package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.LampaPackage
import com.v2ray.ang.dto.LampaSubscriptionResponse
import com.v2ray.ang.dto.LampaSubscriptionSnapshot
import com.v2ray.ang.dto.LampaTariff
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.LogUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil

/** Syncs tariff, current window, and purchased packages from the billing API. */
object LampaSubscriptionMetadata {

    private val SUB_ID_LIKE = Regex("^[A-Za-z0-9_-]{6,128}$")
    private val MOSCOW = TimeZone.getTimeZone("Europe/Moscow")

    fun snapshotFrom(response: LampaSubscriptionResponse): LampaSubscriptionSnapshot =
        LampaSubscriptionSnapshot(
            tariff = response.tariff,
            currentPackageId = response.currentPackageId,
            packages = response.packages.orEmpty(),
        )

    fun refreshFromApi(subId: String): LampaSubscriptionSnapshot? {
        val response = LampaBillingClient.fetchSubscription(subId) ?: return null
        if (!response.ok) {
            LogUtil.w(AppConfig.TAG, "Lampa metadata sync failed: ${response.error}")
            return null
        }
        return snapshotFrom(response)
    }

    fun applySnapshot(subscription: SubscriptionItem, snapshot: LampaSubscriptionSnapshot?) {
        if (snapshot == null) return
        applyTariff(subscription, snapshot.tariff, snapshot.activePackage())
        subscription.packages = snapshot.packages
        subscription.currentPackageId = snapshot.currentPackageId ?: 0L
        val windowEnd = snapshot.tariff?.packageEndsAt?.takeIf { it > 0 }
            ?: snapshot.activePackage()?.endsAt?.takeIf { it > 0 }
            ?: 0L
        if (windowEnd > 0) {
            subscription.packageEndsAt = windowEnd
        }
    }

    fun applyTariff(subscription: SubscriptionItem, tariff: LampaTariff?) {
        applyTariff(subscription, tariff, null)
    }

    fun storedPackages(subscription: SubscriptionItem): List<LampaPackage> =
        subscription.packages.orEmpty()

    fun visiblePackages(subscription: SubscriptionItem): List<LampaPackage> =
        storedPackages(subscription)
            .filter { it.active || it.upcoming }
            .sortedWith(compareByDescending<LampaPackage> { it.active }.thenBy { it.startsAt })

    fun activePackage(subscription: SubscriptionItem): LampaPackage? {
        val packages = storedPackages(subscription)
        return packages.firstOrNull { it.active }
            ?: packages.firstOrNull {
                subscription.currentPackageId > 0 && it.id == subscription.currentPackageId
            }
    }

    fun tariffLabelForItem(subscription: SubscriptionItem): String? {
        activePackage(subscription)?.let { return formatPackageTitle(it) }
        val gb = when {
            subscription.totalBytes > 0 ->
                (subscription.totalBytes / (1024.0 * 1024.0 * 1024.0)).toInt()
            else -> 0
        }
        if (gb > 0) {
            return when {
                gb >= 100 -> "Плюс · 100 ГБ"
                gb >= 50 -> "Стандарт · 50 ГБ"
                else -> "$gb ГБ"
            }
        }
        val raw = subscription.profileTitle?.trim().orEmpty()
        if (raw.isNotEmpty() && !SUB_ID_LIKE.matches(raw)) return raw
        return null
    }

    fun formatTariffTitle(tariff: LampaTariff?, active: LampaPackage? = null): String? {
        active?.let { return formatPackageTitle(it) }
        val raw = tariff?.title?.trim().orEmpty()
        if (raw.isNotEmpty() && !SUB_ID_LIKE.matches(raw)) return raw
        val gb = tariff?.let { resolveTrafficGb(it) } ?: 0
        if (gb > 0) {
            return when {
                gb >= 100 -> "Плюс · 100 ГБ"
                gb >= 50 -> "Стандарт · 50 ГБ"
                else -> "$gb ГБ"
            }
        }
        return null
    }

    fun formatPackageTitle(pkg: LampaPackage): String {
        val name = pkg.title?.trim().orEmpty()
        val gb = pkg.trafficGb
        return when {
            name.isNotEmpty() && gb > 0 && !name.contains("ГБ", ignoreCase = true) ->
                "$name · $gb ГБ"
            name.isNotEmpty() -> name
            gb > 0 -> "$gb ГБ"
            else -> ""
        }
    }

    fun formatPackageLine(context: Context, pkg: LampaPackage): String {
        val title = formatPackageTitle(pkg)
        return if (pkg.active) {
            context.getString(R.string.subscription_package_active, title, currentDaysLeft(pkg))
        } else {
            context.getString(
                R.string.subscription_package_upcoming,
                title,
                formatStartDay(pkg.startsAt),
                pkg.daysTotal.coerceAtLeast(1),
            )
        }
    }

    fun currentDaysLeft(subscription: SubscriptionItem): Int {
        activePackage(subscription)?.let { return currentDaysLeft(it) }
        val endsAt = subscription.packageEndsAt.takeIf { it > 0 } ?: return -1
        return daysUntil(endsAt)
    }

    fun currentDaysLeft(pkg: LampaPackage): Int {
        if (pkg.daysLeft > 0) return pkg.daysLeft
        if (pkg.endsAt > 0) return daysUntil(pkg.endsAt)
        return 0
    }

    fun totalDaysLeft(subscription: SubscriptionItem): Int {
        if (subscription.expireAt > 0) return daysUntil(subscription.expireAt)
        return -1
    }

    fun formatStartDay(unixSeconds: Long): String {
        if (unixSeconds <= 0) return "—"
        val locale = Locale.getDefault()
        val pattern = if (locale.language == "ru") "d MMM" else "MMM d"
        val sdf = SimpleDateFormat(pattern, locale)
        sdf.timeZone = MOSCOW
        return sdf.format(Date(unixSeconds * 1000L)).replace(".", "").trim()
    }

    private fun applyTariff(
        subscription: SubscriptionItem,
        tariff: LampaTariff?,
        active: LampaPackage?,
    ) {
        if (tariff == null && active == null) return
        val limitGb = active?.trafficGb?.takeIf { it > 0 }
            ?: tariff?.let { resolveTrafficGb(it) }
            ?: 0
        if (limitGb > 0) {
            subscription.totalBytes = gbToBytes(limitGb.toDouble())
        }
        val usedBytes = when {
            tariff?.usedBytes != null -> tariff.usedBytes.coerceAtLeast(0)
            tariff != null && tariff.usedGb > 0.0 -> gbToBytes(tariff.usedGb)
            else -> null
        }
        if (usedBytes != null) {
            subscription.uploadBytes = 0
            subscription.downloadBytes = usedBytes
        }
        if (tariff != null && tariff.expireAt > 0) {
            subscription.expireAt = tariff.expireAt
        }
        if (tariff != null && tariff.packageEndsAt > 0) {
            subscription.packageEndsAt = tariff.packageEndsAt
        } else if (active != null && active.endsAt > 0) {
            subscription.packageEndsAt = active.endsAt
        }
    }

    private fun resolveTrafficGb(tariff: LampaTariff): Int {
        if (tariff.limitGb > 0.0) return tariff.limitGb.toInt()
        if (tariff.trafficGb > 0) return tariff.trafficGb
        return 0
    }

    private fun gbToBytes(gb: Double): Long =
        (gb * 1024.0 * 1024.0 * 1024.0).toLong().coerceAtLeast(0)

    private fun daysUntil(unixSeconds: Long): Int {
        val secondsLeft = unixSeconds - System.currentTimeMillis() / 1000L
        if (secondsLeft <= 0) return 0
        return ceil(secondsLeft / 86_400.0).toInt()
    }
}
