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

    /**
     * HTVPlayer / Video.js bootstrap hosts that also appear in category-ads.
     * Must be proxied before the ads block rule or the player waits ~10s and fails.
     */
    internal val VIDEO_BOOTSTRAP_DOMAINS = listOf(
        "domain:imasdk.googleapis.com",
        "domain:googleads.g.doubleclick.net",
        "domain:googlesyndication.com",
        "domain:googleadservices.com",
        "domain:cdn.jsdelivr.net",
        "domain:jsdelivr.net",
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
            "geoip:ru",
        ),
        proxySites = listOf(
            // Narrow Play hosts only — full geosite:google-play is too broad.
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
            "geosite:private",
            "geosite:whitelist",
        ),
        directIp = listOf(
            "geoip:private",
            "geoip:whitelist",
            "geoip:ru",
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
            // Explicit: Telegram must stay on proxy in whitelist mode (DCs are
            // not geoip:ru; still pin geosite so DNS/SNI never fall through oddly).
            "geosite:telegram",
        ),
        proxyIp = listOf(
            "geoip:telegram",
        ),
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

    /** Extra SOCKS inbound that carries WHITELIST rules (p5+). p0–p4 stay on mixed/socks. */
    const val INBOUND_WHITELIST = "socks-whitelist"
    const val INBOUND_WHITELIST_PROXY = "auto-proxy-whitelist-in"
    const val OUTBOUND_WHITELIST_PROXY = "proxy-whitelist"
    const val BALANCER_WHITELIST = "tier-whitelist"
    const val WHITELIST_SOCKS_PORT_OFFSET = 10
    private val CLIENT_INBOUND_PROTOCOLS = setOf("socks", "mixed")

    fun profileFor(mode: Mode): Profile {
        val remote = WinterRoutingProfiles.load()
        return when (mode) {
            Mode.FULL -> remote?.defaultProfile ?: FULL
            Mode.WHITELIST -> remote?.whitelistProfile ?: WHITELIST
        }
    }

    fun whitelistSocksPort(basePort: Int): Int {
        val candidate = basePort + WHITELIST_SOCKS_PORT_OFFSET
        return if (candidate <= 65535) {
            candidate
        } else {
            (basePort - WHITELIST_SOCKS_PORT_OFFSET).coerceAtLeast(1)
        }
    }

    fun tunSocksPort(mode: Mode?, basePort: Int): Int {
        return if (mode == Mode.WHITELIST) whitelistSocksPort(basePort) else basePort
    }

    /**
     * Both FULL and WHITELIST live in one Xray process as two inbound-scoped
     * rule tables **and** two proxy chains. p0–p4 traffic stays on `proxy` →
     * auto-proxy-in → P0. p5+ traffic uses [OUTBOUND_WHITELIST_PROXY] →
     * [BALANCER_WHITELIST] (p5+ only). Sharing one balancer was why Telegram
     * still dialed dead P0 after hev moved to the whitelist SOCKS port.
     */
    fun apply(
        config: JsonObject,
        plan: PriorityFailoverConfig.Plan,
        activeIndex: Int,
    ): Boolean {
        val full = profileFor(Mode.FULL)
        val white = profileFor(Mode.WHITELIST)
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
        val (fullInbounds, whiteInbound) = ensureWhitelistInbound(config)
        val whitelistProxy = attachWhitelistProxyChain(config, plan, activeIndex) ?: tags.proxy
        val whiteTags = tags.copy(proxy = whitelistProxy)
        val originalRules = routing.arrayOrNull("rules") ?: JsonArray()
        val preserved = JsonArray()
        originalRules.forEach { element ->
            val rule = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (!isReplaceableUserRule(rule, tags)) {
                preserved.add(rule.deepCopy())
            }
        }

        val fullRules = buildUserRules(full, tags, fullInbounds)
        val whiteRules = buildUserRules(white, whiteTags, listOf(whiteInbound))
        // inboundTag bridges first, then UDP/443 (Meta QUIC bypass, global reject, preserved).
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
        if (whitelistProxy == OUTBOUND_WHITELIST_PROXY) {
            merged.add(whitelistProxyInboundRule())
        }
        merged.add(metaQuicProxyRule(tags.proxy, fullInbounds))
        if (whitelistProxy == OUTBOUND_WHITELIST_PROXY) {
            merged.add(metaQuicProxyRule(whitelistProxy, listOf(whiteInbound)))
        }
        merged.add(quicRejectRule(tags.block))
        quicStructural.forEach { element ->
            val rule = element.takeIf { it.isJsonObject }?.asJsonObject
            if (rule != null && isGlobalQuicRejectRule(rule, tags.block)) {
                return@forEach
            }
            merged.add(element)
        }
        merged.add(videoBootstrapProxyRule(tags.proxy, fullInbounds))
        if (whitelistProxy == OUTBOUND_WHITELIST_PROXY) {
            merged.add(videoBootstrapProxyRule(whitelistProxy, listOf(whiteInbound)))
        }
        fullRules.forEach { merged.add(it) }
        whiteRules.forEach { merged.add(it) }
        if (whitelistProxy == OUTBOUND_WHITELIST_PROXY) {
            merged.add(whitelistCatchAll(whiteInbound, whitelistProxy))
        }
        restPreserved.forEach { merged.add(it) }
        routing.add("rules", merged)
        routing.addProperty("domainStrategy", full.domainStrategy)
        mergeDns(config, full, white)
        pinBalancersToActiveRoute(config, plan, activeIndex)

        runCatching {
            LogUtil.transport(
                "Roscom dual routing tables FULL+WHITELIST " +
                    "(p0–p4=${fullInbounds.joinToString()}→${tags.proxy}; " +
                    "p5+=$whiteInbound→$whitelistProxy; " +
                    "route=${plan.routes.getOrNull(activeIndex)}; hev port follows p-tier)"
            )
        }
        return true
    }

    /**
     * Clone the primary SOCKS/mixed inbound as [INBOUND_WHITELIST] on port+10.
     * Hev is moved to that port when the active route is p5+; Xray stays up.
     */
    internal fun ensureWhitelistInbound(config: JsonObject): Pair<List<String>, String> {
        val inbounds = config.arrayOrNull("inbounds")
            ?: JsonArray().also { config.add("inbounds", it) }
        val primary = inbounds.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject
        }.firstOrNull { inbound ->
            val tag = inbound.stringOrNull("tag").orEmpty()
            val protocol = inbound.stringOrNull("protocol").orEmpty()
            tag != INBOUND_WHITELIST &&
                !tag.startsWith("priority-probe") &&
                protocol in CLIENT_INBOUND_PROTOCOLS
        }
        val fullTags = inbounds.mapNotNull { element ->
            val inbound = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val tag = inbound.stringOrNull("tag") ?: return@mapNotNull null
            val protocol = inbound.stringOrNull("protocol").orEmpty()
            if (tag == INBOUND_WHITELIST || tag.startsWith("priority-probe")) return@mapNotNull null
            if (protocol in CLIENT_INBOUND_PROTOCOLS || protocol == "http") tag else null
        }.ifEmpty { listOf("socks", "mixed", "http") }

        if (primary != null && inbounds.none { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.stringOrNull("tag") == INBOUND_WHITELIST
            }
        ) {
            val clone = primary.deepCopy()
            val basePort = primary.get("port")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt
                ?: AppConfig.PORT_SOCKS.toInt()
            clone.addProperty("tag", INBOUND_WHITELIST)
            clone.addProperty("listen", AppConfig.LOOPBACK)
            clone.addProperty("port", whitelistSocksPort(basePort))
            inbounds.add(clone)
        }
        return fullTags to INBOUND_WHITELIST
    }

    private fun buildUserRules(
        profile: Profile,
        tags: OutboundTags,
        inboundTags: List<String>,
    ): List<JsonObject> {
        val rules = mutableListOf<JsonObject>()
        profile.routeOrder.forEach { step ->
            when (step) {
                "block" -> {
                    addDomainRule(rules, profile.blockSites, tags.block, inboundTags)
                    addIpRule(rules, profile.blockIp, tags.block, inboundTags)
                }
                "proxy" -> {
                    addDomainRule(rules, profile.proxySites, tags.proxy, inboundTags)
                    addIpRule(rules, profile.proxyIp, tags.proxy, inboundTags)
                }
                "direct" -> {
                    // Do not force GMS/mtalk off VPN; leave routing to profile lists only.
                    addDomainRule(
                        rules,
                        profile.directSites.filterNot { it.contains("mtalk", ignoreCase = true) },
                        tags.direct,
                        inboundTags,
                    )
                    addIpRule(rules, profile.directIp, tags.direct, inboundTags)
                }
            }
        }
        return rules
    }

    private fun addDomainRule(
        rules: MutableList<JsonObject>,
        domains: List<String>,
        outboundTag: String,
        inboundTags: List<String>,
    ) {
        if (domains.isEmpty()) return
        rules += JsonObject().apply {
            addProperty("type", "field")
            add("inboundTag", JsonArray().apply { inboundTags.forEach { add(it) } })
            add("domain", JsonArray().apply { domains.forEach { add(it) } })
            addProperty("outboundTag", outboundTag)
        }
    }

    private fun addIpRule(
        rules: MutableList<JsonObject>,
        ips: List<String>,
        outboundTag: String,
        inboundTags: List<String>,
    ) {
        if (ips.isEmpty()) return
        rules += JsonObject().apply {
            addProperty("type", "field")
            add("inboundTag", JsonArray().apply { inboundTags.forEach { add(it) } })
            add("ip", JsonArray().apply { ips.forEach { add(it) } })
            addProperty("outboundTag", outboundTag)
        }
    }

    private fun mergeDns(config: JsonObject, vararg profiles: Profile) {
        val dns = config.objectOrNull("dns") ?: JsonObject().also { config.add("dns", it) }
        val hosts = dns.objectOrNull("hosts") ?: JsonObject().also { dns.add("hosts", it) }
        profiles.forEach { profile ->
            profile.dnsHosts.forEach { (domain, ip) ->
                hosts.addProperty(domain, ip)
            }
        }
        val servers = dns.arrayOrNull("servers") ?: return
        val remoteByAddress = profiles.associate { it.remoteDns to it }
        val domesticByAddress = profiles.associate { it.domesticDns to it }
        val proxySites = profiles.flatMap { it.proxySites }.distinct()
        val directSites = profiles.flatMap { it.directSites }.distinct()
        servers.forEach { element ->
            val server = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val address = server.stringOrNull("address") ?: return@forEach
            when {
                address in remoteByAddress && proxySites.isNotEmpty() -> {
                    server.add("domains", JsonArray().apply { proxySites.forEach { add(it) } })
                }
                address in domesticByAddress && directSites.isNotEmpty() -> {
                    server.add("domains", JsonArray().apply { directSites.forEach { add(it) } })
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

    /**
     * Loopback + balancer for p5+. Pin the selector to the Smart Priority
     * active route so each TCP does not hop a random p5/p6/p15 server.
     */
    internal fun attachWhitelistProxyChain(
        config: JsonObject,
        plan: PriorityFailoverConfig.Plan,
        activeIndex: Int,
    ): String? {
        val whitelistRoutes = plan.routes.filter { modeForRouteTag(it) == Mode.WHITELIST }
        if (whitelistRoutes.isEmpty()) return null
        val outbounds = config.arrayOrNull("outbounds") ?: return null
        if (outbounds.none { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.stringOrNull("tag") ==
                    OUTBOUND_WHITELIST_PROXY
            }
        ) {
            outbounds.add(
                JsonObject().apply {
                    addProperty("protocol", "loopback")
                    addProperty("tag", OUTBOUND_WHITELIST_PROXY)
                    add(
                        "settings",
                        JsonObject().apply { addProperty("inboundTag", INBOUND_WHITELIST_PROXY) },
                    )
                },
            )
        }
        val routing = config.objectOrNull("routing") ?: return null
        val balancers = routing.arrayOrNull("balancers")
            ?: JsonArray().also { routing.add("balancers", it) }
        val activeTag = plan.routes.getOrNull(activeIndex)
        val pinned = activeTag?.takeIf { modeForRouteTag(it) == Mode.WHITELIST }
            ?: whitelistRoutes.first()
        val selector = JsonArray().apply { add(pinned) }
        val existing = balancers.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject
        }.firstOrNull { it.stringOrNull("tag") == BALANCER_WHITELIST }
        if (existing == null) {
            val strategyType = balancers.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject
                    ?.objectOrNull("strategy")
                    ?.stringOrNull("type")
            }.firstOrNull() ?: "leastLoad"
            balancers.add(
                JsonObject().apply {
                    addProperty("tag", BALANCER_WHITELIST)
                    add("selector", selector)
                    add("strategy", JsonObject().apply { addProperty("type", strategyType) })
                },
            )
        } else {
            existing.add("selector", selector)
        }
        runCatching {
            LogUtil.transport(
                "Whitelist proxy chain $OUTBOUND_WHITELIST_PROXY → $BALANCER_WHITELIST " +
                    "pinned=$pinned (not random among ${whitelistRoutes.size} p5+ routes)"
            )
        }
        return OUTBOUND_WHITELIST_PROXY
    }

    /**
     * Smart Priority already chose one live outbound. A `random` balancer with
     * every peer in the tier made Telegram/DNS hop servers every new TCP.
     */
    internal fun pinBalancersToActiveRoute(
        config: JsonObject,
        plan: PriorityFailoverConfig.Plan,
        activeIndex: Int,
    ) {
        val activeTag = plan.routes.getOrNull(activeIndex) ?: return
        val balancers = config.objectOrNull("routing")?.arrayOrNull("balancers") ?: return
        balancers.forEach { element ->
            val balancer = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val selector = balancer.arrayOrNull("selector") ?: return@forEach
            val tags = selector.mapNotNull {
                it.takeIf { item -> item.isJsonPrimitive && item.asJsonPrimitive.isString }?.asString
            }
            if (activeTag !in tags) return@forEach
            if (tags.size == 1 && tags[0] == activeTag) return@forEach
            balancer.add("selector", JsonArray().apply { add(activeTag) })
            runCatching {
                LogUtil.transport(
                    "Pinned balancer ${balancer.stringOrNull("tag")} → $activeTag " +
                        "(was ${tags.joinToString()})"
                )
            }
        }
    }

    private fun whitelistProxyInboundRule(): JsonObject = JsonObject().apply {
        addProperty("type", "field")
        add("inboundTag", JsonArray().apply { add(INBOUND_WHITELIST_PROXY) })
        addProperty("network", "tcp,udp")
        addProperty("balancerTag", BALANCER_WHITELIST)
    }

    private fun whitelistCatchAll(whiteInbound: String, proxyTag: String): JsonObject =
        JsonObject().apply {
            addProperty("type", "field")
            add("inboundTag", JsonArray().apply { add(whiteInbound) })
            addProperty("network", "tcp,udp")
            addProperty("outboundTag", proxyTag)
        }

    private fun metaQuicProxyRule(proxyTag: String, inboundTags: List<String>): JsonObject =
        JsonObject().apply {
            addProperty("type", "field")
            add("inboundTag", JsonArray().apply { inboundTags.forEach { add(it) } })
            addProperty("network", "udp")
            addProperty("port", "443")
            add("ip", JsonArray().apply { FACEBOOK_PROXY_IP.forEach { add(it) } })
            addProperty("outboundTag", proxyTag)
        }

    /**
     * HTTP/3 over VLESS+Reality+TCP stalls; reject QUIC fast so Chrome falls back to TCP.
     * [metaQuicProxyRule] above keeps Instagram/Meta QUIC on the proxy path.
     */
    private fun quicRejectRule(blockTag: String): JsonObject =
        JsonObject().apply {
            addProperty("type", "field")
            addProperty("network", "udp")
            addProperty("port", "443")
            addProperty("outboundTag", blockTag)
        }

    private fun videoBootstrapProxyRule(proxyTag: String, inboundTags: List<String>): JsonObject =
        JsonObject().apply {
            addProperty("type", "field")
            add("inboundTag", JsonArray().apply { inboundTags.forEach { add(it) } })
            add("domain", JsonArray().apply { VIDEO_BOOTSTRAP_DOMAINS.forEach { add(it) } })
            addProperty("outboundTag", proxyTag)
        }

    private fun isGlobalQuicRejectRule(rule: JsonObject, blockTag: String): Boolean {
        if (!isUdp443Rule(rule)) return false
        if (rule.has("domain") || rule.has("ip") || rule.has("inboundTag") ||
            rule.has("balancerTag") || rule.has("process")
        ) {
            return false
        }
        return rule.stringOrNull("outboundTag") == blockTag
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
