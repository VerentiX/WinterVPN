package com.v2ray.ang.util

import android.content.Context
import android.os.BatteryManager
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats

/**
 * Reads cumulative energy attributed by Android to this application's UID.
 *
 * Prefer HealthStats power counters (mA·ms). When OEMs freeze or omit them
 * (common on Xiaomi while USB-powered), fall back to a CPU-time model that
 * matches BatteryStats "Estimated power use" for VPN UIDs — which typically
 * attribute almost no wifi/mobile radio power to the tunnel app, despite large
 * RX/TX times on the VPN UID.
 */
object AppBatteryUsageEstimator {
    data class Snapshot(
        val powerMams: Long,
        val cpuTimeMs: Long,
    )

    /** Effective UID CPU current used by Android PowerProfile-style estimators. */
    private const val CPU_CURRENT_MA = 200.0

    private const val MAMS_PER_MAH = 3_600_000.0

    private val powerKeys = intArrayOf(
        UidHealthStats.MEASUREMENT_CPU_POWER_MAMS,
        UidHealthStats.MEASUREMENT_WIFI_POWER_MAMS,
        UidHealthStats.MEASUREMENT_MOBILE_POWER_MAMS,
        UidHealthStats.MEASUREMENT_BLUETOOTH_POWER_MAMS,
    )

    fun readSnapshot(context: Context): Snapshot? = runCatching {
        val manager = context.getSystemService(SystemHealthManager::class.java) ?: return null
        val stats = manager.takeMyUidSnapshot()
        var power = 0L
        powerKeys.forEach { key ->
            if (stats.hasMeasurement(key)) {
                power += stats.getMeasurement(key).coerceAtLeast(0L)
            }
        }
        fun value(key: Int): Long =
            if (stats.hasMeasurement(key)) stats.getMeasurement(key).coerceAtLeast(0L) else 0L
        Snapshot(
            powerMams = power,
            cpuTimeMs = value(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS) +
                value(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS),
        )
    }.getOrElse {
        LogUtil.w("Winter", "App battery HealthStats unavailable", it)
        null
    }

    /**
     * Prefer Android's attributed power (mA·ms → mAh).
     * If the delta is zero, estimate from UID CPU time only — do not invent
     * radio cost from VPN tunnel RX/TX, which overstates usage by ~10× vs Settings.
     */
    fun estimateDeltaMah(start: Snapshot, current: Snapshot): Double? {
        val measuredMams = current.powerMams - start.powerMams
        if (measuredMams > 0L) return measuredMams / MAMS_PER_MAH

        val cpuMs = (current.cpuTimeMs - start.cpuTimeMs).coerceAtLeast(0L)
        if (cpuMs == 0L) return 0.0
        return cpuMs * CPU_CURRENT_MA / MAMS_PER_MAH
    }

    /**
     * Full pack capacity in mAh. Prefer PowerProfile (same source as BatteryStats),
     * then coulomb-counter / percentage.
     */
    fun estimateFullCapacityMah(context: Context): Double? {
        powerProfileCapacityMah(context)?.let { return it }
        return chargeCounterCapacityMah(context)
    }

    private fun powerProfileCapacityMah(context: Context): Double? = runCatching {
        val clazz = Class.forName("com.android.internal.os.PowerProfile")
        val profile = clazz.getConstructor(Context::class.java).newInstance(context)
        val capacity = clazz.getMethod("getBatteryCapacity").invoke(profile) as Double
        capacity.takeIf { it in 500.0..20_000.0 }
    }.getOrNull()

    private fun chargeCounterCapacityMah(context: Context): Double? = runCatching {
        val battery = context.getSystemService(BatteryManager::class.java) ?: return null
        val remainingMicroAh = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (remainingMicroAh <= 0 || percent !in 1..100) return null
        (remainingMicroAh / 1_000.0) / (percent / 100.0)
    }.getOrNull()?.takeIf { it in 500.0..20_000.0 }
}
