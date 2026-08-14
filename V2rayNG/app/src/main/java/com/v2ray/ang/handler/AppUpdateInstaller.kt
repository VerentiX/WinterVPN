package com.v2ray.ang.handler

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.multiprocess.RemoteWorkManager
import java.util.concurrent.TimeUnit
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.worker.AppUpdateDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads a release APK through the local Xray HTTP inbound when it is
 * available. Android's DownloadManager is a separate system process: it does
 * not inherit the VPN service's network binding or proxy, which made GitHub
 * downloads unreliable on networks where direct GitHub access is filtered.
 *
 * Background downloads use HTTP Range resume so Wi‑Fi/cellular switches and
 * brief offline gaps only continue the partial file.
 */
object AppUpdateInstaller {
    const val PREF_DOWNLOAD_ID = "pref_zimavpn_update_download_id"
    const val PREF_DOWNLOAD_VERSION = "pref_zimavpn_update_download_version"
    const val PREF_PENDING_VERSION = "pref_zimavpn_update_pending_version"
    const val PREF_PENDING_URL = "pref_zimavpn_update_pending_url"
    const val PREF_PENDING_NOTES = "pref_zimavpn_update_pending_notes"
    const val PREF_PENDING_STATUS = "pref_zimavpn_update_pending_status"
    const val PREF_READY_APK_PATH = "pref_zimavpn_update_ready_apk_path"
    const val STATUS_NONE = ""
    const val STATUS_DOWNLOADING = "downloading"
    const val STATUS_READY = "ready"
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    const val DOWNLOAD_WORK_NAME = "zimavpn_app_update_download_v1"

    data class DownloadResult(val apkFile: File? = null, val error: String? = null)

    data class ReadyUpdate(
        val version: String,
        val releaseNotes: String?,
        val apkFile: File,
    )

    fun updatesDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    fun apkFileForVersion(context: Context, version: String): File {
        val safeVersion = version.replace(Regex("[^0-9A-Za-z._-]"), "_")
        return File(updatesDir(context), "Winter-$safeVersion.apk")
    }

    fun partialFileFor(apk: File): File = File(apk.parentFile, "${apk.name}.part")

    fun rememberPendingUpdate(update: CheckUpdateResult) {
        val version = update.latestVersion.orEmpty()
        val url = update.downloadUrl.orEmpty()
        if (version.isBlank() || url.isBlank()) return
        val previousVersion = MmkvManager.decodeSettingsString(PREF_PENDING_VERSION).orEmpty()
        val status = MmkvManager.decodeSettingsString(PREF_PENDING_STATUS).orEmpty()
        MmkvManager.encodeSettings(PREF_PENDING_VERSION, version)
        MmkvManager.encodeSettings(PREF_PENDING_URL, url)
        MmkvManager.encodeSettings(PREF_PENDING_NOTES, update.releaseNotes.orEmpty())
        MmkvManager.encodeSettings(PREF_DOWNLOAD_VERSION, version)
        // Keep READY only when the same version is already downloaded; otherwise (re)start download.
        if (status == STATUS_READY && previousVersion == version) {
            return
        }
        MmkvManager.encodeSettings(PREF_PENDING_STATUS, STATUS_DOWNLOADING)
    }

    fun markReady(apk: File, version: String) {
        MmkvManager.encodeSettings(PREF_PENDING_VERSION, version)
        MmkvManager.encodeSettings(PREF_DOWNLOAD_VERSION, version)
        MmkvManager.encodeSettings(PREF_READY_APK_PATH, apk.absolutePath)
        MmkvManager.encodeSettings(PREF_PENDING_STATUS, STATUS_READY)
    }

    fun clearPendingUpdate() {
        MmkvManager.encodeSettings(PREF_PENDING_VERSION, "")
        MmkvManager.encodeSettings(PREF_PENDING_URL, "")
        MmkvManager.encodeSettings(PREF_PENDING_NOTES, "")
        MmkvManager.encodeSettings(PREF_PENDING_STATUS, STATUS_NONE)
        MmkvManager.encodeSettings(PREF_READY_APK_PATH, "")
        clearDownloadRecord()
    }

    fun enqueueBackgroundDownload(context: Context, update: CheckUpdateResult) {
        if (!update.hasUpdate) return
        val version = update.latestVersion.orEmpty()
        val url = update.downloadUrl.orEmpty()
        if (version.isBlank() || url.isBlank()) return

        val ready = getReadyUpdate(context)
        if (ready != null && ready.version == version) {
            LogUtil.i(AppConfig.TAG, "Update APK already ready for $version")
            return
        }

        val previousVersion = MmkvManager.decodeSettingsString(PREF_PENDING_VERSION).orEmpty()
        rememberPendingUpdate(update)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DOWNLOAD_WORK_NAME)
            .build()
        // KEEP avoids canceling an in-flight Range download for the same version.
        // REPLACE starts fresh when the target version changes.
        val policy = if (previousVersion.isNotBlank() && previousVersion != version) {
            ExistingWorkPolicy.REPLACE
        } else {
            ExistingWorkPolicy.KEEP
        }
        RemoteWorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(DOWNLOAD_WORK_NAME, policy, request)
        LogUtil.i(AppConfig.TAG, "Enqueued background update download for $version")
    }

    /** Re-queue a previously remembered update if the APK is not ready yet. */
    fun resumePendingDownloadIfNeeded(context: Context) {
        if (getReadyUpdate(context) != null) return
        val status = MmkvManager.decodeSettingsString(PREF_PENDING_STATUS).orEmpty()
        if (status != STATUS_DOWNLOADING) return
        val pending = pendingCheckResult() ?: return
        enqueueBackgroundDownload(context, pending)
    }

    fun getReadyUpdate(context: Context): ReadyUpdate? {
        val status = MmkvManager.decodeSettingsString(PREF_PENDING_STATUS).orEmpty()
        if (status != STATUS_READY) return null
        val version = MmkvManager.decodeSettingsString(PREF_PENDING_VERSION).orEmpty()
        if (version.isBlank()) return null
        if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) {
            clearPendingUpdate()
            return null
        }
        val path = MmkvManager.decodeSettingsString(PREF_READY_APK_PATH).orEmpty()
        val apk = when {
            path.isNotBlank() -> File(path)
            else -> apkFileForVersion(context, version)
        }
        if (!apk.isFile || apk.length() == 0L) {
            MmkvManager.encodeSettings(PREF_PENDING_STATUS, STATUS_DOWNLOADING)
            return null
        }
        val notes = MmkvManager.decodeSettingsString(PREF_PENDING_NOTES)
        return ReadyUpdate(version = version, releaseNotes = notes, apkFile = apk)
    }

    fun pendingCheckResult(): CheckUpdateResult? {
        val version = MmkvManager.decodeSettingsString(PREF_PENDING_VERSION).orEmpty()
        val url = MmkvManager.decodeSettingsString(PREF_PENDING_URL).orEmpty()
        if (version.isBlank() || url.isBlank()) return null
        return CheckUpdateResult(
            hasUpdate = true,
            latestVersion = version,
            releaseNotes = MmkvManager.decodeSettingsString(PREF_PENDING_NOTES),
            downloadUrl = url,
        )
    }

    suspend fun download(
        context: Context,
        update: CheckUpdateResult,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val url = update.downloadUrl ?: return@withContext DownloadResult(error = "Missing APK URL")
        val version = update.latestVersion.orEmpty().ifBlank { "update" }
        rememberPendingUpdate(update)
        MmkvManager.encodeSettings(PREF_PENDING_STATUS, STATUS_DOWNLOADING)

        val apk = apkFileForVersion(context, version)
        val partial = partialFileFor(apk)

        // Drop stale complete APKs for other versions.
        updatesDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk") && it.absolutePath != apk.absolutePath }
            ?.forEach { runCatching { it.delete() } }

        if (apk.isFile && apk.length() > 0L) {
            markReady(apk, version)
            onProgress(apk.length(), apk.length())
            return@withContext DownloadResult(apkFile = apk)
        }

        val headers = mapOf("Accept" to "application/octet-stream")
        val proxyRequest = UrlContentRequest(
            url = url,
            timeout = 60_000,
            httpPort = SettingsManager.getHttpPort(),
            proxyUsername = SettingsManager.getSocksUsername(),
            proxyPassword = SettingsManager.getSocksPassword(),
            headers = headers,
        )
        val directRequest = proxyRequest.copy(httpPort = 0, proxyUsername = null, proxyPassword = null)

        // Prefer tunnel, fall back to direct; both support Range resume.
        val downloaded = HttpUtil.downloadToFile(proxyRequest, partial, resume = true, onProgress = onProgress) ||
            HttpUtil.downloadToFile(directRequest, partial, resume = true, onProgress = onProgress)
        if (!downloaded || partial.length() == 0L) {
            return@withContext DownloadResult(error = "Unable to download the APK")
        }
        if (apk.exists()) apk.delete()
        if (!partial.renameTo(apk)) {
            // Cross-filesystem rename can fail; copy then delete.
            runCatching {
                partial.inputStream().use { input ->
                    apk.outputStream().use { output -> input.copyTo(output) }
                }
                partial.delete()
            }.onFailure {
                return@withContext DownloadResult(error = "Unable to save the APK")
            }
        }
        markReady(apk, version)
        DownloadResult(apkFile = apk)
    }

    fun launchDownloadedApk(context: Context, apk: File): Boolean {
        if (!apk.isFile || apk.length() == 0L) return false
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.cache", apk)
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply {
                clipData = ClipData.newRawUri("Winter update", apkUri)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runCatching {
            context.startActivity(installIntent)
            true
        }.getOrDefault(false)
    }

    fun clearDownloadRecord() {
        MmkvManager.encodeSettings(PREF_DOWNLOAD_ID, -1L)
        MmkvManager.encodeSettings(PREF_DOWNLOAD_VERSION, "")
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val v1 = version1.split(".").map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val v2 = version2.split(".").map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = if (i < v1.size) v1[i] else 0
            val num2 = if (i < v2.size) v2[i] else 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }
}
