package com.v2ray.ang.core

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionRefreshManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.service.CoreRootService
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.IDialerService
import com.v2ray.ang.util.FailureLogRecorder
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object CoreServiceManager {

    /** Native stopLoop can hang after overnight network death; never block forever. */
    private const val STOP_LOOP_TIMEOUT_MS = 8_000L
    /** Brief pause so priority-probe ports are released before the next startLoop. */
    private const val POST_STOP_DELAY_MS = 400L

    private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var currentConfigGuid: String? = null
    private var currentRuntimeConfig: String? = null
    @Volatile
    private var outboundLabels: Map<String, String> = emptyMap()
    @Volatile
    private var activeOutboundLabel: String = ""
    @Volatile
    private var activeRoutingModeLabel: String = ""
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    private var receiverRegistered = false
    private val coreLifecycleLock = ReentrantLock()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceActionScope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var suppressShutdownStop = false

    /**
     * Strong reference while the VPN/proxy service is alive. SoftReference previously
     * risked dropping the handle under memory pressure so stop/reload became no-ops.
     */
    @Volatile
    var serviceControl: ServiceControl? = null
        private set

    fun bindServiceControl(control: ServiceControl) {
        serviceControl = control
        val service = control.getService()
        CoreNativeManager.initCoreEnv(service)
        if (processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            processFinder = XrayProcessFinder(service)
            coreController.registerProcessFinder(processFinder)
        }
    }

    fun unbindServiceControl(control: ServiceControl) {
        if (serviceControl === control) {
            serviceControl = null
        }
    }

    /**
     * Starts the V2Ray service from a toggle action.
     * @param context The context from which the service is started.
     * @return True if the service was started successfully, false otherwise.
     */
    fun startVServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            showToast(context) { toast(R.string.app_tile_first_use) }
            return false
        }
        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${e.message}", e)
            showToast(context) { toast(e.message ?: e.javaClass.simpleName) }
            notifyStartFailure(context, e.message ?: e.javaClass.simpleName)
            return false
        }
        return true
    }

    /**
     * Starts the V2Ray service.
     * @param context The context from which the service is started.
     * @param guid The GUID of the server configuration to use (optional).
     */
    fun startVService(context: Context, guid: String? = null) {
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: startVService from ${context::class.java.simpleName}")

        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }

        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${e.message}", e)
            showToast(context) { toast(e.message ?: e.javaClass.simpleName) }
            notifyStartFailure(context, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Publishes the authoritative daemon state to every registered UI client. */
    fun notifyUiCurrentServiceState(context: Context) {
        val active = serviceControl?.isServiceActive() == true || coreController.isRunning
        MessageUtil.sendMsg2UI(
            context.applicationContext,
            if (active) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_NOT_RUNNING,
            "",
        )
    }

    private fun notifyStartFailure(context: Context, message: String) {
        MessageUtil.sendMsg2UI(context.applicationContext, AppConfig.MSG_STATE_START_FAILURE, message)
    }

    /** Toasty requires a prepared main looper, while service actions may come from workers. */
    private fun showToast(context: Context, action: Context.() -> Unit) {
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appContext.action()
        } else {
            mainHandler.post { appContext.action() }
        }
    }

    /**
     * Stops the V2Ray service.
     * @param context The context from which the service is stopped.
     */
    fun stopVService(context: Context) {
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /** Reloads the selected profile without destroying the VPN interface. */
    fun reloadVService(context: Context) {
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_RELOAD, "")
    }

    /** Rebuilds the VPN TUN so a new adaptive MTU takes effect. */
    fun requestTunRecreate() {
        serviceControl?.requestTunRecreate()
    }

    /** Applies a runtime route change even though the selected profile GUID is unchanged. */
    fun reloadPriorityRoute(): Boolean {
        val control = serviceControl
        if (control == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: No service for priority route reload")
            return false
        }
        LogUtil.transport("Requesting forced soft reload for priority route change")
        return control.reloadService(force = true)
    }

    /** Escalate a priority reload timeout instead of endlessly queuing soft reloads. */
    fun recoverStalledPriorityReload(): Boolean {
        val control = serviceControl ?: return false
        LogUtil.transport("Recovering stalled priority reload")
        return control.recoverStalledReload()
    }

    /** Performs a full service restart without relying on a guessed fixed delay. */
    fun restartVService(context: Context) {
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /** Measure delay through the currently running core, or -1 on failure. */
    fun measureRunningDelay(): Long {
        if (!coreController.isRunning) return -1L
        return try {
            val primary = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            if (primary >= 0) primary
            else coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: measureRunningDelay failed", e)
            -1L
        }
    }

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /** Route currently selected by Smart Priority. */
    fun getActiveOutboundLabel(): String = activeOutboundLabel

    /** Roscom routing profile currently applied by Smart Priority (`default` / `whitelist`). */
    fun getActiveRoutingModeLabel(): String = activeRoutingModeLabel

    /**
     * Smart Priority is the source of truth for the selected route. Native access
     * logs are not guaranteed to be delivered through CoreCallbackHandler, so the
     * notification must not depend on observing a GoLog line.
     */
    fun setActiveOutboundTag(routeTag: String) {
        if (routeTag.isBlank()) return
        val label = outboundLabels[routeTag] ?: routeTag
        val changed = activeOutboundLabel != label
        activeOutboundLabel = label
        if (changed) {
            LogUtil.transport("Active route is $label")
        }
        // Always refresh: soft-reload / FGS startup can leave "ожидание трафика"
        // on screen even when the in-memory label was already correct.
        NotificationManager.refreshConnectionDetails()
    }

    internal fun setActiveRoutingMode(mode: RoscomPriorityRouting.Mode?) {
        val label = when (mode) {
            RoscomPriorityRouting.Mode.FULL -> "default"
            RoscomPriorityRouting.Mode.WHITELIST -> "whitelist"
            null -> ""
        }
        val changed = activeRoutingModeLabel != label
        activeRoutingModeLabel = label
        if (changed && label.isNotEmpty()) {
            runCatching { LogUtil.transport("Active routing mode is $label") }
        }
        NotificationManager.refreshConnectionDetails()
    }

    fun isSelectedProfileRunning(): Boolean {
        return coreController.isRunning && currentConfigGuid == MmkvManager.getSelectServer()
    }

    /**
     * Starts the context service for V2Ray.
     * Chooses between VPN service or Proxy-only service based on user settings.
     * @param context The context from which the service is started.
     * @throws IllegalStateException if the core is already running, no server is selected,
     *   server config cannot be decoded, or server configuration is invalid.
     * @throws Exception if the foreground service fails to start.
     */
    @Throws(Exception::class)
    private fun startContextService(context: Context) {
        if (coreController.isRunning) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            notifyUiCurrentServiceState(context)
            return
        }

        val guid = MmkvManager.getSelectServer()
            ?: run {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: No server selected")
                error(context.getString(R.string.app_tile_first_use))
            }

        val config = MmkvManager.decodeServerConfig(guid)
            ?: run {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to decode server config")
                error(context.getString(R.string.toast_config_file_invalid))
            }

        if (!config.configType.isComplexType()
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Invalid server configuration")
            error(context.getString(R.string.toast_config_file_invalid))
        }

        // refresh socks port when enabled dynamic socks port
        SettingsManager.refreshRuntimeSocksPort()

//        val result = V2rayConfigUtil.getV2rayConfig(context, guid)
//        if (!result.status) error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })

        if (config.insecure == true) {
            showToast(context) { toastError(R.string.toast_allow_insecure_deprecated) }
            showToast(context) { toastError(R.string.toast_allow_insecure_deprecated) }
        }

        val isRootMode = SettingsManager.isRootMode()
        if (isRootMode && !RootManager.isRootAvailable()) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: root mode requires root but none available")
            error(context.getString(R.string.toast_root_required))
        }

        val intent = if (isRootMode) {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting Root service")
            Intent(context.applicationContext, CoreRootService::class.java)
        } else if (SettingsManager.isVpnMode()) {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting VPN service")
            Intent(context.applicationContext, CoreVpnService::class.java)
        } else {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting Proxy service")
            Intent(context.applicationContext, CoreProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Missing permission to start foreground service", e)
            throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Foreground service start not allowed", e)
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
            throw e
        }
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        return coreLifecycleLock.withLock { startCoreLoopLocked(vpnInterface) }
    }

    private fun startCoreLoopLocked(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (coreController.isRunning) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        return try {
            doStartCoreLoop(service, vpnInterface, prepareCoreStart(service))
            suppressShutdownStop = false
            SubscriptionRefreshManager.startVpnBackgroundRefresh()
            true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            NotificationManager.cancelNotification()
            false
        }
    }

    @Throws(Exception::class)
    private fun prepareCoreStart(service: Service): PreparedCoreStart {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        LogUtil.d(AppConfig.TAG, result.content)
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }
        val runtimeContent = PriorityFailoverManager.prepareRuntimeConfig(guid, result.content)
        return PreparedCoreStart(guid, config, runtimeContent)
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(
        service: Service,
        vpnInterface: ParcelFileDescriptor?,
        prepared: PreparedCoreStart,
    ) {
        val guid = prepared.guid
        val config = prepared.profile

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")

        registerReceiverIfNeeded(service)
        var tunFd = vpnInterface?.fd ?: 0
        val dialerAddr = if (config.browserDialerMode.isNullOrEmpty()) {
            ""
        } else {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        // Seed route label BEFORE the first FGS notification so the shade never
        // sticks on "Маршрут: ожидание трафика" while the core is already up.
        outboundLabels = buildOutboundLabels(prepared.content)
        PriorityFailoverManager.currentRouteTag()?.let(::setActiveOutboundTag)

        NotificationManager.showNotification(config)
        CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        coreController.startLoop(prepared.content, tunFd)

        if (!coreController.isRunning) {
            error("Core failed to start")
        }

        currentConfig = config
        currentConfigGuid = guid
        currentRuntimeConfig = prepared.content
        // Soft reload keeps the previous label until onCoreStarted updates it.

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        if (config.browserDialerMode == "OkHttp") {
            browserDialer = DialerNativeService()
            browserDialer!!.start(service, dialerAddr)
        }

        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        PriorityFailoverManager.onCoreStarted(service)
        // Rebuild foreground notification after the active route label is known.
        NotificationManager.showNotification(currentConfig)
        FailureLogRecorder.markSessionActive("core_started:${config.remarks}")
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    private fun registerReceiverIfNeeded(service: Service) {
        if (receiverRegistered) return
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(service, mMsgReceive, filter, Utils.receiverFlags())
        receiverRegistered = true
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(preservePriorityState: Boolean = false): Boolean {
        return coreLifecycleLock.withLock {
            stopCoreLoopLocked(
                // A preserved priority state means this is an internal core recycle
                // (Wi-Fi/LTE handover or failed soft-reload recovery), not a user stop.
                // Keep the foreground notification and its cumulative battery baseline
                // alive so the displayed usage does not restart from zero.
                notifyUi = !preservePriorityState,
                clearPriorityState = !preservePriorityState,
            )
        }
    }

    /**
     * Stops the old core and starts the selected profile on the same TUN descriptor.
     * Existing app-side sockets stay attached to Android's VPN interface and reconnect
     * as soon as the new core is ready.
     */
    fun reloadCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        return coreLifecycleLock.withLock {
            val service = getService() ?: return@withLock false
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Soft-reloading core")

            // Build the complete runtime JSON before touching the working core.
            // Structural/DNS/geodata failures therefore leave the old tunnel alive.
            val next = try {
                prepareCoreStart(service)
            } catch (e: Exception) {
                val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Reload preparation failed: $message", e)
                PriorityFailoverManager.rollbackPendingSwitch()
                PriorityFailoverManager.notifyReloadAborted()
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
                // The current core was not touched, so report the reload as
                // safely handled and keep the existing VPN connection alive.
                return@withLock true
            }

            val previous = currentRuntimeConfig?.let { content ->
                val guid = currentConfigGuid
                val profile = currentConfig
                if (guid != null && profile != null) PreparedCoreStart(guid, profile, content) else null
            }
            if (!stopCoreLoopLocked(notifyUi = false)) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Old core did not stop; aborting reload")
                PriorityFailoverManager.rollbackPendingSwitch()
                PriorityFailoverManager.notifyReloadAborted()
                return@withLock false
            }
            waitForPortRelease()
            var lastError: Exception? = null
            try {
                doStartCoreLoop(service, vpnInterface, next)
                suppressShutdownStop = false
                return@withLock true
            } catch (e: Exception) {
                lastError = e
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: New core failed; restoring previous config", e)
            }

            PriorityFailoverManager.rollbackPendingSwitch()
            if (previous != null && lastError?.let(::isPortBindError) != true) {
                try {
                    doStartCoreLoop(service, vpnInterface, previous)
                    suppressShutdownStop = false
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_RUNNING, "")
                    return@withLock true
                } catch (rollbackError: Exception) {
                    lastError = rollbackError
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Previous config rollback failed", rollbackError)
                }
            }

            if (lastError?.let(::isPortBindError) == true) {
                PriorityFailoverManager.rotateProbePortsForReload()
            }
            waitForPortRelease()
            try {
                val rebuilt = prepareCoreStart(service)
                doStartCoreLoop(service, vpnInterface, rebuilt)
                suppressShutdownStop = false
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_RUNNING, "")
                return@withLock true
            } catch (recoveryError: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Rebuilt config recovery failed", recoveryError)
            }

            PriorityFailoverManager.notifyReloadAborted()
            val message = lastError?.message?.takeUnless { it.isBlank() }
                ?: lastError?.javaClass?.simpleName
                ?: "reload failed"
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            false
        }
    }

    private fun stopCoreLoopLocked(
        notifyUi: Boolean,
        clearPriorityState: Boolean = notifyUi,
    ): Boolean {
        val service = getService() ?: return false

        suppressShutdownStop = true
        val stopped = !coreController.isRunning || stopLoopWithTimeout()

        // Close existing browser dialer
        CoreNativeManager.reconcileBrowserDialer("")
        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }

        if (notifyUi) {
            SubscriptionRefreshManager.stopVpnBackgroundRefresh()
            PriorityFailoverManager.stop(clearState = clearPriorityState)
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
            NotificationManager.cancelNotification()
            FailureLogRecorder.markSessionClean("core_stopped")
        }

        if (notifyUi && receiverRegistered) {
            try {
                service.unregisterReceiver(mMsgReceive)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
            } finally {
                receiverRegistered = false
            }
            currentRuntimeConfig = null
            outboundLabels = emptyMap()
            activeOutboundLabel = ""
            activeRoutingModeLabel = ""
        }

        return stopped
    }

    /**
     * stopLoop is synchronous and can hang when sockets are stuck after Doze / overnight
     * network loss. Bound the wait so VPN teardown and the UI never block forever.
     */
    private fun stopLoopWithTimeout(): Boolean {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            // Explicit Callable avoids Java overload resolution selecting
            // submit(Runnable), whose Future.get() result is typed as Any?.
            val future = executor.submit(Callable<Boolean> {
                try {
                    coreController.stopLoop()
                    !coreController.isRunning
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                    false
                }
            })
            future.get(STOP_LOOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            LogUtil.e(
                AppConfig.TAG,
                "StartCore-Manager: stopLoop timed out after ${STOP_LOOP_TIMEOUT_MS}ms",
            )
            false
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
            false
        } finally {
            executor.shutdown()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                LogUtil.w(
                    AppConfig.TAG,
                    "StartCore-Manager: stopLoop thread still running after shutdown; interrupting",
                )
                executor.shutdownNow()
            }
        }
    }

    private fun waitForPortRelease() {
        try {
            Thread.sleep(POST_STOP_DELAY_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun isPortBindError(e: Exception): Boolean {
        val message = e.message ?: return false
        return message.contains("failed to listen", ignoreCase = true) ||
            message.contains("bind:", ignoreCase = true) ||
            message.contains("operation not permitted", ignoreCase = true)
    }

    private data class PreparedCoreStart(
        val guid: String,
        val profile: ProfileItem,
        val content: String,
    )

    /**
     * Receives access-log lines forwarded by the native Xray wrapper. Unlike
     * outbound counters, access logs originate from a real TUN connection and
     * do not include burstObservatory health probes.
     */
    fun observeAccessLog(accessLog: String) {
        val routeTag = Regex("""\[(?:auto-proxy-in|chain-in-s\d+) -> (route-p[^\]]+)]""")
            .find(accessLog)
            ?.groupValues
            ?.getOrNull(1)
            ?: return
        // Real TUN traffic confirms the outbound in use after a priority reload.
        runCatching { setActiveOutboundTag(routeTag) }
            .onFailure { LogUtil.e(AppConfig.TAG, "StartCore-Manager: observeAccessLog failed", it) }
    }

    private fun buildOutboundLabels(runtimeConfig: String): Map<String, String> = runCatching {
        JsonParser.parseString(runtimeConfig).asJsonObject
            .getAsJsonArray("outbounds")
            .mapNotNull { element ->
                val outbound = element.asJsonObject
                val tag = outbound.get("tag")?.asString.orEmpty()
                if (!tag.startsWith("route-p")) return@mapNotNull null
                val priority = Regex("""route-p0*(\d+)""")
                    .find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val endpoint = outboundEndpointLabel(outbound)
                tag to if (endpoint.isBlank()) {
                    "[P$priority] $tag"
                } else {
                    "[P$priority] $endpoint"
                }
            }
            .toMap()
    }.getOrElse {
        LogUtil.w(AppConfig.TAG, "StartCore-Manager: Failed to map automatic routes", it)
        emptyMap()
    }

    /** Best-effort host:port for notification text; never throws on WG arrays / vnext. */
    private fun outboundEndpointLabel(outbound: com.google.gson.JsonObject): String {
        val settings = outbound.getAsJsonObject("settings") ?: return ""
        settings.get("address")?.let { addressElement ->
            val host = when {
                addressElement.isJsonPrimitive -> addressElement.asString
                addressElement.isJsonArray -> addressElement.asJsonArray
                    .firstOrNull { it.isJsonPrimitive }
                    ?.asString
                    ?.substringBefore('/')
                else -> null
            }.orEmpty()
            if (host.isNotBlank()) {
                val port = settings.get("port")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                return if (port.isBlank()) host else "$host:$port"
            }
        }
        settings.getAsJsonArray("vnext")
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.let { vnext ->
                val host = vnext.get("address")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val port = vnext.get("port")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (host.isNotBlank()) {
                    return if (port.isBlank()) host else "$host:$port"
                }
            }
        settings.getAsJsonArray("servers")
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.let { server ->
                val host = server.get("address")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val port = server.get("port")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (host.isNotBlank()) {
                    return if (port.isBlank()) host else "$host:$port"
                }
            }
        settings.getAsJsonArray("peers")
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("endpoint")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return ""
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay() {
        if (coreController.isRunning == false) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":") ?: "empty message"
            }
            if (time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
            }

            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }

            // Send a single final message (with IP when available) so the UI does not flicker.
            val content = if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip -> "$result\n$ip" } ?: result
            } else {
                result
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, content)
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            if (suppressShutdownStop) {
                return 0
            }
            FailureLogRecorder.breadcrumb("CORE_CALLBACK_SHUTDOWN", forceDisk = true)
            val control = serviceControl ?: return -1
            return try {
                // Native callbacks must not tear down on the calling thread if it can
                // be the main looper; stop is always dispatched to IO.
                serviceActionScope.launch { control.stopService() }
                0
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop service", e)
                -1
            }
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            if (s.isNullOrBlank()) return 0
            when (l) {
                1L -> observeAccessLog(s)
                2L -> PriorityFailoverManager.onTrafficError(s)
            }
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(private val context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                if (uid >= 0 && MmkvManager.decodeSettingsBool(AppConfig.PREF_CONNECTION_DIAGNOSTICS_ENABLED) == true) {
                    val packages = context.packageManager.getPackagesForUid(uid.toInt())
                    val packageName = packages?.firstOrNull().orEmpty()
                    val label = runCatching {
                        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
                    }.getOrDefault(packageName.ifBlank { "Неизвестное приложение" })
                    val destination = "$destIP:$destPort"
                    ConnectionJournal.record(label, packageName, network, destination)
                    LogUtil.transport("APP_FLOW: $label ($packageName) → $destination")
                }
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
                //LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid,${PackageUidResolver.uidToPackageName(uid.toString())}")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val control = serviceControl ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (control.isServiceActive()) {
                        MessageUtil.sendMsg2UI(control.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(control.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    // BroadcastReceiver runs on the main thread; never call stopLoop here.
                    serviceActionScope.launch {
                        control.stopService()
                    }
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    val appContext = control.getService().applicationContext
                    serviceActionScope.launch {
                        control.stopService()
                        // stopSelf() is asynchronous. Starting immediately can
                        // deliver the new intent to the dying instance before
                        // onDestroy removes root rules/closes the old TUN.
                        val deadline = SystemClock.elapsedRealtime() + 15_000L
                        while (serviceControl === control &&
                            SystemClock.elapsedRealtime() < deadline
                        ) {
                            delay(50L)
                        }
                        if (serviceControl === control) {
                            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Service teardown timed out before restart")
                        }
                        startVService(appContext)
                    }
                }

                AppConfig.MSG_STATE_RELOAD -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Soft reload service")
                    serviceActionScope.launch {
                        control.reloadService()
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }

                AppConfig.MSG_BATTERY_STATS_SETTING_CHANGED -> {
                    NotificationManager.updateBatteryUsageTracking()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    PriorityFailoverManager.onScreenStateChanged(interactive = false)
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    PriorityFailoverManager.onScreenStateChanged(interactive = true)
                }

                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val idle = ctx?.getSystemService(PowerManager::class.java)?.isDeviceIdleMode == true
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Device idle mode=$idle")
                    if (!idle) {
                        PriorityFailoverManager.onDeviceExitedIdle()
                    }
                }
            }
        }
    }
}
