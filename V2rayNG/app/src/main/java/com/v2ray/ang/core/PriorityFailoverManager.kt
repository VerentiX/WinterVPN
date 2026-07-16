package com.v2ray.ang.core

import android.app.Service
import android.os.PowerManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Replaces eager Xray health checks with an application-managed priority failover.
 *
 * This is deliberately enabled only for the subscription layout made of priority
 * balancers connected through loopback fallback outbounds. Each priority tier may
 * contain one or more routes. Other custom configs are returned untouched.
 */
object PriorityFailoverManager {
    /** User-tunable active-route check interval while the display is on. */
    internal const val SCREEN_ON_ROUTE_PROBE_INTERVAL_MS = 5_000L

    /** User-tunable active-route check interval while the display is off. */
    internal const val SCREEN_OFF_ROUTE_PROBE_INTERVAL_MS = 60_000L
    private const val FAILURES_BEFORE_FAILOVER = 1
    /** First check for a recovered, higher-priority route after failover. */
    internal const val HIGHER_PRIORITY_PROBE_INITIAL_INTERVAL_MS = 5 * 60_000L

    /** Maximum backoff between checks for a recovered higher-priority route. */
    internal const val HIGHER_PRIORITY_PROBE_MAX_INTERVAL_MS = 10 * 60_000L
    private const val ROUTE_PROBE_TIMEOUT_MS = 4_000L
    private const val ROUTE_PROBE_ATTEMPTS = 1

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var state: State? = null
    private var pendingPreviousIndex: Int? = null
    private var nextRecoveryCheckAt = Long.MAX_VALUE
    private var recoveryIntervalMs = HIGHER_PRIORITY_PROBE_INITIAL_INTERVAL_MS
    @Volatile
    private var isScreenInteractive = true

    private data class State(
        val guid: String,
        val sourceConfig: JsonObject,
        val plan: PriorityFailoverConfig.Plan,
        val probePorts: Map<String, Int>,
        var activeIndex: Int,
        var needsInitialSelection: Boolean,
    )

    /** Prepare a runtime-only config. The profile stored in MMKV is never modified. */
    fun prepareRuntimeConfig(guid: String, content: String): String {
        val source = runCatching { JsonParser.parseString(content).asJsonObject }.getOrNull()
            ?: return content
        val plan = PriorityFailoverConfig.detect(source) ?: run {
            stop(clearState = true)
            return content
        }

        val snapshot = synchronized(lock) {
            val previous = state
            val sameProfile = previous?.guid == guid && previous.plan.routes == plan.routes
            val activeIndex = if (sameProfile) {
                previous.activeIndex.coerceIn(plan.routes.indices)
            } else {
                pendingPreviousIndex = null
                scheduleRecoveryLocked(plan, activeIndex = 0)
                0
            }
            val probePorts = if (sameProfile) {
                previous.probePorts
            } else {
                allocateProbePorts(source, plan.routes)
            }
            State(
                guid = guid,
                sourceConfig = source.deepCopy(),
                plan = plan,
                probePorts = probePorts,
                activeIndex = activeIndex,
                needsInitialSelection = if (sameProfile) previous.needsInitialSelection else true,
            ).also { state = it }
        }

        val runtime = PriorityFailoverConfig.activate(
            snapshot.sourceConfig,
            snapshot.plan,
            snapshot.activeIndex,
            snapshot.probePorts,
        )
        return JsonUtil.toJsonPretty(runtime) ?: content
    }

    fun onCoreStarted(service: Service) {
        synchronized(lock) { pendingPreviousIndex = null }
        isScreenInteractive = service.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val initial = synchronized(lock) { state } ?: return

        LogUtil.transport(
            "Smart priority failover active: ${initial.plan.routes.size} routes, " +
                "current=${initial.plan.routes[initial.activeIndex]}, " +
                "screen=${if (isScreenInteractive) "on" else "off"}, " +
                "probe=${initial.plan.probeUrl ?: SettingsManager.getDelayTestUrl()}"
        )
        startMonitor()
    }

    /** Apply a new probe cadence immediately when the display state changes. */
    fun onScreenStateChanged(interactive: Boolean) {
        if (isScreenInteractive == interactive) return
        isScreenInteractive = interactive
        LogUtil.transport(
            "Smart priority screen=${if (interactive) "on" else "off"}; " +
                "probeInterval=${activeProbeIntervalMs()}ms"
        )
        startMonitor()
    }

    private fun activeProbeIntervalMs(): Long = if (isScreenInteractive) {
        SCREEN_ON_ROUTE_PROBE_INTERVAL_MS
    } else {
        SCREEN_OFF_ROUTE_PROBE_INTERVAL_MS
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        if (synchronized(lock) { state } == null) return
        monitorJob = scope.launch {
            var consecutiveFailures = 0
            val initialState = synchronized(lock) { state } ?: return@launch
            val chooseInitialRoute = synchronized(lock) {
                initialState.needsInitialSelection
            }
            if (chooseInitialRoute) {
                val previousInitialIndex = initialState.activeIndex
                val initialRoute = probeRoutesInParallel(
                    initialState,
                    initialState.plan.routes.indices.toList(),
                )
                synchronized(lock) {
                    if (state === initialState) initialState.needsInitialSelection = false
                }
                if (initialRoute != null && initialRoute != previousInitialIndex &&
                    switchRoute(initialState, previousInitialIndex, initialRoute)
                ) {
                    LogUtil.transport(
                        "Smart priority initial selection: " +
                            "${initialState.plan.routes[previousInitialIndex]} -> " +
                            initialState.plan.routes[initialRoute]
                    )
                    CoreServiceManager.reloadPriorityRoute()
                    return@launch
                }
            }
            while (true) {
                delay(activeProbeIntervalMs())
                val current = synchronized(lock) { state } ?: return@launch
                val activeIndex = current.activeIndex
                if (isRecoveryDue(current, activeIndex)) {
                    val betterRoute = findHigherPriorityRoute(current, activeIndex)
                    if (betterRoute != null && switchRoute(current, activeIndex, betterRoute)) {
                        LogUtil.transport(
                            "Smart priority recovery: ${current.plan.routes[activeIndex]} -> " +
                                current.plan.routes[betterRoute]
                        )
                        CoreServiceManager.reloadPriorityRoute()
                        return@launch
                    }
                    postponeRecoveryCheck()
                }
                val activeTag = current.plan.routes[activeIndex]
                val routeWorks = probeRoute(current, activeTag)

                if (routeWorks != null) {
                    consecutiveFailures = 0
                    continue
                }
                consecutiveFailures++
                LogUtil.transport(
                    "Smart priority probe failed for $activeTag " +
                        "($consecutiveFailures/$FAILURES_BEFORE_FAILOVER)"
                )
                if (consecutiveFailures < FAILURES_BEFORE_FAILOVER) continue

                val replacement = findNextWorkingRoute(current, activeIndex)
                if (replacement == null) {
                    LogUtil.transport("Smart priority failover: no working fallback route")
                    consecutiveFailures = 0
                    continue
                }

                val switched = switchRoute(current, activeIndex, replacement)
                if (switched) {
                    LogUtil.transport(
                        "Smart priority failover: $activeTag -> ${current.plan.routes[replacement]}"
                    )
                    CoreServiceManager.reloadPriorityRoute()
                    return@launch
                }
            }
        }
    }

    private suspend fun findNextWorkingRoute(current: State, activeIndex: Int): Int? {
        val candidates = current.plan.routes.indices.filter { it != activeIndex }
        return probeRoutesInParallel(current, candidates)
    }

    private suspend fun findHigherPriorityRoute(current: State, activeIndex: Int): Int? =
        probeRoutesInParallel(
            current,
            current.plan.routes.indices.filter {
                current.plan.tierIndexForRoute(it) < current.plan.tierIndexForRoute(activeIndex)
            },
        )

    /** Probe together; prefer the best tier, then the lowest measured latency. */
    private suspend fun probeRoutesInParallel(current: State, candidates: List<Int>): Int? =
        coroutineScope {
            val latencyByRoute = candidates.map { candidateIndex ->
                async(Dispatchers.IO) {
                    candidateIndex to probeRoute(current, current.plan.routes[candidateIndex])
                }
            }.awaitAll().mapNotNull { (index, latencyMs) ->
                latencyMs?.let { index to it }
            }.toMap()
            PriorityFailoverConfig.chooseBestRoute(current.plan, latencyByRoute)
        }

    /** Probe through a route-specific HTTP inbound owned by the running core. */
    private fun probeRoute(current: State, routeTag: String): Long? {
        val port = current.probePorts[routeTag] ?: return null
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, port)))
            .connectTimeout(ROUTE_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(ROUTE_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(ROUTE_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = Request.Builder()
            .url(current.plan.probeUrl ?: SettingsManager.getDelayTestUrl())
            .header("Connection", "close")
            .get()
            .build()

        repeat(ROUTE_PROBE_ATTEMPTS) {
            val startedAt = System.nanoTime()
            val succeeded = runCatching {
                client.newCall(request).execute().use { response -> response.isSuccessful }
            }.getOrDefault(false)
            if (succeeded) return (System.nanoTime() - startedAt) / 1_000_000L
        }
        return null
    }

    private fun allocateProbePorts(source: JsonObject, routes: List<String>): Map<String, Int> {
        val used = source.get("inbounds")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { inbound ->
                inbound.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("port")?.takeIf { it.isJsonPrimitive }?.asInt
            }
            ?.toMutableSet()
            ?: mutableSetOf()
        return routes.associateWith {
            var port: Int
            do {
                port = Utils.findRandomFreePort()
            } while (!used.add(port))
            port
        }
    }

    private fun switchRoute(current: State, activeIndex: Int, replacement: Int): Boolean =
        synchronized(lock) {
            val latest = state
            if (latest == null || latest.guid != current.guid || latest.activeIndex != activeIndex) {
                false
            } else {
                pendingPreviousIndex = activeIndex
                latest.activeIndex = replacement
                scheduleRecoveryLocked(latest.plan, replacement)
                true
            }
        }

    private fun isRecoveryDue(current: State, activeIndex: Int): Boolean = synchronized(lock) {
        current.plan.tierIndexForRoute(activeIndex) > 0 &&
            System.currentTimeMillis() >= nextRecoveryCheckAt
    }

    private fun postponeRecoveryCheck() {
        synchronized(lock) {
            recoveryIntervalMs = (recoveryIntervalMs * 2)
                .coerceAtMost(HIGHER_PRIORITY_PROBE_MAX_INTERVAL_MS)
            nextRecoveryCheckAt = System.currentTimeMillis() + recoveryIntervalMs
        }
    }

    private fun scheduleRecoveryLocked(plan: PriorityFailoverConfig.Plan, activeIndex: Int) {
        recoveryIntervalMs = HIGHER_PRIORITY_PROBE_INITIAL_INTERVAL_MS
        nextRecoveryCheckAt = if (plan.tierIndexForRoute(activeIndex) > 0) {
            System.currentTimeMillis() + recoveryIntervalMs
        } else {
            Long.MAX_VALUE
        }
    }

    /** Restore the route used by the still-running config if a soft reload failed. */
    fun rollbackPendingSwitch() {
        synchronized(lock) {
            val previous = pendingPreviousIndex ?: return
            state?.let { current ->
                current.activeIndex = previous
                scheduleRecoveryLocked(current.plan, previous)
            }
            pendingPreviousIndex = null
        }
    }

    fun stop(clearState: Boolean) {
        monitorJob?.cancel()
        monitorJob = null
        if (clearState) {
            synchronized(lock) {
                state = null
                pendingPreviousIndex = null
                nextRecoveryCheckAt = Long.MAX_VALUE
                recoveryIntervalMs = HIGHER_PRIORITY_PROBE_INITIAL_INTERVAL_MS
            }
        }
    }
}

/** Pure JSON recognition/transformation helpers, kept separate for unit testing. */
internal object PriorityFailoverConfig {
    data class Tier(val priority: Int, val routes: List<String>)

    data class Plan(
        val rootBalancerTag: String,
        val balancerTags: List<String>,
        val tiers: List<Tier>,
        val probeUrl: String?,
    ) {
        val routes: List<String> = tiers.flatMap { it.routes }

        fun tierIndexForRoute(routeIndex: Int): Int {
            val route = routes[routeIndex]
            return tiers.indexOfFirst { route in it.routes }
        }
    }

    /** Priority wins between tiers; measured latency wins only inside that tier. */
    fun chooseBestRoute(plan: Plan, latencyByRoute: Map<Int, Long>): Int? {
        val bestTier = latencyByRoute.keys.minOfOrNull(plan::tierIndexForRoute) ?: return null
        return latencyByRoute
            .asSequence()
            .filter { (routeIndex, _) -> plan.tierIndexForRoute(routeIndex) == bestTier }
            .minWithOrNull(compareBy<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
            ?.key
    }

    fun detect(source: JsonObject): Plan? {
        if (!source.has("burstObservatory")) return null
        val probeUrl = source.objectOrNull("burstObservatory")
            ?.objectOrNull("pingConfig")
            ?.stringOrNull("destination")
            ?.let(::validProbeUrl)
        val routing = source.objectOrNull("routing") ?: return null
        val rules = routing.arrayOrNull("rules") ?: return null
        val balancers = routing.arrayOrNull("balancers") ?: return null
        val outbounds = source.arrayOrNull("outbounds") ?: return null

        val rootBalancerTag = rules.asSequence()
            .mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
            .firstOrNull { it.stringArray("inboundTag").contains("auto-proxy-in") }
            ?.stringOrNull("balancerTag")
            ?: return null
        val balancerByTag = balancers.asSequence()
            .mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
            .mapNotNull { balancer -> balancer.stringOrNull("tag")?.let { it to balancer } }
            .toMap()
        val outboundByTag = outbounds.asSequence()
            .mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
            .mapNotNull { outbound -> outbound.stringOrNull("tag")?.let { it to outbound } }
            .toMap()

        val routes = mutableListOf<String>()
        val visitedBalancers = mutableSetOf<String>()
        val balancerTags = mutableListOf<String>()
        var balancerTag: String? = rootBalancerTag
        while (balancerTag != null && visitedBalancers.add(balancerTag)) {
            val balancer = balancerByTag[balancerTag] ?: return null
            balancerTags += balancerTag
            val selector = balancer.stringArray("selector")
            if (selector.isEmpty()) return null
            if (selector.distinct().size != selector.size) return null
            if (selector.any { route ->
                    routePriority(route) == null || route !in outboundByTag || route in routes
                }
            ) return null
            routes += selector

            val fallback = balancer.stringOrNull("fallbackTag") ?: break
            if (fallback in routes || fallback.startsWith("route-p")) break
            val loopback = outboundByTag[fallback] ?: return null
            if (loopback.stringOrNull("protocol") != "loopback") return null
            val inboundTag = loopback.objectOrNull("settings")?.stringOrNull("inboundTag") ?: return null
            balancerTag = rules.asSequence()
                .mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
                .firstOrNull { inboundTag in it.stringArray("inboundTag") }
                ?.stringOrNull("balancerTag")
        }

        // Do not disable observability if the config contains another, unrelated
        // balancer. Such a balancer could still legitimately require it.
        if (routes.size < 2 || visitedBalancers.size != balancerByTag.size) return null
        val tiers = routes
            .groupBy { routePriority(it) ?: return null }
            .toSortedMap()
            .map { (priority, priorityRoutes) -> Tier(priority, priorityRoutes) }
        return Plan(rootBalancerTag, balancerTags, tiers, probeUrl)
    }

    private fun routePriority(routeTag: String): Int? =
        Regex("""^route-p(\d+)(?:-|$)""")
            .find(routeTag)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun validProbeUrl(value: String): String? = runCatching {
        URI(value.trim())
    }.getOrNull()?.takeIf { uri ->
        uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)
    }?.takeIf { uri ->
        !uri.host.isNullOrBlank()
    }?.toString()

    fun activate(
        source: JsonObject,
        plan: Plan,
        activeIndex: Int,
        probePorts: Map<String, Int> = emptyMap(),
    ): JsonObject {
        val result = source.deepCopy()
        val routing = result.objectOrNull("routing") ?: return result
        val balancers = routing.arrayOrNull("balancers") ?: return result
        balancers.asSequence()
            .mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
            .filter { it.stringOrNull("tag") in plan.balancerTags }
            .forEach { balancer ->
                if (balancer.stringOrNull("tag") == plan.rootBalancerTag) {
                    balancer.add("selector", JsonArray().apply { add(plan.routes[activeIndex]) })
                }
                balancer.remove("fallbackTag")
                balancer.add("strategy", JsonObject().apply { addProperty("type", "random") })
            }
        result.remove("burstObservatory")
        result.remove("observatory")
        addRouteProbeInbounds(result, plan, probePorts)
        return result
    }

    private fun addRouteProbeInbounds(
        result: JsonObject,
        plan: Plan,
        probePorts: Map<String, Int>,
    ) {
        if (probePorts.isEmpty()) return
        val inbounds = result.arrayOrNull("inbounds") ?: JsonArray().also { result.add("inbounds", it) }
        val routing = result.objectOrNull("routing") ?: return
        val originalRules = routing.arrayOrNull("rules") ?: JsonArray()
        val rules = JsonArray()

        plan.routes.forEachIndexed { index, routeTag ->
            val port = probePorts[routeTag] ?: return@forEachIndexed
            val inboundTag = "priority-probe-in-$index"
            inbounds.add(JsonObject().apply {
                addProperty("listen", "127.0.0.1")
                addProperty("port", port)
                addProperty("protocol", "http")
                addProperty("tag", inboundTag)
                add("settings", JsonObject().apply { addProperty("userLevel", 0) })
            })
            rules.add(JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().apply { add(inboundTag) })
                addProperty("network", "tcp")
                addProperty("outboundTag", routeTag)
            })
        }
        originalRules.forEach { rules.add(it.deepCopy()) }
        routing.add("rules", rules)
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arrayOrNull(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.stringArray(name: String): List<String> =
        arrayOrNull(name)?.mapNotNull {
            it.takeIf { element -> element.isJsonPrimitive && element.asJsonPrimitive.isString }?.asString
        }.orEmpty()
}
