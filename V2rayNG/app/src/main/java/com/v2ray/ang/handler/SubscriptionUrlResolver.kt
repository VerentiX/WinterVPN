package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import java.net.URI

/** Resolves Хоттабыч subscription URLs with primary/fallback hosts. */
object SubscriptionUrlResolver {

    private val SUB_ID_PATH = Regex("/auto/([A-Za-z0-9_-]{6,128})/?")

    fun extractSubId(url: String?): String? {
        val normalized = url?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return SUB_ID_PATH.find(normalized)?.groupValues?.getOrNull(1)
    }

    fun isManagedSubscriptionUrl(url: String?): Boolean = extractSubId(url) != null

    /** Primary gw.zizmos.ru, then sub.subhotig.buzz, then v.hattabych.ru. */
    fun candidateUrls(originalUrl: String): List<String> {
        val trimmed = originalUrl.trim()
        val subId = extractSubId(trimmed) ?: return listOf(trimmed)
        return listOf(
            AppConfig.SUBSCRIPTION_PRIMARY_HOST,
            AppConfig.SUBSCRIPTION_FALLBACK_HOST,
            AppConfig.SUBSCRIPTION_RESERVE_HOST,
        ).map { buildUrl(it, subId) }.distinct()
    }

    fun normalizeStoredUrl(url: String): String {
        val subId = extractSubId(url) ?: return url.trim()
        return buildUrl(AppConfig.SUBSCRIPTION_PRIMARY_HOST, subId)
    }

    private fun buildUrl(host: String, subId: String): String = "https://$host/auto/$subId"
}
