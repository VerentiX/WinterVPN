package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.PriorityFailoverManager
import com.v2ray.ang.core.RoscomPriorityRouting
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MtuPathProbe
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    companion object {
        /**
         * A TCP session cannot survive a Wi-Fi/LTE source-address change.
         * Recreate TUN soon even if VALIDATED is slow — Telegram otherwise waits
         * on a dead long-lived connection while browsers open fresh sockets.
         */
        private const val TRANSPORT_SWITCH_RECONNECT_DELAY_MS = 550L

        /**
         * Link-properties callbacks are noisy: Android can publish several route
         * snapshots for one DHCP, validation, or VPN update. Restarting Xray for
         * each one also restarts observatory probes in custom profiles.
         */
        private const val SAME_NETWORK_RELOAD_COOLDOWN_MS = 30_000L

        /** Collapse rapid Wi-Fi↔LTE↔Wi-Fi flaps into one soft reload. */
        private const val TRANSPORT_SOFT_RELOAD_COOLDOWN_MS = 4_000L

        /**
         * Same-transport cellular Network/link churn (tower / NAT / whitelist
         * zone) may not always look like Wi-Fi↔LTE; allow one full-tier probe
         * without hammering on every DHCP/route callback.
         */
        private const val CELLULAR_LINK_RESELECT_COOLDOWN_MS = 45_000L

        /** onAvailable + validated-again must not start two parallel reselections. */
        private const val TRANSPORT_RESELECT_DEDUPE_MS = 15_000L

        /**
         * If VALIDATED never arrives (captive / broken cell), still reselect so we
         * are not stuck forever on a dead uplink — but give NetworkMonitor time first.
         */
        private const val TRANSPORT_RESELECT_FALLBACK_MS = 2_500L

        /** Collapse duplicate hev restarts from onAvailable + capabilities. */
        private const val HEV_RESTART_DEBOUNCE_MS = 1_500L

        /** Spacing so Wi-Fi↔LTE flaps cannot stack TUN recreates. */
        private const val HARD_RECONNECT_MIN_INTERVAL_MS = 3_000L

        /** User stop must not wait forever on a stuck soft reload. */
        private const val STOP_LOCK_TIMEOUT_MS = 2_500L
    }

    private enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        RELOADING,
        STOPPING,
    }

    private lateinit var mInterface: ParcelFileDescriptor
    @Volatile
    private var isRunning = false
    @Volatile
    private var serviceState = ServiceState.STOPPED
    private var tun2SocksService: Tun2SocksControl? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * Guards brief state transitions only. Never hold across native Xray/hev I/O:
     * that deadlocked soft reload + made the Stop button hang forever.
     */
    private val lifecycleLock = ReentrantLock()
    /** Serializes hev Stop/Start so SOCKS-port bounce cannot race TUN recreate. */
    private val hevLifecycleLock = ReentrantLock()
    private var networkReloadJob: Job? = null
    private var reloadJob: Job? = null
    /** Set by stop; reload/start paths must abort when they see it. */
    private val stopRequested = AtomicBoolean(false)
    /** First Stop wins; a recreate/start racing Stop must not wait on native join again. */
    private val stopEntered = AtomicBoolean(false)
    /** Start arrived while STOPPING — run it after teardown instead of dropping the tap. */
    private val pendingStartAfterStop = AtomicBoolean(false)
    /** Explicit Start tap / queued start. START_STICKY after user Stop must not reconnect. */
    private val userWantsVpn = AtomicBoolean(false)
    /** Bumped on every Stop so an in-flight TUN recreate cannot establish after teardown. */
    private val lifecycleGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastHevRestartAt = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var currentUnderlyingNetwork: Network? = null
    private var lastUnderlyingNetwork: Network? = null
    private var lastLinkFingerprint: String? = null
    private var hasCapabilitySnapshot = false
    private var lastNetworkValidated = false
    private var lastNetworkBlocked = false
    private var networkCallbackRegistered = false
    private var linkPropertiesReady = false
    private var pendingUnderlyingSwitch = false
    /**
     * Wi-Fi↔LTE arrived while [serviceState] was RELOADING. Sticky full-tier
     * reselection must run once we are RUNNING again — onAvailable alone was
     * gated on isServiceReady() and silently dropped the LTE hop.
     */
    @Volatile
    private var reselectAfterReload = false
    private var lastTransportDescription = "unknown"
    private var lastUnderlyingMtu = 0
    @Volatile
    private var lastSoftNetworkReloadAt = 0L
    @Volatile
    private var lastSoftReloadNetwork: Network? = null
    @Volatile
    private var lastHardReconnectAt = 0L
    /** Network we already rebuilt TUN for; extra callbacks for this id must not recreate. */
    @Volatile
    private var lastHardReconnectNetwork: Network? = null
    @Volatile
    private var lastTransportSoftReloadAt = 0L
    @Volatile
    private var lastCellularLinkReselectAt = 0L
    @Volatile
    private var lastTransportIsCellular = false
    /** Network id we already issued a full-tier reselect for (dedupe validated-again). */
    @Volatile
    private var lastTransportReselectNetwork: Network? = null
    @Volatile
    private var lastTransportReselectAt = 0L
    /** Last network passed to VpnService.setUnderlyingNetworks. */
    @Volatile
    private var publishedUnderlyingNetwork: Network? = null
    /** SOCKS port hev was last started with; FULL vs WHITELIST use different inbounds. */
    @Volatile
    private var lastHevSocksPort = 0

    /**destroy
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = lastUnderlyingNetwork
                LogUtil.transport("Network available=$network previous=$previous")
                currentUnderlyingNetwork = network
                lastUnderlyingNetwork = network
                lastLinkFingerprint = null
                hasCapabilitySnapshot = false
                linkPropertiesReady = false
                lastNetworkBlocked = false
                // Rebind immediately so Telegram keeps network=true, then recreate
                // TUN — stale TCP cannot migrate across a source-address change.
                applyUnderlyingNetwork(network)

                pendingUnderlyingSwitch = previous != null && previous != network
                if (pendingUnderlyingSwitch) {
                    val newCaps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
                    val prevCaps = previous?.let {
                        runCatching { connectivity.getNetworkCapabilities(it) }.getOrNull()
                    }
                    val newCellular = newCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                    val prevCellular = prevCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                    val cellularToCellular = prevCellular && newCellular
                    if (newCellular) lastTransportIsCellular = true

                    when (serviceState) {
                        ServiceState.RUNNING -> {
                            // Same working path as before: close TUN so Android RSTs
                            // Telegram's dead TCP. Rebind-only left ESTABLISHED NAT
                            // sessions until timeout — «ожидание сети».
                            LogUtil.transport(
                                if (cellularToCellular) {
                                    "Cellular network changed (LTE→LTE); recreate TUN"
                                } else {
                                    "Transport changed; recreate TUN"
                                }
                            )
                            lastTransportReselectNetwork = network
                            lastTransportReselectAt = SystemClock.elapsedRealtime()
                            scheduleNetworkReload(
                                delayMs = TRANSPORT_SWITCH_RECONNECT_DELAY_MS,
                                recreateTun = true,
                            )
                            if (cellularToCellular) {
                                lastCellularLinkReselectAt = SystemClock.elapsedRealtime()
                            }
                        }
                        ServiceState.RELOADING -> {
                            LogUtil.transport(
                                if (cellularToCellular) {
                                    "Cellular network changed mid-reload; queue TUN recreate"
                                } else {
                                    "Transport changed mid-reload; queue TUN recreate"
                                }
                            )
                            lastTransportReselectNetwork = network
                            lastTransportReselectAt = SystemClock.elapsedRealtime()
                        }
                        else -> Unit
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (currentUnderlyingNetwork == network) {
                    val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    lastTransportDescription = describeTransport(networkCapabilities)
                    lastTransportIsCellular =
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    LogUtil.transport(
                        "Network capabilities=$network transport=$lastTransportDescription " +
                            "validated=$validated metered=${!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}"
                    )
                    val recovered = hasCapabilitySnapshot && !lastNetworkValidated && validated
                    lastNetworkValidated = validated
                    hasCapabilitySnapshot = true
                    applyUnderlyingNetwork(network)
                    if (pendingUnderlyingSwitch) {
                        scheduleValidatedNetworkReloadIfReady()
                    } else if (recovered && isServiceReady()) {
                        val sinceReselect = SystemClock.elapsedRealtime() - lastTransportReselectAt
                        if (
                            network == lastTransportReselectNetwork &&
                            sinceReselect < TRANSPORT_RESELECT_DEDUPE_MS
                        ) {
                            LogUtil.transport(
                                "Network validated again; skip duplicate reselection " +
                                    "(already issued ${sinceReselect}ms ago)"
                            )
                        } else {
                            LogUtil.transport(
                                "Network validated again; full-tier reselection (no soft reload)"
                            )
                            lastTransportReselectNetwork = network
                            lastTransportReselectAt = SystemClock.elapsedRealtime()
                            PriorityFailoverManager.requestImmediatePriorityReselection()
                        }
                    }
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (currentUnderlyingNetwork != network) return

                val fingerprint = buildString {
                    append(linkProperties.interfaceName.orEmpty())
                    append('|')
                    append(linkProperties.linkAddresses.map { it.toString() }.sorted().joinToString(","))
                    append('|')
                    append(linkProperties.routes.map { it.toString() }.sorted().joinToString(","))
                }
                val previous = lastLinkFingerprint
                lastLinkFingerprint = fingerprint
                linkPropertiesReady = true
                lastUnderlyingMtu = linkProperties.mtu
                val mtuChanged = updateAdaptiveMtu(linkProperties)
                LogUtil.transport(
                    "Link ready=$network iface=${linkProperties.interfaceName} " +
                        "underlyingMtu=${linkProperties.mtu} effectiveMtu=${SettingsManager.getEffectiveVpnMtu()} " +
                        "addresses=${linkProperties.linkAddresses.size} routes=${linkProperties.routes.size}"
                )
                if (pendingUnderlyingSwitch) {
                    scheduleValidatedNetworkReloadIfReady()
                } else if (mtuChanged && isServiceReady()) {
                    // Builder MTU can only change via a new establish() — rare path.
                    LogUtil.transport("Effective MTU changed; recreating TUN")
                    scheduleNetworkReload(delayMs = 200L, recreateTun = true)
                } else if (previous != null && previous != fingerprint) {
                    // Same Network id, new addresses/routes: common when the phone
                    // moves between cells / whitelist NATs without a Wi-Fi↔LTE flip.
                    if (lastTransportIsCellular && isServiceReady()) {
                        val now = SystemClock.elapsedRealtime()
                        val remaining = CELLULAR_LINK_RESELECT_COOLDOWN_MS -
                            (now - lastCellularLinkReselectAt)
                        if (lastCellularLinkReselectAt == 0L || remaining <= 0L) {
                            lastCellularLinkReselectAt = now
                            LogUtil.transport(
                                "Cellular link changed; full-tier reselection " +
                                    "(possible whitelist zone)"
                            )
                            PriorityFailoverManager.requestImmediatePriorityReselection()
                        } else {
                            LogUtil.transport(
                                "Cellular link changed; reselection cooldown=${remaining}ms"
                            )
                        }
                    } else {
                        LogUtil.transport("Link routes changed; keeping current core")
                    }
                }
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (currentUnderlyingNetwork != network) return
                val unblocked = lastNetworkBlocked && !blocked
                lastNetworkBlocked = blocked
                LogUtil.transport("Network blocked state=$blocked network=$network")
                if (blocked && isServiceReady()) {
                    // Underlying path is blocked — confirm Smart Priority route without
                    // waiting for the normal probe interval.
                    PriorityFailoverManager.onTrafficError("network blocked")
                }
                if (unblocked && isServiceReady()) {
                    LogUtil.transport("Network unblocked; keep core, uplink already rebound")
                    reportVpnConnectivityAvailable()
                }
            }

            override fun onLost(network: Network) {
                if (currentUnderlyingNetwork == network) {
                    LogUtil.transport("Active underlying network lost=$network")
                    currentUnderlyingNetwork = null
                    lastLinkFingerprint = null
                    hasCapabilitySnapshot = false
                    linkPropertiesReady = false
                    pendingUnderlyingSwitch = false
                    lastUnderlyingMtu = 0
                    lastTransportDescription = "unknown"
                    lastTransportIsCellular = false
                    if (SettingsManager.followsNetworkMtu()) SettingsManager.setRuntimeVpnMtu(null)
                    networkReloadJob?.cancel()
                    networkReloadJob = null
                    // Do NOT setUnderlyingNetworks(null) here: null means "system default",
                    // which on Wi-Fi→LTE is the fresh unvalidated cell and makes Telegram
                    // flap to "waiting for network". Keep the last underlying until
                    // onAvailable/VALIDATED publishes the replacement.
                    LogUtil.transport(
                        "Keeping previous underlyingNetworks until next uplink is applied"
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        CoreServiceManager.bindServiceControl(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        userWantsVpn.set(false)
        stopAllService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")

        // Android may destroy the Service without routing the request through
        // stopAllService() (task removal, package/service management, or an OEM
        // lifecycle decision). In that case stop the native core while the
        // ServiceControl and foreground notification still point to this instance.
        // A hard process kill cannot invoke onDestroy(), but every graceful path
        // must release Xray's sockets and the TUN descriptor deterministically.
        val needsUnexpectedCleanup = lifecycleLock.withLock {
            when (serviceState) {
                ServiceState.STOPPED, ServiceState.STOPPING -> false
                else -> true
            }
        }
        if (needsUnexpectedCleanup) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Unexpected service destruction; stopping core")
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_STOP_SUCCESS, "")
            stopAllService(isForced = false)
        }

        // stop deliberately leaves the descriptor to its
        // caller in some paths. Close it here even when the core was only partially started.
        if (::mInterface.isInitialized) {
            try {
                mInterface.close()
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed in onDestroy")
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface in onDestroy", e)
            }
        }

        // cancelNotification() resolves the Service through serviceControl, so it
        // has to run before unbindServiceControl(). The previous order silently
        // skipped stopForeground().
        NotificationManager.cancelNotification()
        CoreServiceManager.unbindServiceControl(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received")

        if (intent == null) {
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: Ignoring START_STICKY redelivery")
            if (serviceState == ServiceState.STOPPED) {
                stopSelf()
            }
            return START_NOT_STICKY
        }
        userWantsVpn.set(true)

        var startGeneration = lifecycleGeneration.get()
        val shouldStart = lifecycleLock.withLock {
            when (serviceState) {
                ServiceState.STOPPED -> {
                    pendingStartAfterStop.set(false)
                    stopRequested.set(false)
                    stopEntered.set(false)
                    startGeneration = lifecycleGeneration.get()
                    serviceState = ServiceState.STARTING
                    true
                }
                ServiceState.STOPPING -> {
                    pendingStartAfterStop.set(true)
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: Queueing start until current stop finishes")
                    false
                }
                ServiceState.STARTING, ServiceState.RUNNING, ServiceState.RELOADING -> {
                    if (stopRequested.get()) {
                        pendingStartAfterStop.set(true)
                        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Queueing start; stop already requested")
                    }
                    false
                }
            }
        }
        if (!shouldStart) {
            if (!pendingStartAfterStop.get()) {
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: Ignoring duplicate start in state $serviceState")
            }
            if (serviceState == ServiceState.RUNNING) {
                CoreServiceManager.notifyUiCurrentServiceState(this)
            }
            return START_NOT_STICKY
        }

        NotificationManager.showNotification(null)

        // Building a config can perform DNS and disk I/O. Keep it away from the
        // service main thread so a slow resolver cannot cause a service ANR.
        // Do NOT hold lifecycleLock across setup/start — that blocked soft reload
        // and the Stop button for the entire native start path.
        val generation = startGeneration
        serviceScope.launch {
            try {
                if (shouldAbortLifecycle(generation)) {
                    return@launch
                }
                if (!setupVpnService(generation)) {
                    if (shouldAbortLifecycle(generation)) return@launch
                    lifecycleLock.withLock {
                        serviceState = ServiceState.STOPPED
                        isRunning = false
                    }
                    NotificationManager.cancelNotification()
                    CoreServiceManager.notifyUiCurrentServiceState(this@CoreVpnService)
                    stopSelf()
                    return@launch
                }
                if (shouldAbortLifecycle(generation)) {
                    return@launch
                }
                startServiceForGeneration(generation)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Unhandled startup failure", e)
                if (!shouldAbortLifecycle(generation)) {
                    stopAllService(isForced = true)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun isServiceActive(): Boolean = when (serviceState) {
        ServiceState.STARTING, ServiceState.RUNNING, ServiceState.RELOADING -> true
        ServiceState.STOPPING, ServiceState.STOPPED -> false
    }

    override fun isDataplaneReady(): Boolean = serviceState == ServiceState.RUNNING

    override fun startService() {
        startServiceForGeneration(lifecycleGeneration.get())
    }

    private fun startServiceForGeneration(generation: Int) {
        if (!::mInterface.isInitialized) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        if (shouldAbortLifecycle(generation)) {
            return
        }
        // Do not tell the UI we are connected until TUN+hev are actually up.
        val coreStarted = CoreServiceManager.startCoreLoop(mInterface, notifyUi = false)
        if (!coreStarted) {
            if (CoreServiceManager.isRunning()) {
                LogUtil.transport("Core already running; attaching hev without teardown")
            } else {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
                if (!shouldAbortLifecycle(generation)) stopAllService(isForced = true)
                return
            }
        }

        if (shouldAbortLifecycle(generation)) {
            return
        }

        // SOCKS must be listening before hev attaches to the new TUN fd.
        if (shouldAbortLifecycle(generation) || !runTun2socks()) {
            if (!shouldAbortLifecycle(generation)) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start tun2socks after core start")
                stopAllService(isForced = true)
            }
            return
        }

        if (shouldAbortLifecycle(generation)) {
            return
        }

        // Start LAN sharing if enabled in settings
        RootLanSharing.startClientSharing(this)
        lifecycleLock.withLock {
            if (shouldAbortLifecycle(generation)) return@withLock
            serviceState = ServiceState.RUNNING
            flushQueuedTunRecreate()
        }
        if (shouldAbortLifecycle(generation)) {
            return
        }
        CoreServiceManager.notifyDataplaneStarted(this)
        PriorityFailoverManager.onDataplaneReady()
    }

    override fun stopService() {
        userWantsVpn.set(false)
        if (stopEntered.get()) {
            pendingStartAfterStop.set(false)
        }
        // Unlock the FAB immediately. Native teardown can hang; the user must
        // never be stuck with a filled button and no tunnel.
        MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        stopAllService(true)
    }

    override fun reloadService(force: Boolean): Boolean {
        val accepted = lifecycleLock.withLock {
            !stopRequested.get() &&
                isRunning &&
                ::mInterface.isInitialized &&
                serviceState == ServiceState.RUNNING
        }
        if (!accepted) {
            LogUtil.transport("Rejecting soft reload in state=$serviceState running=$isRunning")
            if (serviceState == ServiceState.STARTING) {
                PriorityFailoverManager.markPendingDataplaneReload()
            } else if (serviceState != ServiceState.STOPPING) {
                PriorityFailoverManager.notifyReloadAborted()
            }
            return false
        }
        reloadJob?.cancel()
        val job = serviceScope.launch {
            val executed = reloadCoreKeepingTun(
                reason = if (force) "active priority route change" else "profile change",
                skipIfSelectedAlreadyRunning = !force,
            )
            if (force && !executed && !stopRequested.get()) {
                LogUtil.transport("Queued priority reload could not execute; notifying monitor")
                PriorityFailoverManager.notifyReloadAborted()
            }
        }
        reloadJob = job
        if (job.isCancelled) {
            LogUtil.transport("Soft reload scope is inactive; request rejected")
            PriorityFailoverManager.notifyReloadAborted()
            return false
        }
        return true
    }

    override fun recoverStalledReload(): Boolean {
        if (stopRequested.get() || !isRunning || !::mInterface.isInitialized) return false
        reloadJob?.cancel()
        val job = serviceScope.launch {
            try {
                LogUtil.transport("Hard reconnect (stalled priority reload), state=$serviceState")
                recoverFromFailedSoftReload()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Stalled reload recovery crashed; closing VPN", e)
                stopAllService()
            }
        }
        reloadJob = job
        return !job.isCancelled
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun requestTunRecreate() {
        // SOCKS FULL↔WHITELIST port change: restart hev on the live TUN.
        // A second Android TUN recreate after Wi-Fi↔LTE is what looked like
        // "several reconnects" and raced lwIP.
        restartHevKeepingTun("SOCKS port change")
    }

    private fun restartHevKeepingTun(reason: String) {
        serviceScope.launch {
            if (shouldAbortLifecycle()) return@launch
            val accepted = lifecycleLock.withLock {
                !stopRequested.get() &&
                    isRunning &&
                    ::mInterface.isInitialized &&
                    serviceState == ServiceState.RUNNING
            }
            if (!accepted) {
                LogUtil.transport("Skip hev restart ($reason); state=$serviceState")
                return@launch
            }
            LogUtil.transport("Restarting hev on same TUN ($reason)")
            if (!stopHevWhileTunOpen(reason)) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: hev still joining; skip restart after $reason")
                return@launch
            }
            if (shouldAbortLifecycle()) return@launch
            if (!runTun2socks()) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: hev restart failed after $reason")
            } else {
                reportVpnConnectivityAvailable()
            }
        }
    }

    override fun resetDataplaneForTransport() {
        val accepted = lifecycleLock.withLock {
            !stopRequested.get() &&
                isRunning &&
                ::mInterface.isInitialized &&
                serviceState == ServiceState.RUNNING
        }
        if (!accepted) return
        serviceScope.launch {
            if (shouldAbortLifecycle()) return@launch
            val now = SystemClock.elapsedRealtime()
            val previous = lastHevRestartAt.get()
            if (now - previous < HEV_RESTART_DEBOUNCE_MS) {
                LogUtil.transport(
                    "Skip duplicate hev restart (${now - previous}ms < ${HEV_RESTART_DEBOUNCE_MS}ms)"
                )
                reportVpnConnectivityAvailable()
                return@launch
            }
            if (!lastHevRestartAt.compareAndSet(previous, now)) {
                reportVpnConnectivityAvailable()
                return@launch
            }
            if (shouldAbortLifecycle()) return@launch
            LogUtil.transport("Ensuring hev after transport rebind")
            ensureTun2SocksAfterCoreReload("transport rebind")
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService(generation: Int = lifecycleGeneration.get()): Boolean {
        if (shouldAbortLifecycle(generation)) return false
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            return false
        }

        if (configureVpnService(generation) != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            return false
        }

        return true
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(generation: Int = lifecycleGeneration.get()): Boolean {
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed
        try {
            if (::mInterface.isInitialized) {
                mInterface.close()
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to close old interface", e)
        }

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Create a new interface using the builder and save the parameters
        if (shouldAbortLifecycle(generation)) {
            LogUtil.transport("Aborting TUN establish; user stop already in progress")
            return false
        }
        try {
            mInterface = builder.establish()!!
            isRunning = true
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        seedAdaptiveMtuFromActiveNetwork()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getEffectiveVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        //builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android P (API 28) and above: Configure network callbacks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !networkCallbackRegistered) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
                networkCallbackRegistered = true
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to request network", e)
            }
        }

        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks(): Boolean {
        if (!SettingsManager.isUsingHevTun()) {
            tun2SocksService = null
            return true
        }
        val started = hevLifecycleLock.withLock {
            if (TProxyService.isHevStopPending()) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: refusing hev Start; Stop still joining")
                return@withLock false
            }
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
            tun2SocksService?.startTun2Socks() ?: false
        }
        if (started) {
            lastHevSocksPort = desiredHevSocksPort()
        }
        return started
    }

    private fun desiredHevSocksPort(): Int =
        RoscomPriorityRouting.tunSocksPort(
            CoreServiceManager.getActiveRoutingMode(),
            SettingsManager.getSocksPort(),
        )

    /**
     * Quit+join hev while the TUN fd is still open so lwIP can drop PCBs cleanly.
     * Closing TUN first left CLOSED pcbs on the active list; the next Start then
     * aborted in tcp_slowtmr (SIGABRT, process gone).
     */
    private fun stopHevWhileTunOpen(reason: String): Boolean {
        tun2SocksService = null
        if (!SettingsManager.isUsingHevTun()) {
            return true
        }
        val startedAt = SystemClock.elapsedRealtime()
        LogUtil.transport("Stopping hev before closing TUN ($reason)")
        val stopped = hevLifecycleLock.withLock {
            TProxyService.stopNativeTunnel(
                wakeAfterMs = 2_000L,
                joinGiveUpMs = 8_000L,
                onStuck = {
                    // TUN EOF unblocks lwIP. Do not establish() a replacement
                    // until JNI join returns — fd reuse blackholes Telegram.
                    LogUtil.transport(
                        "hev join stuck; closing TUN to unblock worker ($reason)"
                    )
                    closeTunInterface("hev join unblock: $reason")
                },
            )
        }
        lastHevSocksPort = 0
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (stopped) {
            LogUtil.transport("hev stopped for $reason in ${elapsed}ms")
        } else {
            LogUtil.transport(
                "hev join still pending after $reason in ${elapsed}ms; " +
                    "not establishing a new TUN until the worker exits"
            )
        }
        return stopped
    }

    private fun closeTunInterface(reason: String) {
        if (!::mInterface.isInitialized) {
            return
        }
        try {
            mInterface.close()
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed ($reason)")
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to close TUN ($reason)", e)
        }
    }

    /**
     * After Xray is listening, hev must sit on the SOCKS inbound for the
     * active p-tier. Keeping a live worker after FULL↔WHITELIST left Telegram
     * on 10808 while Xray only accepted whitelist traffic on 10818.
     */
    private fun ensureTun2SocksAfterCoreReload(reason: String): Boolean {
        if (!SettingsManager.isUsingHevTun()) {
            reportVpnConnectivityAvailable()
            return true
        }
        if (!::mInterface.isInitialized) {
            return false
        }
        val wantPort = desiredHevSocksPort()
        if (TProxyService.isHevStopPending()) {
            LogUtil.transport("hev Stop still joining; skip SOCKS bounce after $reason")
            return false
        }
        val running = TProxyService.isNativeTunnelRunning()
        if (running && lastHevSocksPort == wantPort && lastHevSocksPort != 0) {
            LogUtil.transport("hev already on SOCKS $wantPort after $reason")
            reportVpnConnectivityAvailable()
            return true
        }
        if (running) {
            LogUtil.transport(
                "hev SOCKS $lastHevSocksPort -> $wantPort after $reason; restarting"
            )
            if (!stopHevWhileTunOpen("$reason SOCKS port")) {
                return false
            }
            if (shouldAbortLifecycle()) return false
        } else {
            LogUtil.transport("hev not running after $reason; starting on $wantPort")
        }
        val started = runTun2socks()
        if (started) {
            reportVpnConnectivityAvailable()
        }
        return started
    }

    private fun applyUnderlyingNetwork(network: Network?) {
        try {
            if (!connectivity.bindProcessToNetwork(network)) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to bind process to $network")
            }
            // Always publish the real uplink immediately (WireGuard-style).
            // Deferring until VALIDATED left UnderlyingNetworks=[] when Wi-Fi died and
            // LTE was still unvalidated — AyuGram stuck on network=false forever.
            // Never pass emptyArray(): that means "no upstream".
            if (network == null) {
                setUnderlyingNetworks(null)
                publishedUnderlyingNetwork = null
                LogUtil.transport("setUnderlyingNetworks=null")
                return
            }
            setUnderlyingNetworks(arrayOf(network))
            publishedUnderlyingNetwork = network
            val validated = runCatching { connectivity.getNetworkCapabilities(network) }
                .getOrNull()
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            LogUtil.transport("setUnderlyingNetworks=$network validated=$validated")
            // Keep Telegram's isNetworkOnline() true while we RST TUN TCP.
            reportVpnConnectivityAvailable()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to apply underlying network $network", e)
        }
    }

    /** Nudge ConnectivityService after dataplane is back so apps leave "waiting for network". */
    private fun reportVpnConnectivityAvailable() {
        try {
            connectivity.allNetworks.forEach { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@forEach
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@forEach
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@forEach
                connectivity.reportNetworkConnectivity(network, true)
                LogUtil.transport("reportNetworkConnectivity($network, true)")
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: reportNetworkConnectivity failed", e)
        }
    }

    private fun isServiceReady(): Boolean = serviceState == ServiceState.RUNNING

    private fun shouldAbortLifecycle(generation: Int = lifecycleGeneration.get()): Boolean {
        return stopRequested.get() || generation != lifecycleGeneration.get()
    }

    /** Run queued full-tier Smart Priority probe after soft reload leaves RELOADING. */
    private fun flushReselectAfterReload() {
        if (!reselectAfterReload) return
        reselectAfterReload = false
        if (stopRequested.get() || serviceState != ServiceState.RUNNING) return
        LogUtil.transport("Flushing queued full-tier reselection after soft reload")
        PriorityFailoverManager.requestImmediatePriorityReselection()
    }

    private fun scheduleValidatedNetworkReloadIfReady() {
        if (!pendingUnderlyingSwitch) return
        // onAvailable already queued the single TUN recreate for this Network.
        // VALIDATED used to schedule a second one — that's the extra reconnect.
        LogUtil.transport(
            "Underlying network validated=$lastNetworkValidated; " +
                "TUN recreate already queued or completed"
        )
    }

    /** Do not wait for VALIDATED — same as the 550 ms onAvailable path. */
    private fun flushQueuedTunRecreate() {
        if (shouldAbortLifecycle() || !pendingUnderlyingSwitch || !isServiceReady()) return
        if (currentUnderlyingNetwork != null &&
            currentUnderlyingNetwork == lastHardReconnectNetwork
        ) {
            pendingUnderlyingSwitch = false
            LogUtil.transport("Dropping queued TUN recreate; already rebuilt for $currentUnderlyingNetwork")
            return
        }
        LogUtil.transport("Queued transport change; recreating TUN")
        scheduleNetworkReload(
            delayMs = 150L,
            consumePendingSwitch = false,
            recreateTun = true,
        )
    }

    /**
     * Fallback when VALIDATED is slow/missing: still run one full-tier probe so we
     * are not stuck, but only after [TRANSPORT_RESELECT_FALLBACK_MS].
     */
    private fun scheduleDeferredTransportReselection(network: Network) {
        networkReloadJob?.cancel()
        networkReloadJob = serviceScope.launch {
            delay(TRANSPORT_RESELECT_FALLBACK_MS)
            if (stopRequested.get() || !isServiceReady()) return@launch
            if (currentUnderlyingNetwork != network) return@launch
            if (!pendingUnderlyingSwitch) return@launch
            pendingUnderlyingSwitch = false
            LogUtil.transport(
                "Transport reselection fallback after ${TRANSPORT_RESELECT_FALLBACK_MS}ms " +
                    "(uplink still unvalidated)"
            )
            lastTransportReselectNetwork = network
            lastTransportReselectAt = SystemClock.elapsedRealtime()
            PriorityFailoverManager.requestImmediatePriorityReselection()
        }
    }

    private fun scheduleNetworkReload(
        delayMs: Long = 220L,
        consumePendingSwitch: Boolean = true,
        recreateTun: Boolean = false,
        transportHandover: Boolean = false,
    ) {
        val targetNetwork = currentUnderlyingNetwork
        if (recreateTun) {
            // Hard recreate is the Wi-Fi↔LTE / recovery path.
            reloadJob?.cancel()
            reloadJob = null
            val sinceHard = SystemClock.elapsedRealtime() - lastHardReconnectAt
            val sameNetwork = targetNetwork != null && targetNetwork == lastHardReconnectNetwork
            if (lastHardReconnectAt != 0L && sinceHard < HARD_RECONNECT_MIN_INTERVAL_MS) {
                if (sameNetwork) {
                    pendingUnderlyingSwitch = false
                    LogUtil.transport(
                        "Dropping extra TUN recreate for $targetNetwork " +
                            "(already rebuilt ${sinceHard}ms ago)"
                    )
                    return
                }
                val waitMs = HARD_RECONNECT_MIN_INTERVAL_MS - sinceHard
                LogUtil.transport(
                    "Deferring TUN recreate; hard reconnect cooldown=${waitMs}ms " +
                        "network=$targetNetwork"
                )
                networkReloadJob?.cancel()
                networkReloadJob = serviceScope.launch {
                    delay(waitMs + delayMs)
                    if (shouldAbortLifecycle()) return@launch
                    if (currentUnderlyingNetwork != targetNetwork) {
                        LogUtil.transport(
                            "Discarding deferred TUN recreate for network=$targetNetwork; " +
                                "current=$currentUnderlyingNetwork"
                        )
                        return@launch
                    }
                    if (consumePendingSwitch || pendingUnderlyingSwitch) {
                        pendingUnderlyingSwitch = false
                    }
                    try {
                        LogUtil.transport("Recreating TUN after transport change (deferred)")
                        recreateTunAfterTransportChange()
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "StartCore-VPN: Network recovery crashed; closing VPN", e)
                        if (!shouldAbortLifecycle()) stopAllService()
                    }
                }
                return
            }
        } else {
            val now = SystemClock.elapsedRealtime()
            if (transportHandover) {
                val remaining = TRANSPORT_SOFT_RELOAD_COOLDOWN_MS - (now - lastTransportSoftReloadAt)
                if (lastTransportSoftReloadAt != 0L && remaining > 0L) {
                    LogUtil.transport(
                        "Skipping transport soft reload; cooldown=${remaining}ms " +
                            "(upstream already rebound)"
                    )
                    return
                }
            }
            val remaining = SAME_NETWORK_RELOAD_COOLDOWN_MS - (now - lastSoftNetworkReloadAt)
            if (!transportHandover && targetNetwork != null && targetNetwork == lastSoftReloadNetwork &&
                lastSoftNetworkReloadAt != 0L && remaining > 0L
            ) {
                LogUtil.transport(
                    "Skipping duplicate soft reload for network=$targetNetwork; " +
                        "cooldown=${remaining}ms"
                )
                return
            }
            if (serviceState == ServiceState.RELOADING) {
                LogUtil.transport("Skipping soft reload; reload already in progress")
                return
            }
        }
        networkReloadJob?.cancel()
        networkReloadJob = serviceScope.launch {
            // Collapse Android's ordered callback burst and let it install routes.
            delay(delayMs)
            if (shouldAbortLifecycle()) return@launch
            if (currentUnderlyingNetwork != targetNetwork) {
                LogUtil.transport(
                    "Discarding stale reconnect for network=$targetNetwork; " +
                        "current=$currentUnderlyingNetwork"
                )
                return@launch
            }
            if (consumePendingSwitch || pendingUnderlyingSwitch) {
                pendingUnderlyingSwitch = false
            }
            try {
                if (recreateTun) {
                    LogUtil.transport("Recreating TUN after transport change")
                    recreateTunAfterTransportChange()
                } else {
                    lastSoftReloadNetwork = targetNetwork
                    lastSoftNetworkReloadAt = SystemClock.elapsedRealtime()
                    if (transportHandover) {
                        lastTransportSoftReloadAt = lastSoftNetworkReloadAt
                    }
                    val reason = if (transportHandover) {
                        "underlying transport changed"
                    } else {
                        "underlying network changed"
                    }
                    LogUtil.transport("Soft-refreshing core after network event on $targetNetwork")
                    val ok = reloadCoreKeepingTun(reason)
                    if (ok && transportHandover) {
                        PriorityFailoverManager.requestImmediatePriorityReselection()
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Network recovery crashed; closing VPN", e)
                if (!shouldAbortLifecycle()) stopAllService()
            }
        }
    }

    private fun describeTransport(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "LTE/5G"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "other"
    }

    /**
     * Network / adaptive MTU: Android link MTU by default; when adaptive is on,
     * prefer a path-probed value stored for the current transport.
     */
    private fun updateAdaptiveMtu(linkProperties: LinkProperties): Boolean {
        if (!SettingsManager.followsNetworkMtu()) return false
        val useProbed = SettingsManager.isAdaptiveMtuEnabled()
        val transport = when (lastTransportDescription) {
            "Wi-Fi" -> MtuPathProbe.Transport.WIFI
            "LTE/5G" -> MtuPathProbe.Transport.CELLULAR
            else -> null
        }
        val stored = if (useProbed) {
            transport?.let { SettingsManager.getAdaptiveMtuForTransport(it) }
        } else {
            null
        }
        val linkMtu = linkProperties.mtu.takeIf { it in 1280..9_000 }
        val chosen = stored ?: linkMtu
        return if (chosen != null) {
            SettingsManager.setRuntimeVpnMtu(chosen)
        } else {
            SettingsManager.setRuntimeVpnMtu(null)
        }
    }

    /** Apply link (and optional probed) MTU before Builder.setMtu on TUN create. */
    private fun seedAdaptiveMtuFromActiveNetwork() {
        if (!SettingsManager.followsNetworkMtu()) {
            SettingsManager.setRuntimeVpnMtu(null)
            return
        }
        val network = connectivity.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val props = network?.let { connectivity.getLinkProperties(it) }
        val useProbed = SettingsManager.isAdaptiveMtuEnabled()
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
                MtuPathProbe.Transport.WIFI
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                MtuPathProbe.Transport.CELLULAR
            else -> null
        }
        val stored = if (useProbed) {
            transport?.let { SettingsManager.getAdaptiveMtuForTransport(it) }
        } else {
            null
        }
        val linkMtu = props?.mtu?.takeIf { it in 1280..9_000 }
        SettingsManager.setRuntimeVpnMtu(stored ?: linkMtu)
    }

    private fun reloadCoreKeepingTun(
        reason: String,
        skipIfSelectedAlreadyRunning: Boolean = false,
    ): Boolean {
        val canStart = lifecycleLock.withLock {
            when {
                stopRequested.get() -> false
                !isRunning || !::mInterface.isInitialized -> false
                serviceState != ServiceState.RUNNING -> false
                skipIfSelectedAlreadyRunning && CoreServiceManager.isSelectedProfileRunning() -> false
                else -> {
                    serviceState = ServiceState.RELOADING
                    true
                }
            }
        }
        if (!canStart) {
            if (!stopRequested.get()) {
                LogUtil.transport("Cannot soft reload ($reason) in state=$serviceState running=$isRunning")
            }
            return false
        }

        // Native work runs WITHOUT lifecycleLock so Stop can always close the TUN.
        return try {
            if (shouldAbortLifecycle()) return false
            LogUtil.transport("Soft reload ($reason)")
            if (shouldAbortLifecycle()) return false
            // Keep hev attached to TUN the entire time (Clash / sing-box / v2rayNG).
            // SOCKS listens on the same port after startLoop; hev opens a new
            // CONNECT per client TCP so it does not need a restart.
            if (!CoreServiceManager.reloadCoreLoop(mInterface)) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Soft reload failed; attempting hard reconnect")
                if (!shouldAbortLifecycle()) recoverFromFailedSoftReload()
                return false
            }
            if (shouldAbortLifecycle()) return false
            if (!ensureTun2SocksAfterCoreReload(reason)) {
                val transportHandover = reason.contains("transport", ignoreCase = true)
                if (transportHandover) {
                    // Do not escalate to TUN recreate — that is what crashed VPN on flaps.
                    LogUtil.e(
                        AppConfig.TAG,
                        "StartCore-VPN: hev ensure failed after transport soft reload; " +
                            "keeping existing TUN/core (upstream already rebound)",
                    )
                    lifecycleLock.withLock {
                        if (!shouldAbortLifecycle()) {
                            serviceState = ServiceState.RUNNING
                            flushQueuedTunRecreate()
                        }
                    }
                    if (serviceState == ServiceState.RUNNING) {
                        flushReselectAfterReload()
                        PriorityFailoverManager.onDataplaneReady()
                    }
                    return !shouldAbortLifecycle() && serviceState == ServiceState.RUNNING
                }
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: hev ensure failed after soft reload; attempting hard reconnect")
                if (!shouldAbortLifecycle()) recoverFromFailedSoftReload()
                return false
            }
            lifecycleLock.withLock {
                if (shouldAbortLifecycle()) return@withLock
                serviceState = ServiceState.RUNNING
                flushQueuedTunRecreate()
            }
            if (serviceState == ServiceState.RUNNING && !shouldAbortLifecycle()) {
                flushReselectAfterReload()
                PriorityFailoverManager.onDataplaneReady()
            }
            !shouldAbortLifecycle() && serviceState == ServiceState.RUNNING
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Soft reload crashed ($reason)", e)
            if (!shouldAbortLifecycle()) recoverFromFailedSoftReload()
            false
        }
    }

    /** Full TUN rebuild after a soft reload left the core down but the VPN service alive. */
    private fun recoverFromFailedSoftReload() {
        val generation = lifecycleGeneration.get()
        if (shouldAbortLifecycle(generation)) {
            stopAllService(isForced = true)
            return
        }
        if (!::mInterface.isInitialized) {
            stopAllService(isForced = true)
            return
        }
        lifecycleLock.withLock {
            if (shouldAbortLifecycle(generation)) return
            serviceState = ServiceState.RELOADING
        }
        LogUtil.transport("Hard reconnect (failed soft reload recovery)")
        if (!CoreServiceManager.stopCoreLoop(preservePriorityState = true)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Core would not stop; closing VPN to avoid a traffic blackhole")
            stopAllService(isForced = true)
            return
        }
        if (!stopHevWhileTunOpen("soft reload recovery")) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: hev still joining; aborting TUN recreate")
            stopAllService(isForced = true)
            return
        }
        closeTunInterface("soft reload recovery")
        isRunning = false
        if (shouldAbortLifecycle(generation)) {
            stopAllService(isForced = true)
            return
        }
        PriorityFailoverManager.rotateProbePortsForReload()
        if (!setupVpnService(generation)) {
            if (shouldAbortLifecycle(generation)) {
                stopAllService(isForced = true)
                return
            }
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to recreate TUN after soft reload failure")
            stopAllService(isForced = true)
            return
        }
        lastHardReconnectAt = SystemClock.elapsedRealtime()
        lastHardReconnectNetwork = currentUnderlyingNetwork
        startServiceForGeneration(generation)
        if (shouldAbortLifecycle(generation)) {
            stopAllService(isForced = true)
            return
        }
        if (serviceState != ServiceState.RUNNING) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Hard reconnect recovery failed; stopping safely")
            stopAllService(isForced = true)
        }
    }

    /**
     * Rebuilds the Android VPN interface after Wi-Fi/LTE handover.
     *
     * Keeping TUN is less disruptive for browsers, but it cannot migrate an
     * already-open Telegram TCP connection to a different source address.
     * Closing it makes Android drop that stale flow immediately.
     *
     * Safety: native hev/Xray I/O is outside [lifecycleLock]. Stop hev
     * before closing the fd so lwIP can drop PCBs; then establish the new TUN.
     */
    private fun recreateTunAfterTransportChange() {
        val generation = lifecycleGeneration.get()
        val canRecreate = lifecycleLock.withLock {
            when {
                shouldAbortLifecycle(generation) -> false
                !isRunning || !::mInterface.isInitialized || serviceState != ServiceState.RUNNING -> {
                    // Do not consume a real handover merely because another reload
                    // won the race. The next transition to RUNNING will retry it.
                    pendingUnderlyingSwitch = true
                    LogUtil.transport("Deferring TUN recreation until service is running; state=$serviceState")
                    false
                }
                else -> {
                    serviceState = ServiceState.RELOADING
                    true
                }
            }
        }
        if (!canRecreate) return

        val startedAt = System.nanoTime()
        LogUtil.transport(
            "Hard reconnect (recreate TUN), transport=$lastTransportDescription " +
                "underlyingMtu=$lastUnderlyingMtu effectiveMtu=${SettingsManager.getEffectiveVpnMtu()}"
        )

        // Kill SOCKS first so hev is not stuck in a 120s TCP timeout, then
        // quit+join hev. Never establish() a new TUN while the worker still
        // holds the old fd — Android reuses that number and Telegram dies.
        if (!CoreServiceManager.stopCoreLoop(preservePriorityState = true)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Core would not stop after transport change; closing VPN")
            stopAllService(isForced = true)
            return
        }
        if (shouldAbortLifecycle(generation)) {
            stopAllService(isForced = true)
            return
        }
        if (!stopHevWhileTunOpen("transport TUN recreate")) {
            LogUtil.e(
                AppConfig.TAG,
                "StartCore-VPN: hev still joining after transport change; not replacing TUN",
            )
            stopAllService(isForced = true)
            return
        }
        closeTunInterface("transport TUN recreate")
        isRunning = false
        if (shouldAbortLifecycle(generation)) {
            stopAllService(isForced = true)
            return
        }
        if (!setupVpnService(generation)) {
            if (shouldAbortLifecycle(generation)) {
                stopAllService(isForced = true)
                return
            }
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to recreate TUN after transport change")
            stopAllService(isForced = true)
            return
        }
        lastHardReconnectAt = SystemClock.elapsedRealtime()
        lastHardReconnectNetwork = currentUnderlyingNetwork
        startServiceForGeneration(generation)
        if (shouldAbortLifecycle(generation)) {
            stopAllService(isForced = true)
            return
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        LogUtil.transport("Hard reconnect completed in ${elapsedMs}ms, state=$serviceState")
        if (serviceState == ServiceState.RUNNING) {
            // Keep the route that just came up, then immediately look for a
            // higher-priority outbound that works on the new transport.
            reselectAfterReload = false
            PriorityFailoverManager.requestImmediatePriorityReselection()
        } else {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Hard reconnect did not reach RUNNING; closing VPN")
            stopAllService(isForced = true)
        }
    }

    private fun stopAllService(isForced: Boolean = true) {
        LogUtil.transport(
            "stopAllService forced=$isForced state=$serviceState " +
                "from ${Throwable().stackTraceToString().lineSequence().drop(2).take(6).joinToString(" <- ")}",
        )
        if (!pendingStartAfterStop.get()) {
            userWantsVpn.set(false)
        }
        stopRequested.set(true)
        lifecycleGeneration.incrementAndGet()
        networkReloadJob?.cancel()
        networkReloadJob = null
        reloadJob?.cancel()
        reloadJob = null
        // Unblock Smart Priority waiters immediately so they do not hold work
        // while we tear the tunnel down.
        runCatching { PriorityFailoverManager.notifyReloadAborted() }

        if (!stopEntered.compareAndSet(false, true)) {
            // Recreate/start raced the user Stop. The first Stop already owns
            // teardown; extra taps must not close TUN again or drop a queued Start.
            LogUtil.transport("Stop already in progress; ignoring nested stop")
            return
        }

        val acquired = try {
            lifecycleLock.tryLock(STOP_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        try {
            if (acquired) {
                beginStopLocked(isForced)
            } else {
                LogUtil.e(
                    AppConfig.TAG,
                    "StartCore-VPN: Stop could not acquire lifecycle lock in ${STOP_LOCK_TIMEOUT_MS}ms; forcing TUN close",
                )
                forceCloseTunOnly()
            }
        } finally {
            if (acquired && lifecycleLock.isHeldByCurrentThread) {
                lifecycleLock.unlock()
            }
        }

        // Native teardown outside the lock so a slow stopLoop cannot block another Stop.
        finishStopOutsideLock(isForced)
    }

    /**
     * Brief locked phase: mark STOPPING and close TUN so internet returns immediately.
     */
    private fun beginStopLocked(isForced: Boolean) {
        if (serviceState == ServiceState.STOPPED && !isRunning) return
        serviceState = ServiceState.STOPPING
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                if (networkCallbackRegistered) {
                    connectivity.unregisterNetworkCallback(defaultNetworkCallback)
                    networkCallbackRegistered = false
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to unregister callback", e)
            }
        }
        currentUnderlyingNetwork = null
        lastUnderlyingNetwork = null
        lastLinkFingerprint = null
        hasCapabilitySnapshot = false
        lastNetworkValidated = false
        lastNetworkBlocked = false
        linkPropertiesReady = false
        pendingUnderlyingSwitch = false
        reselectAfterReload = false
        lastCellularLinkReselectAt = 0L
        lastTransportIsCellular = false
        lastTransportReselectNetwork = null
        lastTransportReselectAt = 0L
        publishedUnderlyingNetwork = null
        lastSoftNetworkReloadAt = 0L
        lastSoftReloadNetwork = null
        lastHardReconnectAt = 0L
        lastHardReconnectNetwork = null
        lastHevSocksPort = 0
        applyUnderlyingNetwork(null)
        // TUN stays open until hev quit+join in finishStopOutsideLock.
    }

    private fun forceCloseTunOnly() {
        if (!::mInterface.isInitialized) return
        serviceState = ServiceState.STOPPING
        isRunning = false
        try {
            mInterface.close()
            LogUtil.transport("Forced TUN close to restore internet")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Forced TUN close failed", e)
        }
    }

    private fun finishStopOutsideLock(isForced: Boolean) {
        val hevStopped = runCatching { stopHevWhileTunOpen("service stop") }.getOrDefault(false)
        if (isForced) {
            closeTunInterface("service stop")
        }
        runCatching { RootLanSharing.stopClientSharing(this) }
        runCatching {
            CoreServiceManager.stopCoreLoop(waitForLockMs = STOP_LOCK_TIMEOUT_MS)
        }
        runCatching { NotificationManager.cancelNotification() }
        serviceState = ServiceState.STOPPED
        isRunning = false
        stopEntered.set(false)
        val startQueued = pendingStartAfterStop.compareAndSet(true, false)
        if (startQueued && hevStopped && !TProxyService.isHevStopPending()) {
            userWantsVpn.set(true)
            stopRequested.set(false)
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: Starting after queued tap")
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, CoreVpnService::class.java),
                )
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Queued start failed", e)
                if (isForced) {
                    runCatching { stopSelf() }
                }
            }
        } else {
            if (startQueued) {
                LogUtil.e(
                    AppConfig.TAG,
                    "StartCore-VPN: Dropping queued start; hev worker has not exited",
                )
            }
            if (isForced) {
                runCatching { stopSelf() }
            }
        }
    }
}
