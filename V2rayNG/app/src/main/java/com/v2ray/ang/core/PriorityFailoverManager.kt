package com.v2ray.ang.core

import android.app.Service
import android.os.PowerManager
import android.os.SystemClock
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Replaces eager Xray health checks with an application-managed priority failover.
 *
 * This is deliberately enabled only for the subscription layout made of priority
 * balancers connected through loopback fallback outbounds. Each priority tier may
 * contain one or more routes. Other custom configs are returned untouched.
 */
object PriorityFailoverManager {
    /** User-tunable active-route check interval while the display is on. */
    internal const val SCREEN_ON_ROUTE_PROBE_INTERVAL_MS = 15_000L

    /** User-tunable active-route check interval while the display is off. */
    internal const val SCREEN_OFF_ROUTE_PROBE_INTERVAL_MS = 5 * 60_000L
    /** Ignore isolated probe glitches; quiet path confirms before parallel reselect. */
    private const val FAILURES_BEFORE_FAILOVER = 3
    /**
     * After this many consecutive active-route failures, probe **all** tiers in
     * parallel (same as transport reselection) instead of waiting for a third
     * confirm — cuts whitelist-zone entry from ~35s toward ~15s.
     */
    private const val FAILURES_BEFORE_PARALLEL_RESELECT = 2
    /** Confirmation probes are deliberately quicker than the normal user cadence. */
    private const val FAILURE_CONFIRMATION_BASE_DELAY_MS = 1_000L
    private const val ALL_ROUTES_DOWN_RETRY_INITIAL_MS = 5_000L
    private const val ALL_ROUTES_DOWN_RETRY_MAX_SCREEN_ON_MS = 60_000L
    /** First check for a recovered, higher-priority route after failover. */
    internal const val HIGHER_PRIORITY_PROBE_INITIAL_INTERVAL_MS = 5 * 60_000L

    /** Maximum backoff between checks for a recovered higher-priority route. */
    internal const val HIGHER_PRIORITY_PROBE_MAX_INTERVAL_MS = 10 * 60_000L
    private const val ROUTE_PROBE_TIMEOUT_MS = 4_000L
    private const val ROUTE_PROBE_ATTEMPTS = 1
    /** Soft reload after a route switch must finish (or fail) within this window. */
    private const val RELOAD_WAIT_TIMEOUT_MS = 12_000L
    private const val PROBE_WAKE_LOCK_TIMEOUT_MS = 45_000L
    /**
     * How often a core traffic-error signal may interrupt the idle wait.
     * Keeps background wakeups rare even if Xray emits a burst of dial failures.
     */
    private const val TRAFFIC_ERROR_PROBE_COOLDOWN_MS = 12_000L
    /**
     * Traffic-error corroboration window: a probe fail inside this window after
     * a real TUN/dial error is treated as a hard outage (parallel all-tier now).
     */
    private const val TRAFFIC_ERROR_CORROBORATION_WINDOW_MS = 45_000L
    /** Slice size while waiting for the next scheduled probe (allows early wake). */
    private const val PROBE_WAIT_SLICE_MS = 250L

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var state: State? = null
    private var pendingPreviousIndex: Int? = null
    private var nextRecoveryCheckAt = Long.MAX_VALUE
    private var recoveryIntervalMs = HIGHER_PRIORITY_PROBE_INITIAL_INTERVAL_MS
    @Volatile
    private var isScreenInteractive = true
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null
    private val reloadGeneration = AtomicInteger(0)
    private val probeClients = ConcurrentHashMap<Int, OkHttpClient>()
    @Volatile
    private var completedReloadGeneration = 0
    @Volatile
    private var lastReloadSucceeded = false
    private val probeNowRequested = AtomicBoolean(false)
    private val lastTrafficErrorProbeAt = AtomicLong(0L)
    /** ElapsedRealtime of last TUN/dial error — used to accelerate failover. */
    private val lastTrafficErrorAt = AtomicLong(0L)
    /**
     * After Wi-Fi↔LTE, probe every route in parallel (same as cold start) instead
     * of confirming the old active P0 three times on a whitelist cell.
     */
    @Volatile
    private var pendingFullReselection = false
    /** FULL→WHITELIST reload was rejected while STARTING/STOPPING; apply at RUNNING. */
    private val pendingDataplaneReload = AtomicBoolean(false)

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

    /** Active Smart Priority route tag, if failover state is already prepared. */
    fun currentRouteTag(): String? = synchronized(lock) {
        val current = state ?: return null
        current.plan.routes.getOrNull(current.activeIndex)
    }

    fun onCoreStarted(service: Service) {
        synchronized(lock) { pendingPreviousIndex = null }
        lastReloadSucceeded = true
        completedReloadGeneration = reloadGeneration.get()
        isScreenInteractive = service.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        ensureWakeLock(service)
        val initial = synchronized(lock) { state } ?: return

        LogUtil.transport(
            "Smart priority failover active: ${initial.plan.routes.size} routes, " +
                "current=${initial.plan.routes[initial.activeIndex]}, " +
                "screen=${if (isScreenInteractive) "on" else "off"}, " +
                "probe=${initial.plan.probeUrl ?: SettingsManager.getDelayTestUrl()}"
        )
        CoreServiceManager.setActiveOutboundTag(initial.plan.routes[initial.activeIndex])
        CoreServiceManager.setActiveRoutingMode(
            RoscomPriorityRouting.profileModeForActiveRoute(initial.plan, initial.activeIndex),
        )
        if (!CoreServiceManager.isDataplaneReady()) {
            LogUtil.transport("Smart priority: defer probes until TUN/hev RUNNING")
            return
        }
        startMonitor(probeImmediately = true)
    }

    /**
     * VPN reached RUNNING. Probe now, and apply a WHITELIST SOCKS+pin that was
     * rejected while the service was still STARTING or STOPPING.
     */
    fun onDataplaneReady() {
        if (synchronized(lock) { state } == null) return
        if (pendingDataplaneReload.compareAndSet(true, false)) {
            LogUtil.transport("Applying queued SOCKS+pin now that dataplane is RUNNING")
            scope.launch {
                val requested = CoreServiceManager.reloadPriorityRoute()
                if (!requested) {
                    LogUtil.transport("Queued SOCKS+pin reload still rejected; will retry on next probe")
                    pendingDataplaneReload.set(true)
                    startMonitor(probeImmediately = true)
                }
            }
            return
        }
        startMonitor(probeImmediately = true)
    }

    fun markPendingDataplaneReload() {
        pendingDataplaneReload.set(true)
    }

    /** Apply a new probe cadence immediately when the display state changes. */
    fun onScreenStateChanged(interactive: Boolean) {
        if (isScreenInteractive == interactive) {
            if (interactive) ensureMonitorRunning(probeImmediately = true)
            return
        }
        isScreenInteractive = interactive
        LogUtil.transport(
            "Smart priority screen=${if (interactive) "on" else "off"}; " +
                "probeInterval=${activeProbeIntervalMs()}ms"
        )
        startMonitor(probeImmediately = interactive)
    }

    /** Doze maintenance window ended — run a probe without waiting for the next interval. */
    fun onDeviceExitedIdle() {
        if (synchronized(lock) { state } == null) return
        LogUtil.transport("Smart priority: device left idle; probing now")
        startMonitor(probeImmediately = true)
    }

    /** Restart the monitor if a failed reload left it stopped. */
    fun ensureMonitorRunning(probeImmediately: Boolean = false) {
        if (synchronized(lock) { state } == null) return
        val job = monitorJob
        if (job == null || !job.isActive) {
            LogUtil.transport("Smart priority: restarting idle monitor")
            startMonitor(probeImmediately = probeImmediately)
        }
    }

    /**
     * Sticky flag only — next [startMonitor]/[onCoreStarted] runs parallel
     * all-tier probe. Used when core soft reload is already in flight so we do
     * not cancel [awaitPriorityReload] or probe a half-reloaded core.
     */
    fun markFullReselectionPending() {
        synchronized(lock) {
            if (state == null) return
            pendingFullReselection = true
            nextRecoveryCheckAt = 0L
            recoveryIntervalMs = HIGHER_PRIORITY_PROBE_INITIAL_INTERVAL_MS
        }
        LogUtil.transport("Smart priority: full-tier reselection marked pending")
    }

    /**
     * Wi-Fi↔LTE already rebuilt TUN. This only starts an all-tier probe so
     * p0↔p5 follows the live zone, not the radio type.
     */
    internal fun requestImmediatePriorityReselection(
        preferMode: RoscomPriorityRouting.Mode? = null,
    ) {
        if (synchronized(lock) { state } == null) return
        markFullReselectionPending()
        LogUtil.transport(
            "Smart priority: probe live routes after transport change" +
                (preferMode?.let { " (prefer=$it ignored; zone follows probes)" } ?: "")
        )
        ensureMonitorRunning(probeImmediately = true)
    }

    /**
     * Real TUN / outbound failure observed while the VPN is up. Skip the idle
     * probe wait and confirm the active route soon. Debounced wakeups so a burst
     * of dial errors does not thrash the radio — but corroboration stays sticky
     * so the next failed probe can jump straight to parallel all-tier failover.
     */
    fun onTrafficError(detail: String = "") {
        if (synchronized(lock) { state } == null) return
        val now = SystemClock.elapsedRealtime()
        lastTrafficErrorAt.set(now)

        val previous = lastTrafficErrorProbeAt.get()
        val withinCooldown = now - previous < TRAFFIC_ERROR_PROBE_COOLDOWN_MS
        if (withinCooldown) {
            // Keep corroboration for the monitor; do not wake again yet.
            return
        }
        if (!lastTrafficErrorProbeAt.compareAndSet(previous, now)) return

        val short = detail.trim().take(160)
        LogUtil.transport(
            if (short.isEmpty()) {
                "Smart priority: traffic error → probe now"
            } else {
                "Smart priority: traffic error → probe now ($short)"
            }
        )
        probeNowRequested.set(true)
        ensureMonitorRunning(probeImmediately = false)
    }

    private fun hasRecentTrafficErrorCorroboration(): Boolean {
        val at = lastTrafficErrorAt.get()
        if (at == 0L) return false
        return SystemClock.elapsedRealtime() - at <= TRAFFIC_ERROR_CORROBORATION_WINDOW_MS
    }

    private fun clearTrafficErrorCorroboration() {
        lastTrafficErrorAt.set(0L)
    }

    /** Unblock [awaitPriorityReload] when soft reload aborts before [onCoreStarted]. */
    fun notifyReloadAborted() {
        lastReloadSucceeded = false
        completedReloadGeneration = reloadGeneration.get()
        ensureMonitorRunning(probeImmediately = true)
    }

    private fun activeProbeIntervalMs(): Long =
        SettingsManager.getPriorityProbeIntervalMs(screenOn = isScreenInteractive)

    /** Sleep until the next cadence tick, or until [onTrafficError] asks for an early probe. */
    private suspend fun awaitNextProbeWindow() {
        val deadline = SystemClock.elapsedRealtime() + activeProbeIntervalMs()
        while (true) {
            if (probeNowRequested.get()) return
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return
            delay(minOf(PROBE_WAIT_SLICE_MS, remaining))
        }
    }

    /** Apply user-edited cadence without waiting for the previous delay to expire. */
    fun onProbeIntervalsChanged() {
        if (synchronized(lock) { state } == null) return
        LogUtil.transport("Smart priority probe intervals changed; rescheduling monitor")
        startMonitor(probeImmediately = false)
    }

    private fun ensureWakeLock(service: Service) {
        if (wakeLock != null) return
        val pm = service.getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "v2rayNG:PriorityFailover").apply {
            setReferenceCounted(false)
        }
    }

    private fun startMonitor(probeImmediately: Boolean = false) {
        monitorJob?.cancel()
        if (synchronized(lock) { state } == null) return
        monitorJob = scope.launch {
            var consecutiveFailures = 0
            var allRoutesDownRetryMs = ALL_ROUTES_DOWN_RETRY_INITIAL_MS
            var skipDelay = probeImmediately
            // Drain startup/transport full-tier probes. Do not clear
            // pendingFullReselection when running initial selection — Wi-Fi→LTE can
            // mark it during the initial parallel probe; we must still reselect after.
            while (true) {
                val current = synchronized(lock) { state } ?: return@launch
                val fullReselectReason = synchronized(lock) {
                    when {
                        current.needsInitialSelection -> {
                            current.needsInitialSelection = false
                            "initial selection"
                        }
                        pendingFullReselection -> {
                            pendingFullReselection = false
                            "transport reselection"
                        }
                        else -> null
                    }
                }
                if (fullReselectReason == null) break

                val previousIndex = current.activeIndex
                LogUtil.transport(
                    "Smart priority: probing all ${current.plan.routes.size} routes " +
                        "in parallel ($fullReselectReason)"
                )
                val latencyByRoute = withProbeWakeLock {
                    probeRouteLatencies(
                        current,
                        current.plan.routes.indices.toList(),
                    )
                }
                consecutiveFailures = 0
                val bestRoute = chooseTransportOrBestRoute(
                    current,
                    previousIndex,
                    latencyByRoute,
                    preferKeepCurrent = fullReselectReason == "transport reselection",
                )
                if (bestRoute != null && bestRoute != previousIndex) {
                    when (
                        commitRouteSwitch(
                            current,
                            previousIndex,
                            bestRoute,
                            reason = fullReselectReason,
                        )
                    ) {
                        null -> Unit
                        true -> skipDelay = true
                        false -> return@launch
                    }
                } else if (bestRoute == null) {
                    LogUtil.transport(
                        "Smart priority: no live routes during $fullReselectReason; " +
                            "keeping ${current.plan.routes[previousIndex]}"
                    )
                } else if (
                    fullReselectReason == "transport reselection" &&
                    previousIndex in latencyByRoute
                ) {
                    LogUtil.transport(
                        "Smart priority: transport reselection keeps " +
                            "${current.plan.routes[previousIndex]} (still live; skip soft reload)"
                    )
                } else {
                    LogUtil.transport(
                        "Smart priority: $fullReselectReason keeps " +
                            current.plan.routes[previousIndex]
                    )
                }
            }
            while (true) {
                if (!skipDelay) {
                    awaitNextProbeWindow()
                }
                skipDelay = false
                probeNowRequested.set(false)
                val current = synchronized(lock) { state } ?: return@launch

                // Transport handover while the steady-state loop was sleeping —
                // same keep-if-live policy as the startup drain above.
                val transportPending = synchronized(lock) {
                    if (pendingFullReselection) {
                        pendingFullReselection = false
                        true
                    } else {
                        false
                    }
                }
                if (transportPending) {
                    val previousIndex = current.activeIndex
                    LogUtil.transport(
                        "Smart priority: probing all ${current.plan.routes.size} routes " +
                            "in parallel (transport reselection)"
                    )
                    val latencyByRoute = withProbeWakeLock {
                        probeRouteLatencies(
                            current,
                            current.plan.routes.indices.toList(),
                        )
                    }
                    consecutiveFailures = 0
                    val bestRoute = chooseTransportOrBestRoute(
                        current,
                        previousIndex,
                        latencyByRoute,
                        preferKeepCurrent = true,
                    )
                    if (bestRoute != null && bestRoute != previousIndex) {
                        when (
                            commitRouteSwitch(
                                current,
                                previousIndex,
                                bestRoute,
                                reason = "transport reselection",
                            )
                        ) {
                            null -> Unit
                            true -> {
                                skipDelay = true
                                continue
                            }
                            false -> return@launch
                        }
                    } else {
                        LogUtil.transport(
                            "Smart priority: transport reselection keeps " +
                                current.plan.routes[previousIndex]
                        )
                    }
                    skipDelay = true
                    continue
                }

                val activeIndex = current.activeIndex
                if (isRecoveryDue(current, activeIndex)) {
                    val betterRoute = withProbeWakeLock {
                        findHigherPriorityRoute(current, activeIndex)
                    }
                    if (betterRoute != null) {
                        when (
                            commitRouteSwitch(
                                current,
                                activeIndex,
                                betterRoute,
                                reason = "recovery",
                            )
                        ) {
                            null -> postponeRecoveryCheck()
                            true -> {
                                consecutiveFailures = 0
                                skipDelay = true
                                continue
                            }
                            false -> return@launch
                        }
                    } else {
                        postponeRecoveryCheck()
                    }
                }
                val activeTag = current.plan.routes[activeIndex]
                val routeWorks = withProbeWakeLock { probeRoute(current, activeTag) }

                if (routeWorks != null) {
                    consecutiveFailures = 0
                    allRoutesDownRetryMs = ALL_ROUTES_DOWN_RETRY_INITIAL_MS
                    clearTrafficErrorCorroboration()
                    continue
                }
                consecutiveFailures++
                val trafficCorroborated = hasRecentTrafficErrorCorroboration()
                LogUtil.transport(
                    "Smart priority probe failed for $activeTag " +
                        "($consecutiveFailures/$FAILURES_BEFORE_FAILOVER" +
                        (if (trafficCorroborated) ", traffic-error corroborated" else "") +
                        ")"
                )
                // Fast path: 2 quiet fails, or 1 fail + recent TUN/dial errors →
                // parallel all-tier (same as Wi-Fi↔LTE), not a third P0 hammer.
                val parallelReselect =
                    consecutiveFailures >= FAILURES_BEFORE_PARALLEL_RESELECT ||
                        (consecutiveFailures >= 1 && trafficCorroborated)
                if (!parallelReselect) {
                    delay(FAILURE_CONFIRMATION_BASE_DELAY_MS * consecutiveFailures)
                    skipDelay = true
                    continue
                }

                val reason = if (trafficCorroborated) {
                    "traffic-error failover"
                } else {
                    "fast failover"
                }
                LogUtil.transport(
                    "Smart priority: probing all ${current.plan.routes.size} routes " +
                        "in parallel ($reason)"
                )
                val latencyByRoute = withProbeWakeLock {
                    probeRouteLatencies(
                        current,
                        current.plan.routes.indices.toList(),
                    )
                }
                val bestRoute = chooseTransportOrBestRoute(
                    current,
                    activeIndex,
                    latencyByRoute,
                    preferKeepCurrent = false,
                    preferSameMode = true,
                )
                clearTrafficErrorCorroboration()
                if (bestRoute == null) {
                    LogUtil.transport(
                        "Smart priority failover: no working route; retry in " +
                            "${allRoutesDownRetryMs}ms"
                    )
                    consecutiveFailures = 0
                    delay(allRoutesDownRetryMs)
                    allRoutesDownRetryMs = (allRoutesDownRetryMs * 2).coerceAtMost(
                        if (isScreenInteractive) ALL_ROUTES_DOWN_RETRY_MAX_SCREEN_ON_MS
                        else SCREEN_OFF_ROUTE_PROBE_INTERVAL_MS
                    )
                    skipDelay = true
                    continue
                }
                if (bestRoute == activeIndex) {
                    LogUtil.transport(
                        "Smart priority: $reason keeps ${current.plan.routes[activeIndex]}"
                    )
                    consecutiveFailures = 0
                    allRoutesDownRetryMs = ALL_ROUTES_DOWN_RETRY_INITIAL_MS
                    continue
                }

                when (
                    commitRouteSwitch(
                        current,
                        activeIndex,
                        bestRoute,
                        reason = reason,
                    )
                ) {
                    null -> Unit
                    true -> {
                        consecutiveFailures = 0
                        allRoutesDownRetryMs = ALL_ROUTES_DOWN_RETRY_INITIAL_MS
                        skipDelay = true
                    }
                    false -> return@launch
                }
            }
        }
    }

    /**
     * Apply a Smart Priority route change.
     *
     * p0–p4 vs p5+ is the only rule-table switch: hev is moved to the matching
     * SOCKS inbound. Same-tier picks skip that bounce. Xray is not reloaded.
     *
     * @return `true` keep this monitor running after a failed reload,
     *   `false` exit because a core reload started a replacement monitor,
     *   `null` when the switch was rejected (state raced).
     */
    private suspend fun commitRouteSwitch(
        current: State,
        fromIndex: Int,
        toIndex: Int,
        reason: String,
    ): Boolean? {
        val plan = synchronized(lock) { state?.plan } ?: return null
        val fromTag = plan.routes[fromIndex]
        val toTag = plan.routes[toIndex]
        val fromMode = RoscomPriorityRouting.modeForRouteTag(fromTag)
        val toMode = RoscomPriorityRouting.modeForRouteTag(toTag)

        val preferentialSameProfile =
            fromMode == toMode &&
                (reason == "initial selection" || reason == "recovery")
        if (preferentialSameProfile) {
            LogUtil.transport(
                "Smart priority $reason keeps $fromTag " +
                    "(also preferred $toTag; profile $fromMode unchanged; skip hev bounce)"
            )
            return true
        }

        if (!switchRoute(current, fromIndex, toIndex)) return null
        CoreServiceManager.setActiveOutboundTag(toTag)
        val dataplaneChanged = CoreServiceManager.getActiveRoutingMode() != toMode
        applyDataplaneForRouteMode(toMode, resetSessions = dataplaneChanged)
        LogUtil.transport(
            "Smart priority $reason $fromTag -> $toTag " +
                "(${fromMode} -> ${toMode}; " +
                if (dataplaneChanged) {
                    "hev -> ${toMode} SOCKS, reload to pin outbound)"
                } else {
                    "same p-tier, keep pinned outbound)"
                }
        )
        if (!dataplaneChanged) return true
        lastReloadSucceeded = false
        val generation = reloadGeneration.incrementAndGet()
        val requested = CoreServiceManager.reloadPriorityRoute()
        if (!requested) {
            // STARTING/STOPPING reject reload. Keep the p5 tag and apply SOCKS+pin
            // when the service reaches RUNNING — rolling back left hev on dead P0.
            pendingDataplaneReload.set(true)
            LogUtil.transport(
                "Keeping $toTag; queued SOCKS+pin until dataplane is RUNNING"
            )
            return true
        }
        return awaitReloadCompletion(generation)
    }

    /** Point hev at the SOCKS inbound that owns this p-tier's rule table. */
    private fun applyDataplaneForRouteMode(
        mode: RoscomPriorityRouting.Mode,
        resetSessions: Boolean,
    ) {
        val previousMode = CoreServiceManager.getActiveRoutingMode()
        val basePort = SettingsManager.getSocksPort()
        val previousPort = RoscomPriorityRouting.tunSocksPort(previousMode, basePort)
        val nextPort = RoscomPriorityRouting.tunSocksPort(mode, basePort)
        CoreServiceManager.setActiveRoutingMode(mode)
        if (!resetSessions) return
        if (previousPort == nextPort) {
            LogUtil.transport(
                "SOCKS port $nextPort unchanged ($mode); skip dataplane reset"
            )
            return
        }
        LogUtil.transport(
            "SOCKS port $previousPort -> $nextPort ($mode); hev follows after core reload"
        )
        // Do not requestTunRecreate here: it races reload's RELOADING, skips the
        // hev restart, then ensureTun2Socks used to keep the old FULL port.
    }

    /**
     * Request a soft reload and wait for [onCoreStarted].
     * @return false when a new monitor was started by [onCoreStarted] (caller must exit);
     *   true when this monitor should keep running.
     */
    private suspend fun awaitPriorityReload(): Boolean {
        lastReloadSucceeded = false
        val generation = reloadGeneration.incrementAndGet()
        val requested = CoreServiceManager.reloadPriorityRoute()
        if (!requested) {
            LogUtil.transport("Smart priority reload request failed; rolling back")
            rollbackPendingSwitch()
            return true
        }
        return awaitReloadCompletion(generation)
    }

    private suspend fun awaitReloadCompletion(generation: Int): Boolean {

        val deadline = SystemClock.elapsedRealtime() + RELOAD_WAIT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (completedReloadGeneration >= generation) {
                return !lastReloadSucceeded
            }
            delay(200L)
        }

        LogUtil.transport(
            "Smart priority reload timed out after ${RELOAD_WAIT_TIMEOUT_MS}ms; " +
                "rolling back route and continuing probes (no hard TUN rebuild)"
        )
        // Prefer staying on the previous working core over a hard TUN rebuild that
        // historically deadlocked Stop when soft reload was stuck.
        rollbackPendingSwitch()
        notifyReloadAborted()
        return true
    }

    private suspend fun <T> withProbeWakeLock(block: suspend () -> T): T {
        val lock = wakeLock
        try {
            if (lock != null && !lock.isHeld) {
                @Suppress("DEPRECATION")
                lock.acquire(PROBE_WAKE_LOCK_TIMEOUT_MS)
            }
            return block()
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (_: RuntimeException) {
                // ignore double-release races
            }
        }
    }

    private suspend fun findHigherPriorityRoute(current: State, activeIndex: Int): Int? =
        probeRoutesInParallel(
            current,
            current.plan.routes.indices.filter {
                current.plan.tierIndexForRoute(it) < current.plan.tierIndexForRoute(activeIndex)
            },
        )

    /**
     * Keep the current route if it is still live. Otherwise pick the best live
     * tier — p5+ only when p0–p4 are actually down (whitelist zone), not because
     * the radio is LTE.
     */
    private fun chooseTransportOrBestRoute(
        current: State,
        previousIndex: Int,
        latencyByRoute: Map<Int, Long>,
        preferKeepCurrent: Boolean,
        preferSameMode: Boolean = preferKeepCurrent,
    ): Int? {
        if (latencyByRoute.isEmpty()) return null
        if (preferKeepCurrent && previousIndex in latencyByRoute) {
            return previousIndex
        }
        if (preferSameMode) {
            val previousTag = current.plan.routes.getOrNull(previousIndex)
            val previousMode = previousTag?.let(RoscomPriorityRouting::modeForRouteTag)
            if (previousMode != null) {
                val sameMode = latencyByRoute.filterKeys { index ->
                    RoscomPriorityRouting.modeForRouteTag(current.plan.routes[index]) ==
                        previousMode
                }
                if (sameMode.isNotEmpty()) {
                    val pick = PriorityFailoverConfig.chooseBestRoute(current.plan, sameMode)
                    if (pick != null) {
                        LogUtil.transport(
                            "Smart priority: prefer same-mode $previousMode " +
                                "route ${current.plan.routes[pick]}"
                        )
                        return pick
                    }
                }
            }
        }
        return PriorityFailoverConfig.chooseBestRoute(current.plan, latencyByRoute)
    }

    /** Probe together; prefer the best tier, then the lowest measured latency. */
    private suspend fun probeRoutesInParallel(current: State, candidates: List<Int>): Int? =
        PriorityFailoverConfig.chooseBestRoute(
            current.plan,
            probeRouteLatencies(current, candidates),
        )

    private suspend fun probeRouteLatencies(
        current: State,
        candidates: List<Int>,
    ): Map<Int, Long> =
        coroutineScope {
            candidates.map { candidateIndex ->
                async(Dispatchers.IO) {
                    candidateIndex to probeRoute(current, current.plan.routes[candidateIndex])
                }
            }.awaitAll().mapNotNull { (index, latencyMs) ->
                latencyMs?.let { index to it }
            }.toMap()
        }

    /** Probe through a route-specific HTTP inbound owned by the running core. */
    private fun probeRoute(current: State, routeTag: String): Long? {
        val port = current.probePorts[routeTag] ?: return null
        val client = probeClients.getOrPut(port) {
            OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, port)))
                .connectTimeout(ROUTE_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(ROUTE_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(ROUTE_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
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

    /**
     * Re-allocate priority-probe inbound ports before a reload retry.
     * Needed when the previous core did not release ports before the next startLoop.
     */
    fun rotateProbePortsForReload(): Boolean = synchronized(lock) {
        val current = state ?: return false
        clearProbeClients()
        val probePorts = allocateProbePorts(current.sourceConfig, current.plan.routes)
        state = current.copy(probePorts = probePorts)
        true
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
        probeNowRequested.set(false)
        lastTrafficErrorProbeAt.set(0L)
        lastTrafficErrorAt.set(0L)
        pendingFullReselection = false
        pendingDataplaneReload.set(false)
        clearProbeClients()
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        } catch (_: RuntimeException) {
        }
        wakeLock = null
        if (clearState) {
            synchronized(lock) {
                state = null
                pendingPreviousIndex = null
                nextRecoveryCheckAt = Long.MAX_VALUE
                recoveryIntervalMs = HIGHER_PRIORITY_PROBE_INITIAL_INTERVAL_MS
            }
            completedReloadGeneration = reloadGeneration.get()
            CoreServiceManager.setActiveRoutingMode(null)
        }
    }

    private fun clearProbeClients() {
        probeClients.values.forEach { it.connectionPool.evictAll() }
        probeClients.clear()
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
        // Apply Happ-style Roscom rules for the active priority before probe rules
        // are prepended. Soft reload keeps activeIndex, so failover to p5+ does not
        // restart selection at p0.
        RoscomPriorityRouting.apply(result, plan, activeIndex)
        RoscomPriorityRouting.pinBalancersToActiveRoute(result, plan, activeIndex)
        CoreConfigManager.applyGeoRuleCompatibility(result)
        CoreConfigManager.keepCustomInboundDestination(result)
        disableXrayHealthProbes(result)
        addRouteProbeInbounds(result, plan, probePorts)
        return result
    }

    /**
     * Smart Priority already probes routes. Do not let Xray burst-ping every
     * `route-*` outbound (battery + SIM-switch "network is down" blackhole).
     * Keep an empty burstObservatory stub: removing it while leftover leastLoad
     * settings remain made core init fail with "not all dependencies are resolved".
     */
    private fun disableXrayHealthProbes(result: JsonObject) {
        val balancers = result.objectOrNull("routing")?.arrayOrNull("balancers")
        balancers?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val strategy = element.asJsonObject.get("strategy")
                ?.takeIf { it.isJsonObject }?.asJsonObject
                ?: JsonObject().also { element.asJsonObject.add("strategy", it) }
            strategy.addProperty("type", "random")
            strategy.remove("settings")
        }
        result.remove("observatory")
        val stub = JsonObject()
        stub.add("subjectSelector", JsonArray())
        val ping = result.objectOrNull("burstObservatory")
            ?.objectOrNull("pingConfig")
            ?: JsonObject()
        ping.remove("connectivity")
        ping.addProperty("interval", "24h")
        ping.addProperty("sampling", 1)
        if (ping.stringOrNull("destination").isNullOrBlank()) {
            ping.addProperty("destination", "https://www.gstatic.com/generate_204")
        }
        stub.add("pingConfig", ping)
        result.add("burstObservatory", stub)
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
