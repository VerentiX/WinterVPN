package com.v2ray.ang.dto

import com.google.gson.annotations.SerializedName

data class LampaSubscriptionResponse(
    val ok: Boolean = false,
    val error: String? = null,
    @SerializedName("subId") val subId: String? = null,
    val status: String? = null,
    val tariff: LampaTariff? = null,
    @SerializedName("currentPackageId") val currentPackageId: Long? = null,
    val packages: List<LampaPackage>? = null,
    val plans: List<LampaPlan> = emptyList(),
    @SerializedName("referralDiscountRub") val referralDiscountRub: Int = 0,
    @SerializedName("paymentMethods") val paymentMethods: List<String>? = null,
    @SerializedName("paymentsEnabled") val paymentsEnabled: Boolean = false,
    @SerializedName("bindTelegramRequired") val bindTelegramRequired: Boolean = false,
    @SerializedName("allowTestPayment") val allowTestPayment: Boolean = false,
    @SerializedName("supportUrl") val supportUrl: String? = null,
)

data class LampaTariff(
    val title: String? = null,
    @SerializedName("trafficGb") val trafficGb: Int = 0,
    @SerializedName("daysLeft") val daysLeft: Int = 0,
    @SerializedName("expireAt") val expireAt: Long = 0,
    @SerializedName("usedGb") val usedGb: Double = 0.0,
    @SerializedName("usedBytes") val usedBytes: Long? = null,
    @SerializedName("limitGb") val limitGb: Double = 0.0,
    @SerializedName("packageEndsAt") val packageEndsAt: Long = 0,
)

data class LampaPackage(
    val id: Long = 0,
    val title: String? = null,
    @SerializedName("trafficGb") val trafficGb: Int = 0,
    @SerializedName("startsAt") val startsAt: Long = 0,
    @SerializedName("endsAt") val endsAt: Long = 0,
    @SerializedName("daysLeft") val daysLeft: Int = 0,
    @SerializedName("daysTotal") val daysTotal: Int = 0,
    val active: Boolean = false,
    val upcoming: Boolean = false,
)

data class LampaSubscriptionSnapshot(
    val tariff: LampaTariff? = null,
    val currentPackageId: Long? = null,
    val packages: List<LampaPackage> = emptyList(),
) {
    fun activePackage(): LampaPackage? =
        packages.firstOrNull { it.active }
            ?: packages.firstOrNull { currentPackageId != null && it.id == currentPackageId }

    fun visiblePackages(): List<LampaPackage> =
        packages
            .filter { it.active || it.upcoming }
            .sortedWith(compareByDescending<LampaPackage> { it.active }.thenBy { it.startsAt })
}

data class LampaPlan(
    val id: String = "",
    val title: String = "",
    val days: Int = 0,
    @SerializedName("trafficGb") val trafficGb: Int = 0,
    @SerializedName("priceRub") val priceRub: Int = 0,
    @SerializedName("catalogPriceRub") val catalogPriceRub: Int = 0,
    @SerializedName("discountRub") val discountRub: Int = 0,
)

data class LampaPaymentCreateRequest(
    @SerializedName("subId") val subId: String,
    @SerializedName("planId") val planId: String,
    val method: String,
    val test: Boolean = false,
)

data class LampaPaymentCreateResponse(
    val ok: Boolean = false,
    val error: String? = null,
    @SerializedName("orderId") val orderId: String? = null,
    @SerializedName("payUrl") val payUrl: String? = null,
    @SerializedName("amountRub") val amountRub: Int = 0,
    val plan: LampaPlan? = null,
    @SerializedName("expiresAt") val expiresAt: String? = null,
)

data class LampaPaymentStatusResponse(
    val ok: Boolean = false,
    val error: String? = null,
    @SerializedName("orderId") val orderId: String? = null,
    val status: String? = null,
    @SerializedName("paidAt") val paidAt: String? = null,
    @SerializedName("payUrl") val payUrl: String? = null,
    @SerializedName("amountRub") val amountRub: Int = 0,
    @SerializedName("planId") val planId: String? = null,
)
