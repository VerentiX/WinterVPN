package com.v2ray.ang.viewmodel

import androidx.lifecycle.ViewModel
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.AssetUrlCache
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File
import java.security.MessageDigest

class UserAssetViewModel : ViewModel() {
    private val assets = mutableListOf<AssetUrlCache>()
    private val builtInGeoFiles = listOf(AppConfig.GEOSITE_DAT, AppConfig.GEOIP_DAT, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT)

    val itemCount: Int
        get() = assets.size

    fun getAssets(): List<AssetUrlCache> = assets.toList()

    fun getAsset(position: Int): AssetUrlCache? = assets.getOrNull(position)

    fun reload(geoFilesSource: String) {
        val decoded = MmkvManager.decodeAssetUrls()
        assets.clear()
        assets.addAll(buildAssetList(decoded, geoFilesSource))
    }

    private fun buildAssetList(
        decodedAssets: List<AssetUrlCache>?,
        geoFilesSource: String
    ): List<AssetUrlCache> {
        val savedAssets = decodedAssets ?: emptyList()
        val builtInItems = builtInGeoFiles
            .filter { geoFile -> savedAssets.none { it.assetUrl.remarks == geoFile } }
            .map {
                AssetUrlCache(
                    Utils.getUuid(),
                    AssetUrlItem(
                        it,
                        String.format(AppConfig.GITHUB_DOWNLOAD_URL, geoFilesSource).concatUrl(it),
                        locked = true
                    )
                )
            }
        // Keep the two primary databases on a complete V2Fly-compatible source.
        return (builtInItems + savedAssets).map { cache ->
            val canonicalUrl = when (cache.assetUrl.remarks) {
                AppConfig.GEOSITE_DAT -> AppConfig.GEOSITE_DAT_URL
                AppConfig.GEOIP_DAT -> AppConfig.GEOIP_DAT_URL
                AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT -> AppConfig.GEOIP_ONLY_CN_PRIVATE_URL
                else -> null
            }
            if (canonicalUrl != null) {
                cache.copy(
                    assetUrl = cache.assetUrl.copy(
                        url = canonicalUrl
                    )
                )
            } else {
                cache
            }
        }
    }

    fun downloadGeoFiles(
        extDir: File,
        httpPort: Int,
        proxyUsername: String? = null,
        proxyPassword: String? = null
    ): GeoDownloadResult {
        val snapshot = getAssets()
        var successCount = 0
        val failures = mutableListOf<String>()

        snapshot.forEach { cache ->
            val item = cache.assetUrl
            // Geo databases are also needed to recover a broken VPN configuration. Try the
            // active Android network first so a dead 127.0.0.1 proxy cannot block recovery.
            val portsToTry = if (httpPort == 0) listOf(0) else listOf(0, httpPort)
            if (portsToTry.any { tryDownload(item, extDir, it, proxyUsername, proxyPassword) }) {
                successCount++
            } else {
                failures.add(item.remarks)
            }
        }

        return GeoDownloadResult(successCount, failures.size, failures)
    }

    private fun tryDownload(
        item: AssetUrlItem,
        extDir: File,
        httpPort: Int,
        proxyUsername: String? = null,
        proxyPassword: String? = null
    ): Boolean {
        val targetTemp = File(extDir, item.remarks + "_temp")
        val target = File(extDir, item.remarks)
        try {
            if (
                HttpUtil.downloadToFile(
                    UrlContentRequest(
                        url = item.url,
                        timeout = 15000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    ),
                    targetTemp
                )
            ) {
                if (!verifyChecksum(item, targetTemp, httpPort, proxyUsername, proxyPassword)) {
                    LogUtil.e(AppConfig.TAG, "Geo checksum verification failed: ${item.remarks}")
                    return false
                }
                if (targetTemp.renameTo(target)) {
                    return true
                }
                LogUtil.e(AppConfig.TAG, "Failed to atomically replace geo file: ${item.remarks}")
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to download geo file: ${item.remarks}", e)
        } finally {
            if (targetTemp.exists()) targetTemp.delete()
        }
        return false
    }

    private fun verifyChecksum(
        item: AssetUrlItem,
        file: File,
        httpPort: Int,
        proxyUsername: String?,
        proxyPassword: String?
    ): Boolean {
        val checksumUrl = when (item.remarks) {
            AppConfig.GEOSITE_DAT -> AppConfig.GEOSITE_DAT_CHECKSUM_URL
            AppConfig.GEOIP_DAT -> AppConfig.GEOIP_DAT_CHECKSUM_URL
            else -> return true
        }
        val content = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = checksumUrl,
                timeout = 15_000,
                httpPort = httpPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword
            )
        ) ?: return false
        val expected = content.trim().substringBefore(' ').lowercase()
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
        return digest.digest().joinToString("") { "%02x".format(it) } == expected
    }

    data class GeoDownloadResult(
        val successCount: Int,
        val failureCount: Int,
        val failedAssets: List<String>
    )
}
