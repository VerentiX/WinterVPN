package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/**
 * Happ-style RoscomVPN routing profiles applied at Smart Priority activate time.
 *
 * p0–p4 use the full/default profile; p5+ always uses the ISP-whitelist profile.
 * Structural balancer / inbound / port-only rules are preserved; only user domain/IP
 * rules and DNS hosts are rewritten. The selected [route-pN] outbound is never reset here.
 */
internal object RoscomPriorityRouting {
    /** First numeric priority that uses the whitelist profile (route-p0005…). */
    const val WHITELIST_MIN_PRIORITY = 5

    enum class Mode { FULL, WHITELIST }

    /** ASN 32934 plus geoip; Instagram MQTT/API often connect by IP. */
    internal val FACEBOOK_PROXY_IP = listOf(
        "geoip:facebook",
        "31.13.24.0/21",
        "31.13.64.0/18",
        "45.64.40.0/22",
        "66.220.144.0/20",
        "69.63.176.0/20",
        "69.171.224.0/19",
        "74.119.76.0/22",
        "102.132.96.0/20",
        "103.4.96.0/22",
        "129.134.0.0/17",
        "157.240.0.0/16",
        "163.70.128.0/17",
        "173.252.64.0/19",
        "179.60.192.0/22",
        "185.60.216.0/22",
        "185.89.218.0/23",
        "199.201.64.0/22",
        "204.15.20.0/22",
    )

    data class Profile(
        val name: String,
        val routeOrder: List<String>,
        val domainStrategy: String,
        val dnsHosts: Map<String, String>,
        val remoteDns: String,
        val domesticDns: String,
        val directSites: List<String>,
        val directIp: List<String>,
        val proxySites: List<String>,
        val proxyIp: List<String>,
        val blockSites: List<String>,
        val blockIp: List<String>,
    )

    val FULL = Profile(
        name = "RoscomVPN",
        routeOrder = listOf("block", "proxy", "direct"),
        domainStrategy = "IPIfNonMatch",
        dnsHosts = mapOf(
            "lkfl2.nalog.ru" to "213.24.64.175",
            "lknpd.nalog.ru" to "213.24.64.181",
        ),
        remoteDns = "https://8.8.8.8/dns-query",
        domesticDns = "https://77.88.8.8/dns-query",
        directSites = listOf(
            // GMS push (mtalk / alt*-mtalk): keep off VPN to avoid radio/CPU heat.
            "keyword:mtalk.google.com",
            "domain:mtalk.google.com",
            "geosite:private",
            "geosite:category-ru",
            "geosite:whitelist",
            "geosite:microsoft",
            "geosite:apple",
            "geosite:epicgames",
            "geosite:riot",
            "geosite:escapefromtarkov",
            "geosite:steam",
            "geosite:twitch",
            "geosite:pinterest",
            "geosite:faceit",
        ),
        directIp = listOf(
            "geoip:private",
            "geoip:direct",
        ),
        proxySites = listOf(
            // Narrow Play hosts only — geosite:google-play also matches mtalk and
            // caused auto-proxy-in -> proxy loops when sniffed.
            "domain:play.google.com",
            "domain:play.googleapis.com",
            "domain:googleapis.cn",
            "geosite:github",
            "geosite:twitch-ads",
            "geosite:youtube",
            "geosite:telegram",
        ),
        proxyIp = emptyList(),
        blockSites = listOf(
            "geosite:win-spy",
            "geosite:torrent",
            "geosite:category-ads",
        ),
        blockIp = emptyList(),
    )

    val WHITELIST = Profile(
        name = "RoscomVPN Whitelist",
        routeOrder = listOf("block", "proxy", "direct"),
        domainStrategy = "IPIfNonMatch",
        dnsHosts = mapOf(
            "lkfl2.nalog.ru" to "213.24.64.175",
            "lknpd.nalog.ru" to "213.24.64.181",
        ),
        remoteDns = "https://8.8.8.8/dns-query",
        domesticDns = "https://77.88.8.8/dns-query",
        directSites = listOf(
            "keyword:mtalk.google.com",
            "domain:mtalk.google.com",
            "geosite:private",
            "geosite:whitelist",
        ),
        directIp = listOf(
            "geoip:private",
            "geoip:whitelist",
        ),
        proxySites = listOf(
            "domain:sberbank.ru",
            "domain:sberbank.com",
            "domain:sber.ru",
            "domain:sberbank.app",
            "domain:sberdevices.ru",
            "domain:tbank.ru",
            "domain:tinkoff.ru",
            "domain:tinkoff.com",
            "domain:tcsbank.ru",
        ),
        proxyIp = emptyList(),
        blockSites = listOf(
            "geosite:category-ads",
            "geosite:category-ads-all",
        ),
        blockIp = emptyList(),
    )

    fun modeForRouteTag(routeTag: String): Mode {
        val priority = routePriority(routeTag) ?: return Mode.FULL
        val minPriority = WinterRoutingProfiles.load()?.whitelistMinPriority
            ?: WHITELIST_MIN_PRIORITY
        return if (priority >= minPriority) Mode.WHITELIST else Mode.FULL
    }

    fun modeForActiveRoute(plan: PriorityFailoverConfig.Plan, activeIndex: Int): Mode {
        val route = plan.routes.getOrNull(activeIndex) ?: return Mode.FULL
        return modeForRouteTag(route)
    }

    fun profileModeForRouteTag(routeTag: String): Mode = modeForRouteTag(routeTag)

    fun profileModeForActiveRoute(
        plan: PriorityFailoverConfig.Plan,
        activeIndex: Int,
    ): Mode = modeForActiveRoute(plan, activeIndex)

    fun profileFor(mode: Mode): Profile {
        val remote = WinterRoutingProfiles.load()
        return when (mode) {
            Mode.FULL -> remote?.defaultProfile ?: FULL
            Mode.WHITELIST -> remote?.whitelistProfile ?: WHITELIST
        }
    }

    /**
     * Rewrite user routing + DNS hosts for the active priority route.
     * Returns false when the config lacks proxy/direct/block outbounds.
     */
    fun apply(
        config: JsonObject,
        plan: PriorityFailoverConfig.Plan,
        activeIndex: Int,
    ): Boolean {
        val mode = profileModeForActiveRoute(plan, activeIndex)
        val profile = profileFor(mode)
        val tags = resolveOutboundTags(config.arrayOrNull("outbounds")) ?: run {
            runCatching {
                LogUtil.w(
                    AppConfig.TAG,
                    "Roscom priority routing skipped: missing proxy/direct/block outbounds",
                )
            }
            return false
        }
        val routing = config.objectOrNull("routing") ?: return false
        val originalRules = routing.arrayOrNull("rules") ?: JsonArray()
        val preserved = JsonArray()
        originalRules.forEach { element ->
            val rule = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (!isReplaceableUserRule(rule, tags)) {
                preserved.add(rule.deepCopy())
            }
        }

        val userRules = buildUserRules(profile, tags)
        // inboundTag bridges first, then UDP/443 (Meta QUIC bypass, then YouTube block).
        // Otherwise a proxy-loopback QUIC exception rematches on auto-proxy-in.
        val inboundStructural = JsonArray()
        val quicStructural = JsonArray()
        val restPreserved = JsonArray()
        preserved.forEach { element ->
            val rule = element.takeIf { it.isJsonObject }?.asJsonObject
            when {
                rule == null -> restPreserved.add(element)
                rule.has("inboundTag") || rule.has("balancerTag") -> inboundStructural.add(element)
                isUdp443Rule(rule) -> quicStructural.add(element)
                else -> restPreserved.add(element)
            }
        }
        val merged = JsonArray()
        inboundStructural.forEach { merged.add(it) }
        merged.add(metaQuicProxyRule(tags))
        quicStructural.forEach { merged.add(it) }
        userRules.forEach { merged.add(it) }
        restPreserved.forEach { merged.add(it) }
        routing.add("rules", merged)
        routing.addProperty("domainStrategy", profile.domainStrategy)
        mergeDns(config, profile)

        runCatching {
            LogUtil.transport(
                "Roscom priority routing=${profile.name} " +
                    "(mode=$mode, route=${plan.routes.getOrNull(activeIndex)})",
            )
        }
        return true
    }

    private fun buildUserRules(profile: Profile, tags: OutboundTags): List<JsonObject> {
        val rules = mutableListOf<JsonObject>()
        // GCM/MCS must be direct BEFORE any ProxySites (google-play includes mtalk).
        rules += JsonObject().apply {
            addProperty("type", "field")
            addProperty("port", "5228")
            addProperty("network", "tcp")
            addProperty("outboundTag", tags.direct)
        }
        addDomainRule(
            rules,
            listOf("keyword:mtalk.google.com", "domain:mtalk.google.com"),
            tags.direct,
        )
        profile.routeOrder.forEach { step ->
            when (step) {
                "block" -> {
                    addDomainRule(rules, profile.blockSites, tags.block)
                    addIpRule(rules, profile.blockIp, tags.block)
                }
                "proxy" -> {
                    // Keep GMS out of google-play / other proxy geosites.
                    val proxySites = profile.proxySites.filterNot {
                        it.contains("mtalk", ignoreCase = true)
                    }
                    addDomainRule(rules, proxySites, tags.proxy)
                    addIpRule(rules, profile.proxyIp, tags.proxy)
                }
                "direct" -> {
                    addDomainRule(rules, profile.directSites, tags.direct)
                    addIpRule(rules, profile.directIp, tags.direct)
                }
            }
        }
        return rules
    }

    private fun addDomainRule(rules: MutableList<JsonObject>, domains: List<String>, outboundTag: String) {
        if (domains.isEmpty()) return
        rules += JsonObject().apply {
            addProperty("type", "field")
            add("domain", JsonArray().apply { domains.forEach { add(it) } })
            addProperty("outboundTag", outboundTag)
        }
    }

    private fun addIpRule(rules: MutableList<JsonObject>, ips: List<String>, outboundTag: String) {
        if (ips.isEmpty()) return
        rules += JsonObject().apply {
            addProperty("type", "field")
            add("ip", JsonArray().apply { ips.forEach { add(it) } })
            addProperty("outboundTag", outboundTag)
        }
    }

    private fun mergeDns(config: JsonObject, profile: Profile) {
        val dns = config.objectOrNull("dns") ?: JsonObject().also { config.add("dns", it) }
        val hosts = dns.objectOrNull("hosts") ?: JsonObject().also { dns.add("hosts", it) }
        profile.dnsHosts.forEach { (domain, ip) ->
            hosts.addProperty(domain, ip)
        }
        // Keep existing server endpoints when present; only ensure DoH URLs from the profile
        // are discoverable for configs that already follow the Happ remote/domestic layout.
        val servers = dns.arrayOrNull("servers") ?: return
        servers.forEach { element ->
            val server = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val address = server.stringOrNull("address") ?: return@forEach
            when (address) {
                profile.remoteDns -> {
                    if (profile.proxySites.isNotEmpty()) {
                        server.add("domains", JsonArray().apply { profile.proxySites.forEach { add(it) } })
                    }
                }
                profile.domesticDns -> {
                    if (profile.directSites.isNotEmpty()) {
                        server.add("domains", JsonArray().apply { profile.directSites.forEach { add(it) } })
                    }
                }
            }
        }
    }

    private data class OutboundTags(val proxy: String, val direct: String, val block: String)

    private fun resolveOutboundTags(outbounds: JsonArray?): OutboundTags? {
        if (outbounds == null) return null
        var proxy: String? = null
        var direct: String? = null
        var block: String? = null
        var loopbackProxy: String? = null

        outbounds.forEach { element ->
            val outbound = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val tag = outbound.stringOrNull("tag") ?: return@forEach
            when (outbound.stringOrNull("protocol")) {
                "freedom" -> if (direct == null || tag == AppConfig.TAG_DIRECT) direct = tag
                "blackhole" -> if (block == null || tag == AppConfig.TAG_BLOCKED) block = tag
                "loopback" -> {
                    val inbound = outbound.objectOrNull("settings")?.stringOrNull("inboundTag")
                    if (inbound == "auto-proxy-in") {
                        loopbackProxy = tag
                    }
                }
            }
            when (tag) {
                AppConfig.TAG_PROXY -> proxy = tag
                AppConfig.TAG_DIRECT -> direct = tag
                AppConfig.TAG_BLOCKED -> block = tag
            }
        }
        if (proxy == null) proxy = loopbackProxy
        return OutboundTags(
            proxy = proxy ?: return null,
            direct = direct ?: return null,
            block = block ?: return null,
        )
    }

    private fun isReplaceableUserRule(rule: JsonObject, tags: OutboundTags): Boolean {
        if (rule.has("inboundTag")) return false
        if (rule.has("balancerTag")) return false
        if (rule.has("process")) return false
        if (isUdp443Rule(rule)) return false
        if (isPortOnlyRule(rule)) return false
        val outbound = rule.stringOrNull("outboundTag") ?: return false
        if (outbound != tags.proxy && outbound != tags.direct && outbound != tags.block) {
            return false
        }
        return rule.has("domain") || rule.has("ip")
    }

    private fun isUdp443Rule(rule: JsonObject): Boolean {
        if (rule.stringOrNull("port") != "443") return false
        val network = rule.stringOrNull("network") ?: return false
        return network.split(",").any { it.trim().equals("udp", ignoreCase = true) }
    }

    private fun metaQuicProxyRule(tags: OutboundTags): JsonObject = JsonObject().apply {
        addProperty("type", "field")
        addProperty("network", "udp")
        addProperty("port", "443")
        add("ip", JsonArray().apply { FACEBOOK_PROXY_IP.forEach { add(it) } })
        addProperty("outboundTag", tags.proxy)
    }

    private fun isPortOnlyRule(rule: JsonObject): Boolean {
        if (rule.has("domain") || rule.has("ip") || rule.has("inboundTag") ||
            rule.has("balancerTag") || rule.has("process")
        ) {
            return false
        }
        return rule.has("port")
    }

    private fun routePriority(routeTag: String): Int? =
        Regex("""^route-p(\d+)(?:-|$)""")
            .find(routeTag)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arrayOrNull(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
