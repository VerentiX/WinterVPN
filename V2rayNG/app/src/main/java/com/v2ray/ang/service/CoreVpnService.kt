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
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.SoftReference

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    companion object {
        /**
         * A TCP session cannot survive a Wi-Fi/LTE source-address change.
         * Do not wait for Android's potentially slow VALIDATED probe before
         * rebuilding the VPN path; Telegram otherwise waits on its dead
         * long-lived connection while browsers create fresh sockets.
         */
        private const val TRANSPORT_SWITCH_RECONNECT_DELAY_MS = 550L

        /**
         * Link-properties callbacks are noisy: Android can publish several route
         * snapshots for one DHCP, validation, or VPN update. Restarting Xray for
         * each one also restarts observatory probes in custom profiles.
         */
        private const val SAME_NETWORK_RELOAD_COOLDOWN_MS = 30_000L
    }

    private enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        RELOADING,
        STOPPING,
    }

    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    @Volatile
    private var serviceState = ServiceState.STOPPED
    private var tun2SocksService: Tun2SocksControl? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private var networkReloadJob: Job? = null

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
    private var lastTransportDescription = "unknown"
    private var lastUnderlyingMtu = 0
    @Volatile
    private var lastSoftNetworkReloadAt = 0L
    @Volatile
    private var lastSoftReloadNetwork: Network? = null

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
                applyUnderlyingNetwork(network)

                pendingUnderlyingSwitch = previous != null && previous != network
                if (pendingUnderlyingSwitch && isServiceReady()) {
                    // A transport change invalidates existing long-lived TCP/UDP
                    // flows. Recycle Xray soon even if the system takes several
                    // seconds to report VALIDATED for a newly attached LTE cell.
                    // A normal validated callback can still shorten this to 150 ms.
                    LogUtil.transport("Transport changed; scheduling fast reconnect")
                    scheduleNetworkReload(
                        delayMs = TRANSPORT_SWITCH_RECONNECT_DELAY_MS,
                        consumePendingSwitch = false,
                        recreateTun = true,
                    )
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (currentUnderlyingNetwork == network) {
                    val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    lastTransportDescription = describeTransport(networkCapabilities)
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
                        scheduleNetworkReload()
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
                    LogUtil.transport("Effective MTU changed; recreating TUN")
                    scheduleNetworkReload(delayMs = 200L, recreateTun = true)
                } else if (previous != null && previous != fingerprint) {
                    // bindProcessToNetwork() already follows the current network.
                    // Route snapshots alone do not invalidate existing sockets and
                    // are routinely emitted in bursts by Android. In particular,
                    // reloading the core here recreates every custom-profile
                    // observatory probe and can flood the server with probe URLs.
                    LogUtil.transport("Link routes changed; keeping current core")
                }
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (currentUnderlyingNetwork != network) return
                val unblocked = lastNetworkBlocked && !blocked
                lastNetworkBlocked = blocked
                LogUtil.transport("Network blocked state=$blocked network=$network")
                if (unblocked && isServiceReady()) {
                    scheduleNetworkReload()
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
                    if (SettingsManager.isAdaptiveMtuEnabled()) SettingsManager.setRuntimeVpnMtu(null)
                    networkReloadJob?.cancel()
                    networkReloadJob = null
                    applyUnderlyingNetwork(null)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")

        // Ensure VPN interface is properly closed when the service is destroyed without
        // going through stopAllService() (e.g. when killed unexpectedly). isRunning is
        // set to false at the start of stopAllService(), so this guard prevents a double-close.
        if (isRunning) {
            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed in onDestroy")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface in onDestroy", e)
            }
        }

        NotificationManager.cancelNotification()
        serviceScope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received")
        NotificationManager.showNotification(null)

        val shouldStart = synchronized(lifecycleLock) {
            if (serviceState != ServiceState.STOPPED) {
                false
            } else {
                serviceState = ServiceState.STARTING
                true
            }
        }
        if (!shouldStart) {
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: Ignoring duplicate start in state $serviceState")
            return START_STICKY
        }

        // Building a config can perform DNS and disk I/O. Keep it away from the
        // service main thread so a slow resolver cannot cause a service ANR.
        serviceScope.launch {
            synchronized(lifecycleLock) {
                if (serviceState != ServiceState.STARTING) return@synchronized
                if (!setupVpnService()) {
                    serviceState = ServiceState.STOPPED
                    NotificationManager.cancelNotification()
                    stopSelf()
                    return@synchronized
                }
                startService()
            }
        }
        return START_STICKY
        //return super.onStartCommand(intent, flags, startId)
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (!::mInterface.isInitialized) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        if (!CoreServiceManager.startCoreLoop(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }

        // Start LAN sharing if enabled in settings
        RootLanSharing.startClientSharing(this)
        serviceState = ServiceState.RUNNING
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun reloadService(force: Boolean) {
        serviceScope.launch {
            reloadCoreKeepingTun(
                reason = if (force) "active priority route change" else "profile change",
                skipIfSelectedAlreadyRunning = !force,
            )
        }
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
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
    private fun setupVpnService(): Boolean {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            return false
        }

        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            return false
        }

        runTun2socks()
        return true
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
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
    private fun runTun2socks() {
        if (SettingsManager.isUsingHevTun()) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun isServiceReady(): Boolean = serviceState == ServiceState.RUNNING

    private fun scheduleValidatedNetworkReloadIfReady() {
        if (!pendingUnderlyingSwitch || !hasCapabilitySnapshot || !linkPropertiesReady || !lastNetworkValidated) {
            return
        }
        pendingUnderlyingSwitch = false
        // Recreate the complete VPN path. Reusing the same TUN descriptor can
        // leave a stale user-space TCP state after rapid LTE/Wi-Fi flapping.
        scheduleNetworkReload(
            delayMs = 150L,
            recreateTun = true,
        )
    }

    private fun scheduleNetworkReload(
        delayMs: Long = 220L,
        consumePendingSwitch: Boolean = true,
        recreateTun: Boolean = false,
    ) {
        val targetNetwork = currentUnderlyingNetwork
        if (!recreateTun) {
            val now = SystemClock.elapsedRealtime()
            val remaining = SAME_NETWORK_RELOAD_COOLDOWN_MS - (now - lastSoftNetworkReloadAt)
            if (targetNetwork != null && targetNetwork == lastSoftReloadNetwork &&
                lastSoftNetworkReloadAt != 0L && remaining > 0L
            ) {
                LogUtil.transport(
                    "Skipping duplicate soft reload for network=$targetNetwork; " +
                        "cooldown=${remaining}ms"
                )
                return
            }
        }
        networkReloadJob?.cancel()
        networkReloadJob = serviceScope.launch {
            // Collapse Android's ordered callback burst and let it install routes.
            delay(delayMs)
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
            if (recreateTun) {
                LogUtil.transport("Recreating TUN after transport change")
                recreateTunAfterTransportChange()
            } else {
                // The cooldown belongs to this exact Android Network. A real
                // LTE/Wi-Fi transition has a different Network identity and
                // must never be suppressed by a recent reload of the old one.
                lastSoftReloadNetwork = targetNetwork
                lastSoftNetworkReloadAt = SystemClock.elapsedRealtime()
                LogUtil.transport("Reconnecting tunnel after network event on $targetNetwork")
                reloadCoreKeepingTun("underlying network changed")
            }
        }
    }

    private fun applyUnderlyingNetwork(network: Network?) {
        try {
            if (!connectivity.bindProcessToNetwork(network)) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to bind process to $network")
            }
            setUnderlyingNetworks(network?.let { arrayOf(it) })
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to apply underlying network $network", e)
        }
    }

    private fun describeTransport(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "LTE/5G"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "other"
    }

    /**
     * Uses the actual link MTU when Android provides one. Cellular paths get a
     * conservative ceiling because encapsulated VPN traffic otherwise risks
     * black-holed fragments on some carriers. The feature is opt-in.
     */
    private fun updateAdaptiveMtu(linkProperties: LinkProperties): Boolean {
        if (!SettingsManager.isAdaptiveMtuEnabled()) return false
        val manual = SettingsManager.getVpnMtu()
        val linkMtu = linkProperties.mtu.takeIf { it in 1280..9_000 } ?: manual
        val cellularCeiling = if (lastTransportDescription == "LTE/5G") 1400 else manual
        val effective = minOf(manual, linkMtu, cellularCeiling).coerceAtLeast(1280)
        return SettingsManager.setRuntimeVpnMtu(effective)
    }

    private fun reloadCoreKeepingTun(
        reason: String,
        skipIfSelectedAlreadyRunning: Boolean = false,
    ) {
        synchronized(lifecycleLock) {
            if (!isRunning || !::mInterface.isInitialized || serviceState != ServiceState.RUNNING) return
            if (skipIfSelectedAlreadyRunning && CoreServiceManager.isSelectedProfileRunning()) return

            serviceState = ServiceState.RELOADING
            LogUtil.transport("Soft reload ($reason)")
            if (!CoreServiceManager.reloadCoreLoop(mInterface)) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Soft reload failed; stopping safely")
                stopAllServiceLocked(true)
            } else {
                serviceState = ServiceState.RUNNING
            }
        }
    }

    /**
     * Rebuilds the Android VPN interface after Wi-Fi/LTE handover.
     *
     * Keeping a TUN interface is normally less disruptive, but it cannot
     * migrate an already-open Telegram TCP connection to a different source
     * address. Closing it makes Android drop that stale flow immediately;
     * Telegram then reconnects through a freshly bound interface.
     */
    private fun recreateTunAfterTransportChange() {
        synchronized(lifecycleLock) {
            if (!isRunning || !::mInterface.isInitialized || serviceState != ServiceState.RUNNING) return
            val startedAt = System.nanoTime()
            serviceState = ServiceState.RELOADING
            LogUtil.transport(
                "Hard reconnect (recreate TUN), transport=$lastTransportDescription " +
                    "underlyingMtu=$lastUnderlyingMtu effectiveMtu=${SettingsManager.getEffectiveVpnMtu()}"
            )

            // Leave the network callback registered: it already points at the
            // new transport. Only the old interface and its sockets are reset.
            tun2SocksService?.stopTun2Socks()
            tun2SocksService = null
            // A Wi-Fi/LTE handover must keep the current priority route. Starting
            // again from P0 adds an unnecessary failed probe and reconnect delay.
            CoreServiceManager.stopCoreLoop(preservePriorityState = true)
            try {
                mInterface.close()
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to close old TUN", e)
            }
            isRunning = false

            if (!setupVpnService()) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to recreate TUN after transport change")
                stopAllServiceLocked(true)
                return
            }
            startService()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            LogUtil.transport("Hard reconnect completed in ${elapsedMs}ms, state=$serviceState")
        }
    }

    private fun stopAllService(isForced: Boolean = true) {
        synchronized(lifecycleLock) {
            stopAllServiceLocked(isForced)
        }
    }

    private fun stopAllServiceLocked(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        serviceState = ServiceState.STOPPING
        isRunning = false
        networkReloadJob?.cancel()
        networkReloadJob = null
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
        lastSoftNetworkReloadAt = 0L
        lastSoftReloadNetwork = null
        applyUnderlyingNetwork(null)

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        RootLanSharing.stopClientSharing(this)

        CoreServiceManager.stopCoreLoop()

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()

            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
            }
        }
        serviceState = ServiceState.STOPPED
    }
}
