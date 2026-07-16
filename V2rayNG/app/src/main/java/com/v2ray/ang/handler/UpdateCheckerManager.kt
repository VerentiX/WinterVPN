package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateCheckerManager {
    private fun githubHeaders(): Map<String, String> = buildMap {
        put("Accept", "application/vnd.github+json")
        put("X-GitHub-Api-Version", "2022-11-28")
    }

    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val url = if (includePreRelease) {
            AppConfig.APP_API_URL
        } else {
            AppConfig.APP_API_URL.concatUrl("latest")
        }

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()

        // Prefer the local Xray HTTP inbound while the VPN is active. The app
        // process itself is bound to the physical network to avoid a VPN loop,
        // so a direct request can otherwise be blocked even though the tunnel
        // is healthy. Direct access remains a fallback for VPN-off updates.
        val httpPort = SettingsManager.getHttpPort()
        val responseThroughTunnel = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000,
                httpPort = httpPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword,
                headers = githubHeaders()
            )
        )
        val response = responseThroughTunnel ?: HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 5000,
                    headers = githubHeaders()
                )
            )
            ?: throw IllegalStateException("Failed to get response")

        val latestRelease = if (includePreRelease) {
            JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)
                ?.firstOrNull()
                ?: throw IllegalStateException("No pre-release found")
        } else {
            JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
        }
        if (latestRelease == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        val latestVersion = latestRelease.tagName.removePrefix("v")
        LogUtil.i(
            AppConfig.TAG,
            "Found new version: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        return@withContext if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            val downloadUrl = getDownloadUrl(latestRelease, Build.SUPPORTED_ABIS[0])
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = downloadUrl,
                isPreRelease = latestRelease.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
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

    private fun getDownloadUrl(release: GitHubRelease, abi: String): String {
        val fDroid = "fdroid"

        val apkAssets = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val wantsFdroid = BuildConfig.APPLICATION_ID.contains(fDroid, ignoreCase = true)
        fun matchesDistribution(name: String): Boolean =
            name.contains(fDroid, ignoreCase = true) == wantsFdroid

        val asset = apkAssets.firstOrNull {
            it.name.contains(abi, ignoreCase = true) && matchesDistribution(it.name)
        } ?: apkAssets.firstOrNull {
            it.name.contains("universal", ignoreCase = true) && matchesDistribution(it.name)
        } ?: apkAssets.firstOrNull { it.name.contains(abi, ignoreCase = true) }
            ?: apkAssets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apkAssets.singleOrNull()

        return asset?.browserDownloadUrl
            ?: throw IllegalStateException("No compatible APK found")
    }
}
