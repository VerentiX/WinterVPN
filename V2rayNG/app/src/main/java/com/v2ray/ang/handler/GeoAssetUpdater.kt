package com.v2ray.ang.handler

import android.content.Context
import androidx.work.*
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.NotificationHelper
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Durable daily GeoSite/GeoIP updater; APK assets remain the offline fallback. */
object GeoAssetUpdater {
    const val PROGRESS_PERCENT = "geo_progress_percent"
    const val INPUT_FORCE = "geo_force_update"
    const val INPUT_RECONNECT = "geo_reconnect_after_update"
    const val VISIBLE_PROGRESS_TAG = "geo_update_visible_progress"
    private const val PREVIOUS_BOOTSTRAP_PREF = "pref_zima_geo_bootstrap_complete_v4"
    private const val FORCE_COOLDOWN_MS = 5 * 60 * 1000L
    @Volatile private var lastForcedUpdateAt = 0L

    private data class RemoteAsset(val name: String, val url: String, val checksumUrl: String? = null)
    private val remoteAssets = listOf(
        RemoteAsset(AppConfig.GEOSITE_DAT, AppConfig.GEOSITE_LATEST_URL, AppConfig.GEOSITE_LATEST_SHA256_URL),
        RemoteAsset(AppConfig.GEOSITE_COMPAT_DAT, AppConfig.GEOSITE_COMPAT_LATEST_URL),
        RemoteAsset(AppConfig.GEOIP_DAT, AppConfig.GEOIP_LATEST_URL, AppConfig.GEOIP_LATEST_SHA256_URL),
        RemoteAsset(AppConfig.GEOIP_COMPAT_DAT, AppConfig.GEOIP_COMPAT_LATEST_URL),
    )

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateTask>(24, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(connectedConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(INPUT_FORCE to false, INPUT_RECONNECT to false))
            .addTag(AppConfig.GEO_PERIODIC_TASK_NAME).build()
        RemoteWorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            AppConfig.GEO_PERIODIC_TASK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    fun scheduleFirstInstall(context: Context): Boolean {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_GEO_BOOTSTRAP_COMPLETE, false)) return false
        if (MmkvManager.decodeSettingsBool(PREVIOUS_BOOTSTRAP_PREF, false) && hasUsableLocalFiles(context)) {
            MmkvManager.encodeSettings(AppConfig.PREF_GEO_BOOTSTRAP_COMPLETE, true)
            return false
        }
        enqueue(context, false, false)
        return true
    }

    fun forceUpdate(context: Context, reconnectAfterUpdate: Boolean = true) {
        val now = System.currentTimeMillis()
        if (now - lastForcedUpdateAt < FORCE_COOLDOWN_MS) return
        lastForcedUpdateAt = now
        enqueue(context, true, reconnectAfterUpdate)
    }

    fun isGeoDataError(message: String?): Boolean {
        val value = message.orEmpty().lowercase()
        return value.contains("common/geodata") || value.contains("geosite.dat") ||
            value.contains("geoip.dat") || value.contains("failed to load geosite") ||
            value.contains("failed to load geoip") || value.contains("illegal domain rule: geosite:") ||
            value.contains("code not found in geosite")
    }

    private fun connectedConstraint() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun enqueue(context: Context, force: Boolean, reconnect: Boolean) {
        val request = OneTimeWorkRequestBuilder<UpdateTask>().setConstraints(connectedConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(INPUT_FORCE to force, INPUT_RECONNECT to reconnect))
            .addTag(if (force) VISIBLE_PROGRESS_TAG else "geo_update_silent").build()
        RemoteWorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            if (force) AppConfig.GEO_MANUAL_TASK_NAME else AppConfig.GEO_BOOTSTRAP_TASK_NAME,
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request
        )
    }

    fun hasUsableLocalFiles(context: Context): Boolean {
        val dir = File(Utils.userAssetPath(context))
        return remoteAssets.all { File(dir, it.name).let { f -> f.isFile && f.length() > 0 } }
    }

    class UpdateTask(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val reconnect = inputData.getBoolean(INPUT_RECONNECT, false)
            report(0, applicationContext.getString(R.string.geo_update_checking), true)
            SettingsManager.initAssets(applicationContext, applicationContext.assets)
            val dir = File(Utils.userAssetPath(applicationContext))
            if (!dir.exists() && !dir.mkdirs()) return retryNotice()
            val downloads = remoteAssets.map { it to File(dir, ".${it.name}.download") }
            val expected = try { remoteAssets.associateWith(::fetchChecksum) }
            catch (e: Exception) { LogUtil.e(AppConfig.TAG, "Geo checksum check failed", e); return retryNotice() }
            var changed = false

            for ((index, pair) in downloads.withIndex()) {
                val (asset, partial) = pair
                val target = File(dir, asset.name)
                val checksum = expected.getValue(asset)
                val span = 90 / downloads.size
                if (checksum != null && target.isFile && sha256(target) == checksum) { partial.delete(); continue }
                val ok = try { download(asset, partial, checksum, index * span, span) }
                catch (e: Exception) { LogUtil.e(AppConfig.TAG, "Geo download failed: ${asset.name}", e); false }
                if (!ok) return retryNotice()
                if (!target.isFile || sha256(target) != sha256(partial)) changed = true
            }
            if (!changed) {
                downloads.forEach { it.second.delete() }
                report(100, applicationContext.getString(R.string.geo_update_up_to_date))
                notifyFinal(R.string.geo_update_up_to_date)
                return Result.success()
            }
            if (!installTogether(dir, downloads.filter { it.second.isFile })) return retryNotice()
            report(100, applicationContext.getString(R.string.geo_update_ready))
            MmkvManager.encodeSettings(AppConfig.PREF_GEO_BOOTSTRAP_COMPLETE, true)
            try {
                withContext(Dispatchers.Main.immediate) {
                    if (reconnect) {
                        if (CoreServiceManager.isRunning()) CoreServiceManager.reloadVService(applicationContext)
                        else CoreServiceManager.startVService(applicationContext)
                    } else if (CoreServiceManager.isRunning()) CoreServiceManager.reloadVService(applicationContext)
                }
            } catch (e: Exception) { LogUtil.e(AppConfig.TAG, "Geo files installed, VPN reload failed", e) }
            notifyFinal(R.string.geo_update_ready)
            return Result.success()
        }

        private suspend fun download(asset: RemoteAsset, partial: File, checksum: String?, base: Int, span: Int) = coroutineScope {
            val latest = AtomicInteger(base)
            val reporter = launch {
                var previous = -1
                while (isActive) {
                    val value = latest.get().coerceIn(0, 90)
                    if (value != previous) { report(value, applicationContext.getString(R.string.geo_update_downloading, value)); previous = value }
                    delay(500)
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    val ok = HttpUtil.downloadToFile(UrlContentRequest(asset.url, timeout = 60_000), partial, resume = true) { done, total ->
                        if (total > 0) latest.set(base + (done * span / total).toInt())
                    }
                    ok && partial.length() > 1024 && (checksum == null || sha256(partial) == checksum)
                }
            } finally { reporter.cancelAndJoin() }
        }

        private fun fetchChecksum(asset: RemoteAsset): String? {
            val url = asset.checksumUrl ?: return null
            val body = HttpUtil.getUrlContent(UrlContentRequest(url, timeout = 20_000)).orEmpty()
            return Regex("(?i)\\b[0-9a-f]{64}\\b").find(body)?.value?.lowercase()
                ?: error("Invalid checksum for ${asset.name}")
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) { val count = input.read(buffer); if (count <= 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private suspend fun report(value: Int, text: String, indeterminate: Boolean = false) {
            setProgress(workDataOf(PROGRESS_PERCENT to value))
            setForeground(NotificationHelper.progressForegroundInfo(
                NotificationChannelType.GEO_UPDATE, applicationContext,
                applicationContext.getString(R.string.geo_update_notification_title), text, value, indeterminate
            ))
        }

        private fun notifyFinal(message: Int) = NotificationHelper.notify(
            NotificationChannelType.GEO_UPDATE, applicationContext,
            applicationContext.getString(R.string.geo_update_notification_title), applicationContext.getString(message)
        )
        private fun retryNotice(): Result { notifyFinal(R.string.geo_update_failed); return Result.retry() }

        private fun installTogether(dir: File, downloads: List<Pair<RemoteAsset, File>>): Boolean {
            val targets = downloads.map { (asset, temp) -> Triple(temp, File(dir, asset.name), File(dir, ".${asset.name}.previous")) }
            targets.forEach { it.third.delete() }
            val backedUp = mutableListOf<Triple<File, File, File>>()
            for (entry in targets) {
                if (entry.second.exists() && !entry.second.renameTo(entry.third)) { backedUp.forEach { it.third.renameTo(it.second) }; return false }
                backedUp.add(entry)
            }
            val installed = mutableListOf<Triple<File, File, File>>()
            for (entry in targets) {
                if (!entry.first.renameTo(entry.second)) {
                    installed.forEach { it.second.delete() }; backedUp.forEach { it.third.renameTo(it.second) }; return false
                }
                installed.add(entry)
            }
            backedUp.forEach { it.third.delete() }
            return true
        }
    }
}
