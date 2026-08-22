package com.v2ray.ang.handler

import android.provider.Settings
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.LampaPaymentCreateRequest
import com.v2ray.ang.dto.LampaPaymentCreateResponse
import com.v2ray.ang.dto.LampaPaymentStatusResponse
import com.v2ray.ang.dto.LampaSubscriptionResponse
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object LampaBillingClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private const val TIMEOUT_DIRECT_MS = 20_000L
    private const val TIMEOUT_PROXY_MS = 8_000L

    private fun userAgent(): String = "${AppConfig.LAMPA_SUBSCRIPTION_USER_AGENT}/${BuildConfig.VERSION_NAME}"

    private fun candidateBases(): List<String> = listOf(
        "https://${AppConfig.SUBSCRIPTION_PRIMARY_HOST}",
        "https://${AppConfig.SUBSCRIPTION_FALLBACK_HOST}",
    )

    fun fetchSubscription(subId: String): LampaSubscriptionResponse? =
        getJson("/api/app/subscription/$subId", LampaSubscriptionResponse::class.java)

    fun createPayment(subId: String, planId: String, method: String, test: Boolean = false): LampaPaymentCreateResponse? {
        val body = JsonUtil.toJson(LampaPaymentCreateRequest(subId, planId, method, test))
        return postJson("/api/app/payment", body, LampaPaymentCreateResponse::class.java)
    }

    fun paymentStatus(orderId: String, subId: String): LampaPaymentStatusResponse? =
        getJson("/api/app/payment/$orderId?subId=${encode(subId)}", LampaPaymentStatusResponse::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T> getJson(path: String, cls: Class<T>): T? {
        for (base in candidateBases()) {
            val result = runWithNetworkFallback { viaProxy ->
                executeGet("$base$path", cls, viaProxy) as T?
            }
            if (result != null) return result
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> postJson(path: String, jsonBody: String, cls: Class<T>): T? {
        for (base in candidateBases()) {
            val result = runWithNetworkFallback { viaProxy ->
                executePost("$base$path", jsonBody, cls, viaProxy) as T?
            }
            if (result != null) return result
        }
        return null
    }

    /**
     * CoreController.isRunning is false in the UI process while VPN runs in :bg.
     * Detect the local HTTP inbound instead and prefer it when available.
     */
    private fun <T> runWithNetworkFallback(block: (viaProxy: Boolean) -> T?): T? {
        if (SpeedtestManager.isLocalHttpInboundAvailable()) {
            runCatching { block(true) }.getOrNull()?.let { return it }
        }
        return runCatching { block(false) }.getOrNull()
    }

    private fun buildClient(viaProxy: Boolean): OkHttpClient {
        val timeoutMs = if (viaProxy) TIMEOUT_PROXY_MS else TIMEOUT_DIRECT_MS
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))

        if (!viaProxy) return builder.build()

        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return builder.build()

        builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, httpPort)))
        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        if (!proxyUsername.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) {
                    null
                } else {
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
                        .build()
                }
            }
        }
        return builder.build()
    }

    private fun applyProxyAuth(requestBuilder: Request.Builder, viaProxy: Boolean) {
        if (!viaProxy) return
        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return
        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        if (!proxyUsername.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
            requestBuilder.header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
        }
    }

    private fun <T> executeGet(url: String, cls: Class<T>, viaProxy: Boolean): T? {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent())
            .header("X-Lampa-Device-Id", deviceId())
            .header("Accept", "application/json")
            .header("Connection", "close")
            .get()
        applyProxyAuth(requestBuilder, viaProxy)
        return executeRequest(requestBuilder.build(), url, viaProxy, cls)
    }

    private fun <T> executePost(url: String, jsonBody: String, cls: Class<T>, viaProxy: Boolean): T? {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent())
            .header("X-Lampa-Device-Id", deviceId())
            .header("Accept", "application/json")
            .header("Connection", "close")
            .post(jsonBody.toRequestBody(jsonMedia))
        applyProxyAuth(requestBuilder, viaProxy)
        return executeRequest(requestBuilder.build(), url, viaProxy, cls)
    }

    private fun <T> executeRequest(request: Request, url: String, viaProxy: Boolean, cls: Class<T>): T? {
        val via = if (viaProxy) "proxy" else "direct"
        buildClient(viaProxy).newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                LogUtil.w(AppConfig.TAG, "Lampa billing ${request.method} $url ($via) -> ${response.code}: ${text.take(200)}")
                return JsonUtil.fromJsonSafe(text, cls)
            }
            return JsonUtil.fromJsonSafe(text, cls)
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun deviceId(): String {
        val androidId = Settings.Secure.getString(
            AngApplication.application.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        if (androidId.isNotBlank()) return androidId
        val key = "LAMPA_INSTALL_DEVICE_ID"
        MmkvManager.decodeSettingsString(key)?.takeIf { it.isNotBlank() }?.let { return it }
        return java.util.UUID.randomUUID().toString().also { MmkvManager.encodeSettings(key, it) }
    }
}
