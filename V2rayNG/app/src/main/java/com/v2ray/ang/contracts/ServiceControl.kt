package com.v2ray.ang.contracts

import android.app.Service

interface ServiceControl {
    /**
     * Gets the service instance.
     * @return The service instance.
     */
    fun getService(): Service

    /** True while the service owns, starts, or reloads its networking stack. */
    fun isServiceActive(): Boolean

    /** True when TUN/hev (or equivalent) are up and a soft reload can run. */
    fun isDataplaneReady(): Boolean

    /**
     * Starts the service.
     */
    fun startService()

    /**
     * Stops the service.
     */
    fun stopService()

    /**
     * Reloads only the proxy core while keeping the surrounding service alive.
     * @return true only when the reload was actually accepted for execution.
     */
    fun reloadService(force: Boolean = false): Boolean

    /** Recover a reload that was accepted but never completed. */
    fun recoverStalledReload(): Boolean

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    fun vpnProtect(socket: Int): Boolean

    /**
     * Rebuilds the Android VPN interface so a new TUN MTU takes effect,
     * or restarts hev when the SOCKS inbound port changes.
     * No-op for non-VPN service modes.
     */
    fun requestTunRecreate()

    /**
     * Drop TUN-side TCP (Telegram MTProto) after a Wi-Fi↔LTE source-address
     * change without waiting for Smart Priority probes. TUN itself stays up.
     */
    fun resetDataplaneForTransport()
}
