package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager

/**
 * Debug-only diagnostics: verbose logs, connection journal, failure breadcrumbs.
 * Release builds never expose related settings and always clamp log levels to production values.
 */
object DebugDiagnostics {
    private const val BOOTSTRAP_KEY = "pref_debug_diagnostics_bootstrapped"

    val isAvailable: Boolean
        get() = BuildConfig.DEBUG

    /** One-time defaults when installing/running a debug APK from Android Studio. */
    fun applyDefaultsOnStartup() {
        if (!isAvailable) return
        if (MmkvManager.decodeSettingsBool(BOOTSTRAP_KEY, false)) return
        MmkvManager.encodeSettings(AppConfig.PREF_LOGLEVEL, "debug")
        MmkvManager.encodeSettings(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL, "debug")
        MmkvManager.encodeSettings(AppConfig.PREF_CONNECTION_DIAGNOSTICS_ENABLED, true)
        MmkvManager.encodeSettings(AppConfig.PREF_FAILURE_LOG_ENABLED, true)
        MmkvManager.encodeSettings(BOOTSTRAP_KEY, true)
        LogUtil.refreshLogLevel()
        FailureLogRecorder.refreshEnabled()
    }

    fun effectiveCoreLogLevel(): String =
        if (isAvailable) {
            MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL, "debug") ?: "debug"
        } else {
            "warning"
        }

    fun effectiveHevLogLevel(): String =
        if (isAvailable) {
            MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL, "debug") ?: "debug"
        } else {
            "warn"
        }

    fun isConnectionDiagnosticsEnabled(): Boolean =
        isAvailable && MmkvManager.decodeSettingsBool(AppConfig.PREF_CONNECTION_DIAGNOSTICS_ENABLED, false)

    fun isFailureLogEnabled(): Boolean =
        isAvailable && MmkvManager.decodeSettingsBool(AppConfig.PREF_FAILURE_LOG_ENABLED, false)
}
