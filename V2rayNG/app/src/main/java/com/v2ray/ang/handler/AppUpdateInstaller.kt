package com.v2ray.ang.handler

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads a release APK through the local Xray HTTP inbound when it is
 * available. Android's DownloadManager is a separate system process: it does
 * not inherit the VPN service's network binding or proxy, which made GitHub
 * downloads unreliable on networks where direct GitHub access is filtered.
 */
object AppUpdateInstaller {
    const val PREF_DOWNLOAD_ID = "pref_zimavpn_update_download_id"
    const val PREF_DOWNLOAD_VERSION = "pref_zimavpn_update_download_version"
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    data class DownloadResult(val apkFile: File? = null, val error: String? = null)

    suspend fun download(
        context: Context,
        update: CheckUpdateResult,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val url = update.downloadUrl ?: return@withContext DownloadResult(error = "Missing APK URL")
        val version = update.latestVersion.orEmpty().ifBlank { "update" }
        val safeVersion = version.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(directory, "ZimaVPN-$safeVersion.apk")
        val partial = File(directory, "${apk.name}.part")
        partial.delete()

        val headers = mapOf("Accept" to "application/octet-stream")
        val proxyRequest = UrlContentRequest(
            url = url,
            timeout = 30_000,
            httpPort = SettingsManager.getHttpPort(),
            proxyUsername = SettingsManager.getSocksUsername(),
            proxyPassword = SettingsManager.getSocksPassword(),
            headers = headers,
        )
        val directRequest = proxyRequest.copy(httpPort = 0, proxyUsername = null, proxyPassword = null)

        // Tunnel first; only then use the direct Android route for VPN-off use.
        val downloaded = HttpUtil.downloadToFile(proxyRequest, partial, onProgress) || run {
            partial.delete()
            HttpUtil.downloadToFile(directRequest, partial, onProgress)
        }
        if (!downloaded || partial.length() == 0L) {
            partial.delete()
            return@withContext DownloadResult(error = "Unable to download the APK")
        }
        if (apk.exists()) apk.delete()
        if (!partial.renameTo(apk)) {
            partial.delete()
            return@withContext DownloadResult(error = "Unable to save the APK")
        }
        DownloadResult(apkFile = apk)
    }

    fun launchDownloadedApk(context: Context, apk: File): Boolean {
        if (!apk.isFile || apk.length() == 0L) return false
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.cache", apk)
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply {
                clipData = ClipData.newRawUri("ZimaVPN update", apkUri)
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
}
