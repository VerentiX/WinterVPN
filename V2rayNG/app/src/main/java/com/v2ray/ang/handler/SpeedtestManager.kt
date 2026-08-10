package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

object SpeedtestManager {

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        var socket: Socket? = null
        val start = System.currentTimeMillis()

        try {
            socket = Socket()
            socket.connect(InetSocketAddress(url, port), timeoutMs)

            return System.currentTimeMillis() - start
        } catch (e: UnknownHostException) {
            LogUtil.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "socketConnectTime IOException: ${e.message}")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        } finally {
            socket?.let { s ->
                try {
                    if (!s.isClosed) {
                        s.close()
                    }
                } catch (closeEx: IOException) {
                }
            }
        }
        return -1
    }

    fun getRemoteIPInfo(): String? {
        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return null

        val preferred = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL
        val urls = linkedSetOf(preferred).apply {
            add(AppConfig.IP_API_URL)
            addAll(AppConfig.IP_API_URL_FALLBACKS)
        }

        for (url in urls) {
            formatIpInfo(fetchIpApi(url, httpPort, proxyUsername, proxyPassword))?.let { return it }
        }
        return null
    }

    private fun fetchIpApi(
        url: String,
        httpPort: Int,
        proxyUsername: String?,
        proxyPassword: String?
    ): IPAPIInfo? {
        val content = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 8000,
                httpPort = httpPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword
            )
        ) ?: return null
        return JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java).also {
            if (it == null) {
                LogUtil.w(AppConfig.TAG, "IP API parse failed for $url")
            }
        }
    }

    private fun formatIpInfo(ipInfo: IPAPIInfo?): String? {
        if (ipInfo == null) return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val countryCode = listOf(
            ipInfo.country_code,
            ipInfo.countryCode,
            ipInfo.country_iso,
            ipInfo.location?.country_code
        ).firstOrNull { !it.isNullOrBlank() && it.length <= 3 }

        val countryName = listOf(
            ipInfo.country_name,
            ipInfo.country?.takeIf { it.length > 3 }
        ).firstOrNull { !it.isNullOrBlank() }

        val country = when {
            !countryCode.isNullOrBlank() && !countryName.isNullOrBlank() -> "$countryName ($countryCode)"
            !countryCode.isNullOrBlank() -> countryCode
            !countryName.isNullOrBlank() -> countryName
            !ipInfo.country.isNullOrBlank() -> ipInfo.country
            else -> null
        }

        return when {
            !country.isNullOrBlank() && !ip.isNullOrBlank() -> "$ip\n$country"
            !ip.isNullOrBlank() -> ip
            !country.isNullOrBlank() -> country
            else -> null
        }
    }
}
