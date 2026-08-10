package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Refreshes subscriptions only when their own auto-update interval is due.
 * Also runs a light check loop while VPN/core is running.
 */
object SubscriptionRefreshManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    @Volatile
    private var vpnRefreshJob: Job? = null

    /** Minimum wait between due-checks while VPN is on. */
    private const val VPN_POLL_MIN_MS = 5 * 60 * 1000L
    private const val VPN_POLL_MAX_MS = 30 * 60 * 1000L

    suspend fun refreshDueSubscriptions(): SubscriptionUpdateResult {
        return refreshMutex.withLock {
            withContext(Dispatchers.IO) {
                val due = collectDueSubscriptions()
                if (due.isEmpty()) {
                    LogUtil.i(AppConfig.TAG, "SubscriptionRefreshManager: nothing due yet")
                    return@withContext SubscriptionUpdateResult()
                }
                LogUtil.i(
                    AppConfig.TAG,
                    "SubscriptionRefreshManager: refreshing ${due.size} due subscription(s)"
                )
                due.fold(SubscriptionUpdateResult()) { acc, subscription ->
                    acc + AngConfigManager.updateConfigViaSub(subscription)
                }.also { result ->
                    LogUtil.i(
                        AppConfig.TAG,
                        "SubscriptionRefreshManager: refresh done " +
                            "success=${result.successCount} failure=${result.failureCount} " +
                            "skip=${result.skipCount} configs=${result.configCount}"
                    )
                }
            }
        }
    }

    fun refreshOnAppOpen(onComplete: ((SubscriptionUpdateResult) -> Unit)? = null) {
        scope.launch {
            val result = refreshDueSubscriptions()
            if (onComplete != null) {
                withContext(Dispatchers.Main) {
                    onComplete(result)
                }
            }
        }
    }

    fun startVpnBackgroundRefresh() {
        if (vpnRefreshJob?.isActive == true) return

        vpnRefreshJob = scope.launch {
            LogUtil.i(AppConfig.TAG, "SubscriptionRefreshManager: VPN due-check loop started")
            while (isActive) {
                refreshDueSubscriptions()
                val waitMs = nextDueDelayMs().coerceIn(VPN_POLL_MIN_MS, VPN_POLL_MAX_MS)
                LogUtil.d(AppConfig.TAG, "SubscriptionRefreshManager: next due-check in ${waitMs / 1000}s")
                delay(waitMs)
            }
        }
    }

    fun stopVpnBackgroundRefresh() {
        vpnRefreshJob?.cancel()
        vpnRefreshJob = null
        LogUtil.i(AppConfig.TAG, "SubscriptionRefreshManager: VPN due-check loop stopped")
    }

    private fun collectDueSubscriptions(): List<SubscriptionCache> {
        val now = System.currentTimeMillis()
        return MmkvManager.decodeSubscriptions().filter { cache ->
            isDue(cache.subscription.autoUpdate, cache.subscription.lastUpdated, cache.subscription.updateInterval, now)
        }
    }

    private fun nextDueDelayMs(): Long {
        val now = System.currentTimeMillis()
        var soonest = VPN_POLL_MAX_MS
        MmkvManager.decodeSubscriptions().forEach { cache ->
            val sub = cache.subscription
            if (!sub.autoUpdate) return@forEach
            val intervalMs = intervalMs(sub.updateInterval)
            val dueAt = if (sub.lastUpdated <= 0L) now else sub.lastUpdated + intervalMs
            val remain = (dueAt - now).coerceAtLeast(0L)
            if (remain < soonest) soonest = remain
        }
        return if (soonest <= 0L) VPN_POLL_MIN_MS else soonest
    }

    private fun isDue(autoUpdate: Boolean, lastUpdated: Long, updateIntervalMinutes: Long, now: Long): Boolean {
        if (!autoUpdate) return false
        if (lastUpdated <= 0L) return true
        return now >= lastUpdated + intervalMs(updateIntervalMinutes)
    }

    private fun intervalMs(updateIntervalMinutes: Long): Long {
        val minutes = maxOf(AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES, updateIntervalMinutes)
        return minutes * 60_000L
    }
}
