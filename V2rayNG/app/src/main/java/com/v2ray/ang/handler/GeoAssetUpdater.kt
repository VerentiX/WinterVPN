package com.v2ray.ang.handler

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Installs checksum-verified GeoSite/GeoIP assets, retaining APK assets as fallback. */
object GeoAssetUpdater {
    const val PROGRESS_PERCENT = "geo_progress_percent"
    const val INPUT_FORCE = "geo_force_update"
    const val INPUT_RECONNECT = "geo_reconnect_after_update"
    const val VISIBLE_PROGRESS_TAG = "geo_update_visible_progress"
    private const val PREVIOUS_BOOTSTRAP_PREF = "pref_zima_geo_bootstrap_complete_v4"
    private const val FORCE_COOLDOWN_MS = 5 * 60 * 1000L
    @Volatile private var lastForcedUpdateAt = 0L

    private data class RemoteAsset(val name: String, val url: String, val checksum: String)

    private val remoteAssets = listOf(
        RemoteAsset(AppConfig.GEOSITE_DAT, AppConfig.GEOSITE_DAT_URL, AppConfig.GEOSITE_DAT_SHA256),
        RemoteAsset(
            AppConfig.GEOSITE_COMPAT_DAT,
            AppConfig.GEOSITE_COMPAT_DAT_URL,
            AppConfig.GEOSITE_COMPAT_DAT_SHA256
        ),
        RemoteAsset(AppConfig.GEOIP_DAT, AppConfig.GEOIP_DAT_URL, AppConfig.GEOIP_DAT_SHA256),
        RemoteAsset(
            AppConfig.GEOIP_COMPAT_DAT,
            AppConfig.GEOIP_COMPAT_DAT_URL,
            AppConfig.GEOIP_COMPAT_DAT_SHA256
        ),
    )

    fun scheduleFirstInstall(context: Context): Boolean {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_GEO_BOOTSTRAP_COMPLETE, false)) return false
        if (MmkvManager.decodeSettingsBool(PREVIOUS_BOOTSTRAP_PREF, false) &&
            hasUsableLocalFiles(context)
        ) {
            MmkvManager.encodeSettings(AppConfig.PREF_GEO_BOOTSTRAP_COMPLETE, true)
            return false
        }

        enqueue(context, force = false, reconnectAfterUpdate = false)
        return true
    }

    fun forceUpdate(context: Context, reconnectAfterUpdate: Boolean = true) {
        val now = System.currentTimeMillis()
        if (now - lastForcedUpdateAt < FORCE_COOLDOWN_MS) return
        lastForcedUpdateAt = now
        enqueue(context, force = true, reconnectAfterUpdate = reconnectAfterUpdate)
    }

    fun isGeoDataError(message: String?): Boolean {
        val value = message.orEmpty().lowercase()
        return value.contains("common/geodata") ||
            value.contains("geosite.dat") ||
            value.contains("geoip.dat") ||
            value.contains("failed to load geosite") ||
            value.contains("failed to load geoip") ||
            value.contains("illegal domain rule: geosite:") ||
            value.contains("code not found in geosite")
    }

    private fun enqueue(context: Context, force: Boolean, reconnectAfterUpdate: Boolean) {
        val remoteWorkManager = RemoteWorkManager.getInstance(context.applicationContext)
        remoteWorkManager.cancelUniqueWork("roscom_geo_bootstrap")
        remoteWorkManager.cancelUniqueWork("zetya_geo_bootstrap_v2")
        remoteWorkManager.cancelUniqueWork("zima_geo_bootstrap_v3")
        remoteWorkManager.cancelUniqueWork("zima_geo_bootstrap_v4")

        val request = OneTimeWorkRequestBuilder<UpdateTask>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    INPUT_FORCE to force,
                    INPUT_RECONNECT to reconnectAfterUpdate
                )
            )
            .addTag(AppConfig.GEO_BOOTSTRAP_TASK_NAME)
            .addTag(if (force) VISIBLE_PROGRESS_TAG else "geo_update_silent")
            .build()

        remoteWorkManager.enqueueUniqueWork(
            AppConfig.GEO_BOOTSTRAP_TASK_NAME,
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun hasUsableLocalFiles(context: Context): Boolean {
        val assetDir = File(Utils.userAssetPath(context))
        return remoteAssets.all { asset ->
            File(assetDir, asset.name).let { it.isFile && it.length() > 0L }
        }
    }

    class UpdateTask(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val force = inputData.getBoolean(INPUT_FORCE, false)
            val reconnectAfterUpdate = inputData.getBoolean(INPUT_RECONNECT, false)
            if (!force && MmkvManager.decodeSettingsBool(AppConfig.PREF_GEO_BOOTSTRAP_COMPLETE, false)) {
                return Result.success(workDataOf(INPUT_RECONNECT to reconnectAfterUpdate))
            }

            setProgress(workDataOf(PROGRESS_PERCENT to 0))

            // Bundled databases keep the first launch functional while the download waits for network.
            SettingsManager.initAssets(applicationContext, applicationContext.assets)
            val assetDir = File(Utils.userAssetPath(applicationContext))
            if (!assetDir.exists() && !assetDir.mkdirs()) return retryLater()

            val downloads = remoteAssets.map { asset ->
                asset to File(assetDir, ".${asset.name}.${id}.download")
            }
            var downloaded = true
            for ((index, pair) in downloads.withIndex()) {
                if (!downloaded) break
                val (asset, temp) = pair
                val progressSpan = 90 / downloads.size
                val baseProgress = index * progressSpan
                downloaded = try {
                    downloadAndVerify(asset, temp, baseProgress, progressSpan)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "First-install geo download failed: ${asset.name}", e)
                    false
                }
                if (downloaded) {
                    setProgress(workDataOf(PROGRESS_PERCENT to ((index + 1) * progressSpan)))
                }
            }

            if (!downloaded) {
                downloads.forEach { it.second.delete() }
                return retryLater()
            }
            if (!installTogether(assetDir, downloads)) {
                downloads.forEach { it.second.delete() }
                return retryLater()
            }

            setProgress(workDataOf(PROGRESS_PERCENT to 100))
            MmkvManager.encodeSettings(AppConfig.PREF_GEO_BOOTSTRAP_COMPLETE, true)
            LogUtil.i(AppConfig.TAG, "Checksum-verified GeoSite/GeoIP pair installed")
            try {
                withContext(Dispatchers.Main.immediate) {
                    if (reconnectAfterUpdate) {
                        if (CoreServiceManager.isRunning()) {
                            CoreServiceManager.reloadVService(applicationContext)
                        } else {
                            CoreServiceManager.startVService(applicationContext)
                        }
                    } else if (CoreServiceManager.isRunning()) {
                        CoreServiceManager.reloadVService(applicationContext)
                    }
                }
            } catch (e: Exception) {
                // The databases are already installed. A denied service start must not turn
                // a successful, checksum-verified update into a failed WorkManager task.
                LogUtil.e(AppConfig.TAG, "Geo files installed, but VPN reconnect failed", e)
            }
            return Result.success(workDataOf(INPUT_RECONNECT to reconnectAfterUpdate))
        }

        private suspend fun downloadAndVerify(
            asset: RemoteAsset,
            temp: File,
            baseProgress: Int,
            progressSpan: Int
        ): Boolean = coroutineScope {
            val latestProgress = AtomicInteger(baseProgress)
            val reporter = launch {
                var reportedProgress = -1
                while (isActive) {
                    val progress = latestProgress.get().coerceIn(0, 90)
                    if (progress != reportedProgress) {
                        setProgress(workDataOf(PROGRESS_PERCENT to progress))
                        reportedProgress = progress
                    }
                    delay(250L)
                }
            }

            try {
                withContext(Dispatchers.IO) {
                    val downloadedOk = HttpUtil.downloadToFile(
                        UrlContentRequest(url = asset.url, timeout = 20_000),
                        temp
                    ) { downloadedBytes, totalBytes ->
                        if (totalBytes > 0L) {
                            latestProgress.set(
                                baseProgress + (downloadedBytes * progressSpan / totalBytes).toInt()
                            )
                        }
                    } && temp.length() > 0L
                    downloadedOk && verifyChecksum(asset, temp)
                }
            } finally {
                reporter.cancelAndJoin()
                setProgress(
                    workDataOf(PROGRESS_PERCENT to latestProgress.get().coerceIn(0, 90))
                )
            }
        }

        private fun verifyChecksum(asset: RemoteAsset, file: File): Boolean {
            val expected = asset.checksum.lowercase()
            if (!expected.matches(Regex("[0-9a-f]{64}"))) return false

            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                LogUtil.e(AppConfig.TAG, "Checksum mismatch for ${asset.name}")
            }
            return actual == expected
        }

        private fun retryLater(): Result = Result.retry()

        private fun installTogether(
            assetDir: File,
            downloads: List<Pair<RemoteAsset, File>>
        ): Boolean {
            val targets = downloads.map { (asset, temp) ->
                Triple(temp, File(assetDir, asset.name), File(assetDir, ".${asset.name}.previous"))
            }

            targets.forEach { (_, _, backup) -> backup.delete() }
            val backedUp = mutableListOf<Triple<File, File, File>>()
            for (entry in targets) {
                val (_, target, backup) = entry
                if (target.exists() && !target.renameTo(backup)) {
                    backedUp.forEach { (_, oldTarget, oldBackup) -> oldBackup.renameTo(oldTarget) }
                    return false
                }
                backedUp.add(entry)
            }

            val installed = mutableListOf<Triple<File, File, File>>()
            for (entry in targets) {
                val (temp, target, _) = entry
                if (!temp.renameTo(target)) {
                    installed.forEach { (_, newTarget, _) -> newTarget.delete() }
                    backedUp.forEach { (_, oldTarget, oldBackup) -> oldBackup.renameTo(oldTarget) }
                    return false
                }
                installed.add(entry)
            }

            backedUp.forEach { (_, _, backup) -> backup.delete() }
            return true
        }
    }
}
