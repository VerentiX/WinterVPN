package com.v2ray.ang.service

import android.content.Context
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.RoscomPriorityRouting
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.DebugDiagnostics
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * Manages the tun2socks process that handles VPN traffic
 */
class TProxyService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val isRunningProvider: () -> Boolean,
    private val restartCallback: () -> Unit
) : Tun2SocksControl {
    companion object {
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService()

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyIsServiceRunning(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        /**
         * One JNI thread for Start and Stop. A second thread calling Start
         * while Stop is still in pthread_join deadlocks on hev's mutex, and
         * establish() of a new TUN can reuse the old fd while the worker lives.
         */
        private val hevJniExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hev-jni").apply { isDaemon = true }
        }
        private val hevJniGate = Any()
        @Volatile
        private var pendingStop: java.util.concurrent.Future<Boolean>? = null

        /**
         * Quit+join hev. After [wakeAfterMs] invoke [onStuck] (close TUN so the
         * worker sees EOF). Do not return until JNI Stop actually finished:
         * establishing a new TUN while the worker still holds the old fd number
         * blackholes Telegram.
         */
        fun stopNativeTunnel(
            wakeAfterMs: Long = 2_000L,
            joinGiveUpMs: Long = 8_000L,
            onStuck: (() -> Unit)? = null,
        ): Boolean {
            val future = synchronized(hevJniGate) {
                val existing = pendingStop
                if (existing != null && !existing.isDone) {
                    existing
                } else {
                    hevJniExecutor.submit(
                        java.util.concurrent.Callable {
                            try {
                                stopNativeTunnelImmediate()
                                true
                            } finally {
                                synchronized(hevJniGate) { pendingStop = null }
                            }
                        },
                    ).also { pendingStop = it }
                }
            }
            return try {
                try {
                    future.get(wakeAfterMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    true
                } catch (_: java.util.concurrent.TimeoutException) {
                    LogUtil.e(
                        AppConfig.TAG,
                        "hev-socks5-tunnel join still running after ${wakeAfterMs}ms",
                    )
                    onStuck?.invoke()
                    try {
                        future.get(joinGiveUpMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                        true
                    } catch (_: java.util.concurrent.TimeoutException) {
                        LogUtil.e(
                            AppConfig.TAG,
                            "hev join still running after TUN close; refusing a second worker",
                        )
                        false
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "hev-socks5-tunnel stop failed", e)
                false
            }
        }

        fun isHevStopPending(): Boolean {
            val stop = pendingStop
            return stop != null && !stop.isDone
        }

        fun startNativeTunnel(configPath: String, fd: Int) {
            if (isHevStopPending()) {
                throw IllegalStateException("hev Stop still joining; refusing Start")
            }
            hevJniExecutor.submit {
                TProxyStartService(configPath, fd)
            }.get()
        }

        private fun stopNativeTunnelImmediate() {
            try {
                TProxyStopService()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to stop native hev-socks5-tunnel", e)
            }
        }

        fun isNativeTunnelRunning(): Boolean {
            if (isHevStopPending()) return true
            return try {
                TProxyIsServiceRunning()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to query native hev-socks5-tunnel state", e)
                false
            }
        }

        private const val HEV_START_CONFIRM_ATTEMPTS = 6
        private const val HEV_START_CONFIRM_DELAY_MS = 25L
    }

    /**
     * Restart hev on the same TUN fd. JNI TProxyStartService already
     * quit+joins the worker if it is running, then starts a new one.
     * Do not wrap Start in a timed executor: interrupting JNI during
     * tcp_slowtmr/pbuf_free aborted the VPN process (SIGABRT).
     */
    override fun startTun2Socks(): Boolean {
        val configContent = buildConfig()
        val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml").apply {
            writeText(configContent)
        }
        LogUtil.d(AppConfig.TAG, "HevSocks5Tunnel Config content:\n$configContent")

        return try {
            startNativeTunnel(configFile.absolutePath, vpnInterface.fd)
            repeat(HEV_START_CONFIRM_ATTEMPTS) { attempt ->
                if (isNativeTunnelRunning()) {
                    LogUtil.transport("HevSocks5Tunnel started on fd=${vpnInterface.fd}")
                    return true
                }
                if (attempt + 1 < HEV_START_CONFIRM_ATTEMPTS) {
                    Thread.sleep(HEV_START_CONFIRM_DELAY_MS)
                }
            }
            LogUtil.e(AppConfig.TAG, "HevSocks5Tunnel worker exited immediately after start")
            false
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "HevSocks5Tunnel exception: ${e.message}", e)
            false
        }
    }

    private fun buildConfig(): String {
        val routingMode = CoreServiceManager.getActiveRoutingMode()
        val socksPort = RoscomPriorityRouting.tunSocksPort(
            routingMode,
            SettingsManager.getSocksPort(),
        )
        LogUtil.transport(
            "Hev SOCKS port=$socksPort mode=${routingMode ?: "FULL"} " +
                "(p0=mixed, p5+=${RoscomPriorityRouting.INBOUND_WHITELIST})"
        )
        val socksUsername = SettingsManager.getSocksUsername()
        val socksPassword = SettingsManager.getSocksPassword()
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val escapedSocksUsername = socksUsername?.replace("'", "''")
        val escapedSocksPassword = socksPassword?.replace("'", "''")
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${SettingsManager.getEffectiveVpnMtu()}")
            appendLine("  ipv4: ${vpnConfig.ipv4Client}")

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED)) {
                appendLine("  ipv6: '${vpnConfig.ipv6Client}'")
            }

            appendLine("socks5:")
            appendLine("  port: ${socksPort}")
            appendLine("  address: ${AppConfig.LOOPBACK}")
            appendLine("  udp: 'udp'")
            if (escapedSocksUsername != null && escapedSocksPassword != null) {
                appendLine("  username: '${escapedSocksUsername}'")
                appendLine("  password: '${escapedSocksPassword}'")
            }

            // Read-write timeout settings
            val timeoutSetting = MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) ?: AppConfig.HEVTUN_RW_TIMEOUT
            val parts = timeoutSetting.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val tcpTimeout = parts.getOrNull(0)?.toIntOrNull() ?: 300
            val udpTimeout = parts.getOrNull(1)?.toIntOrNull() ?: 60

            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: ${tcpTimeout * 1000}")
            appendLine("  udp-read-write-timeout: ${udpTimeout * 1000}")
            appendLine("  log-level: ${DebugDiagnostics.effectiveHevLogLevel()}")
        }
    }

    /**
     * Stops the tun2socks process
     */
    override fun stopTun2Socks() {
        LogUtil.i(AppConfig.TAG, "TProxyStopService...")
        stopNativeTunnel()
    }
}
