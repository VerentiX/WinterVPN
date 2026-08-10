package com.v2ray.ang.handler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.util.AppBatteryUsageEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object NotificationManager {
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1
    private const val NOTIFICATION_PENDING_INTENT_RESTART_V2RAY = 2
    private const val BATTERY_QUERY_INTERVAL_MS = 15 * 60_000L
    private const val BATTERY_INITIAL_QUERY_DELAY_MS = 60_000L

    private var mBuilder: NotificationCompat.Builder? = null
    private var mNotificationManager: NotificationManager? = null
    private var batteryUsageJob: Job? = null
    @Volatile private var batteryUsageMah: Double? = null
    @Volatile private var batteryFullCapacityMah: Double? = null
    @Volatile private var batteryUsageStartedAt = 0L
    @Volatile private var batteryStatsAvailable: Boolean? = null

    /**
     * Shows the notification.
     * @param currentConfig The current profile configuration.
     */
    fun showNotification(currentConfig: ProfileItem?) {
        val service = getService() ?: return

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val startMainIntent = Intent(service, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(service, NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent, flags)

        val stopV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        stopV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        stopV2RayIntent.putExtra("key", AppConfig.MSG_STATE_STOP)
        val stopV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_STOP_V2RAY, stopV2RayIntent, flags)

        val restartV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        restartV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        restartV2RayIntent.putExtra("key", AppConfig.MSG_STATE_RESTART)
        val restartV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_RESTART_V2RAY, restartV2RayIntent, flags)

        val channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel()
            } else {
                ""
            }

        val appName = service.getString(R.string.app_name)
        val profileName = currentConfig?.remarks.orEmpty()
        val notificationTitle = if (profileName.isBlank()) {
            appName
        } else {
            service.getString(R.string.vpn_notification_title, appName, profileName)
        }

        mBuilder = NotificationCompat.Builder(service, channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(notificationTitle)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentText(connectionDetails())
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_delete_24dp,
                service.getString(R.string.notification_action_stop_v2ray),
                stopV2RayPendingIntent
            )
            .addAction(
                R.drawable.ic_restore_24dp,
                service.getString(R.string.title_service_restart),
                restartV2RayPendingIntent
            )

        service.startForeground(NOTIFICATION_ID, mBuilder?.build())
        updateBatteryUsageTracking()
    }

    /**
     * Cancels the notification.
     */
    fun cancelNotification() {
        val service = getService()
        service?.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        mBuilder = null
        stopBatteryUsageTracking()
        mNotificationManager = null
    }

    /**
     * Creates a notification channel for Android O and above.
     * @return The channel ID.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = AppConfig.RAY_NG_CHANNEL_ID
        val service = getService() ?: return AppConfig.RAY_NG_CHANNEL_ID
        val channelName = service.getString(
            R.string.vpn_notification_channel_name,
            service.getString(R.string.app_name)
        )
        val chan = NotificationChannel(
            channelId,
            channelName, NotificationManager.IMPORTANCE_LOW
        )
        chan.lightColor = Color.DKGRAY
        chan.importance = NotificationManager.IMPORTANCE_LOW
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        getNotificationManager()?.apply {
            deleteNotificationChannel("RAY_NG_M_CH_ID")
            deleteNotificationChannel("ZEUSGATE_VPN_SERVICE_V2")
            deleteNotificationChannel("ZETYAVPN_VPN_SERVICE_V3")
            createNotificationChannel(chan)
        }
        return channelId
    }

    /** Refreshes route/MTU details immediately after Xray accepts a new flow. */
    fun refreshConnectionDetails() {
        val builder = mBuilder ?: return
        val text = connectionDetails()
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        builder.setContentText(text)
        getNotificationManager()?.notify(NOTIFICATION_ID, builder.build())
    }

    /** Apply the opt-in energy counter immediately without restarting the VPN. */
    fun updateBatteryUsageTracking() {
        val enabled = MmkvManager.decodeSettingsBool(
            AppConfig.PREF_NOTIFICATION_SHOW_BATTERY_USAGE,
            false,
        )
        if (!enabled) {
            stopBatteryUsageTracking()
            refreshConnectionDetails()
            return
        }
        if (batteryUsageJob?.isActive == true) {
            refreshConnectionDetails()
            return
        }
        val service = getService() ?: return
        batteryUsageJob = CoroutineScope(Dispatchers.IO).launch {
            val baseline = AppBatteryUsageEstimator.readSnapshot(service)
            batteryStatsAvailable = baseline != null
            batteryFullCapacityMah = AppBatteryUsageEstimator.estimateFullCapacityMah(service)
            batteryUsageStartedAt = SystemClock.elapsedRealtime()
            batteryUsageMah = 0.0
            refreshConnectionDetails()
            if (baseline == null) return@launch
            var nextDelayMs = BATTERY_INITIAL_QUERY_DELAY_MS
            while (isActive) {
                delay(nextDelayMs)
                nextDelayMs = BATTERY_QUERY_INTERVAL_MS
                val current = AppBatteryUsageEstimator.readSnapshot(service)
                val estimate = current?.let { AppBatteryUsageEstimator.estimateDeltaMah(baseline, it) }
                if (estimate == null) {
                    batteryStatsAvailable = false
                    batteryUsageMah = null
                } else {
                    batteryStatsAvailable = true
                    batteryUsageMah = estimate
                }
                refreshConnectionDetails()
            }
        }
    }

    private fun stopBatteryUsageTracking() {
        batteryUsageJob?.cancel()
        batteryUsageJob = null
        batteryUsageMah = null
        batteryFullCapacityMah = null
        batteryUsageStartedAt = 0L
        batteryStatsAvailable = null
    }

    private fun batteryUsageText(): String? {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_NOTIFICATION_SHOW_BATTERY_USAGE) != true) {
            return null
        }
        val service = getService() ?: return null
        val usedMah = batteryUsageMah
        return when {
            batteryStatsAvailable == false -> service.getString(
                R.string.notification_battery_usage_unavailable
            )
            usedMah == null -> service.getString(R.string.notification_battery_usage_waiting)
            batteryFullCapacityMah != null -> {
                val percent = usedMah / batteryFullCapacityMah!! * 100.0
                val hours = (SystemClock.elapsedRealtime() - batteryUsageStartedAt) / 3_600_000.0
                val rate = if (hours > 0.0) percent / hours else 0.0
                service.getString(R.string.notification_battery_usage, percent, usedMah, rate)
            }
            else -> service.getString(R.string.notification_battery_usage_mah, usedMah)
        }
    }

    private fun connectionDetails(): String {
        val parts = ArrayList<String>(4)
        batteryUsageText()?.let(parts::add)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_NOTIFICATION_SHOW_MTU) == true) {
            parts.add("MTU ${SettingsManager.getEffectiveVpnMtu()}")
        }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_NOTIFICATION_SHOW_ACTIVE_OUTBOUND) == true) {
            val route = CoreServiceManager.getActiveOutboundLabel()
            parts.add(if (route.isBlank()) "Маршрут: ожидание трафика" else "Маршрут: $route")
        }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_NOTIFICATION_SHOW_ROUTING_MODE) == true) {
            val service = getService()
            val mode = CoreServiceManager.getActiveRoutingModeLabel()
            if (service != null && mode.isNotBlank()) {
                val label = when (mode) {
                    "whitelist" -> service.getString(R.string.notification_routing_mode_whitelist)
                    "default" -> service.getString(R.string.notification_routing_mode_default)
                    else -> mode
                }
                parts.add(service.getString(R.string.notification_routing_mode, label))
            }
        }
        return parts.joinToString(" · ")
    }

    /**
     * Gets the notification manager.
     * @return The notification manager.
     */
    private fun getNotificationManager(): NotificationManager? {
        if (mNotificationManager == null) {
            val service = getService() ?: return null
            mNotificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        return mNotificationManager
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    private fun getService(): Service? {
        return CoreServiceManager.serviceControl?.getService()
    }
}
