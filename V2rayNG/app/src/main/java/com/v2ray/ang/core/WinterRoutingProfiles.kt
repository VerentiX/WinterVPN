package com.v2ray.ang.core

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil

/**
 * Remote Winter dual-routing payload from subscription `Profile-Routing` header.
 *
 * Accepted shapes (Happ fields or already-normalized Roscom fields):
 * ```
 * {
 *   "whitelistMinPriority": 5,
 *   "default": { "Name": "...", "DirectSites": [...], ... },
 *   "whitelist": { ... }
 * }
 * ```
 * Aliases: `full`/`default`, `profiles.default` / `profiles.whitelist`.
 */
internal object WinterRoutingProfiles {
    private const val STORAGE_KEY = "pref_winter_routing_profiles"

    data class Bundle(
        val whitelistMinPriority: Int,
        val defaultProfile: RoscomPriorityRouting.Profile,
        val whitelistProfile: RoscomPriorityRouting.Profile,
    )

    fun saveFromHeader(raw: String?): Boolean {
        val bundle = parseHeader(raw) ?: return false
        val json = JsonObject().apply {
            addProperty("whitelistMinPriority", bundle.whitelistMinPriority)
            add("default", profileToJson(bundle.defaultProfile))
            add("whitelist", profileToJson(bundle.whitelistProfile))
        }
        val encoded = JsonUtil.toJson(json)
        if (encoded.isBlank()) return false
        MmkvManager.encodeSettings(STORAGE_KEY, encoded)
        runCatching {
            LogUtil.i(
                AppConfig.TAG,
                "Winter routing profiles updated: " +
                    "default=${bundle.defaultProfile.name}, " +
                    "whitelist=${bundle.whitelistProfile.name}, " +
                    "minPriority=${bundle.whitelistMinPriority}",
            )
        }
        return true
    }

    fun load(): Bundle? = runCatching {
        val raw = MmkvManager.decodeSettingsString(STORAGE_KEY) ?: return@runCatching null
        parseObject(JsonParser.parseString(raw).asJsonObject)
    }.getOrNull()

    fun parseHeader(raw: String?): Bundle? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().removeSurrounding("\"")
        val jsonText = when {
            trimmed.startsWith("base64:", ignoreCase = true) -> {
                val encoded = trimmed.substringAfter(':').trim()
                decodeBase64(encoded) ?: return null
            }
            trimmed.startsWith("{") -> trimmed
            else -> decodeBase64(trimmed) ?: return null
        }
        return runCatching {
            parseObject(JsonParser.parseString(jsonText).asJsonObject)
        }.onFailure {
            runCatching {
                LogUtil.w(AppConfig.TAG, "Failed to parse Profile-Routing: ${it.message}")
            }
        }.getOrNull()
    }

    private fun parseObject(root: JsonObject): Bundle? {
        val profiles = root.memberObject("profiles")
        val defaultObj = root.memberObject("default", "full")
            ?: profiles?.memberObject("default", "full")
            ?: return null
        val whitelistObj = root.memberObject("whitelist")
            ?: profiles?.memberObject("whitelist")
            ?: return null
        val defaultProfile = profileFromJson(defaultObj) ?: return null
        val whitelistProfile = profileFromJson(whitelistObj) ?: return null
        val minPriority = root.get("whitelistMinPriority")
            ?.takeIf { it.isJsonPrimitive }
            ?.asInt
            ?: RoscomPriorityRouting.WHITELIST_MIN_PRIORITY
        return Bundle(minPriority, defaultProfile, whitelistProfile)
    }

    fun profileFromJson(obj: JsonObject): RoscomPriorityRouting.Profile? {
        // Happ-style keys (Name/DirectSites/…) or already-normalized camelCase.
        val name = stringField(obj, "Name", "name") ?: "RoscomVPN"
        val routeOrderRaw = stringField(obj, "RouteOrder", "routeOrder") ?: "block-proxy-direct"
        val routeOrder = routeOrderRaw.split('-')
            .map { it.trim().lowercase() }
            .filter { it in setOf("block", "proxy", "direct") }
            .ifEmpty { listOf("block", "proxy", "direct") }
        val domainStrategy = stringField(obj, "DomainStrategy", "domainStrategy") ?: "IPIfNonMatch"
        val remoteDns = stringField(obj, "RemoteDNSDomain", "remoteDns")
            ?: "https://8.8.8.8/dns-query"
        val domesticDns = stringField(obj, "DomesticDNSDomain", "domesticDns")
            ?: "https://77.88.8.8/dns-query"
        return RoscomPriorityRouting.Profile(
            name = name,
            routeOrder = routeOrder,
            domainStrategy = domainStrategy,
            dnsHosts = stringMapField(obj, "DnsHosts", "dnsHosts"),
            remoteDns = remoteDns,
            domesticDns = domesticDns,
            directSites = stringListField(obj, "DirectSites", "directSites"),
            directIp = stringListField(obj, "DirectIp", "directIp"),
            proxySites = stringListField(obj, "ProxySites", "proxySites"),
            proxyIp = stringListField(obj, "ProxyIp", "proxyIp"),
            blockSites = stringListField(obj, "BlockSites", "blockSites"),
            blockIp = stringListField(obj, "BlockIp", "blockIp"),
        )
    }

    private fun profileToJson(profile: RoscomPriorityRouting.Profile): JsonObject =
        JsonObject().apply {
            addProperty("name", profile.name)
            add("routeOrder", JsonArray().apply { profile.routeOrder.forEach { add(it) } })
            addProperty("domainStrategy", profile.domainStrategy)
            add("dnsHosts", JsonObject().apply {
                profile.dnsHosts.forEach { (k, v) -> addProperty(k, v) }
            })
            addProperty("remoteDns", profile.remoteDns)
            addProperty("domesticDns", profile.domesticDns)
            add("directSites", JsonArray().apply { profile.directSites.forEach { add(it) } })
            add("directIp", JsonArray().apply { profile.directIp.forEach { add(it) } })
            add("proxySites", JsonArray().apply { profile.proxySites.forEach { add(it) } })
            add("proxyIp", JsonArray().apply { profile.proxyIp.forEach { add(it) } })
            add("blockSites", JsonArray().apply { profile.blockSites.forEach { add(it) } })
            add("blockIp", JsonArray().apply { profile.blockIp.forEach { add(it) } })
        }

    private fun JsonObject.memberObject(vararg keys: String): JsonObject? {
        keys.forEach { key ->
            val value = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
            if (value != null) return value
        }
        return null
    }

    private fun stringField(obj: JsonObject, vararg keys: String): String? {
        keys.forEach { key ->
            val value = obj.get(key)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private fun stringListField(obj: JsonObject, vararg keys: String): List<String> {
        keys.forEach { key ->
            val arr = obj.getAsJsonArray(key) ?: return@forEach
            return arr.mapNotNull {
                it.takeIf { el -> el.isJsonPrimitive && el.asJsonPrimitive.isString }?.asString?.trim()
            }.filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    private fun stringMapField(obj: JsonObject, vararg keys: String): Map<String, String> {
        keys.forEach { key ->
            val map = obj.getAsJsonObject(key) ?: return@forEach
            return map.entrySet().mapNotNull { (k, v) ->
                val value = v.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    ?: return@mapNotNull null
                k to value
            }.toMap()
        }
        return emptyMap()
    }

    private fun decodeBase64(encoded: String): String? {
        val padded = encoded + "=".repeat((-encoded.length).mod(4))
        val flags = listOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            Base64.URL_SAFE or Base64.NO_WRAP,
        )
        flags.forEach { flag ->
            val text = runCatching {
                String(Base64.decode(padded, flag), Charsets.UTF_8)
            }.getOrNull()
            if (!text.isNullOrBlank() && text.trimStart().startsWith("{")) return text
        }
        return null
    }
}
