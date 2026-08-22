package com.v2ray.ang.util

import android.content.Context
import com.v2ray.ang.R
import com.v2ray.ang.dto.SubscriptionUpdateError

object LampaErrorMessages {

    fun subscriptionUpdate(context: Context, error: SubscriptionUpdateError?, detail: String? = null): String {
        val base = when (error) {
            SubscriptionUpdateError.DISABLED -> context.getString(R.string.subscription_update_error_disabled)
            SubscriptionUpdateError.INVALID_URL -> context.getString(R.string.subscription_update_error_invalid_url)
            SubscriptionUpdateError.FETCH_FAILED -> context.getString(R.string.subscription_update_error_fetch)
            SubscriptionUpdateError.EMPTY_RESPONSE -> context.getString(R.string.subscription_update_error_empty)
            SubscriptionUpdateError.PARSE_FAILED -> context.getString(R.string.subscription_update_error_parse)
            SubscriptionUpdateError.UNKNOWN, null -> context.getString(R.string.subscription_update_error_unknown)
        }
        val extra = detail?.trim().orEmpty()
        return if (extra.isNotEmpty()) {
            if (extra.contains("\n")) {
                "$base\n\n${context.getString(R.string.subscription_update_attempts_header)}\n$extra"
            } else {
                "$base\n$extra"
            }
        } else {
            base
        }
    }

    fun billingApi(context: Context, code: String?): String {
        return when (code?.trim()?.lowercase()) {
            "invalid_sub_id" -> context.getString(R.string.lampa_error_invalid_sub_id)
            "subscription_not_found" -> context.getString(R.string.lampa_error_subscription_not_found)
            "invalid_plan" -> context.getString(R.string.lampa_error_invalid_plan)
            "invalid_method" -> context.getString(R.string.lampa_error_invalid_method)
            "payments_not_configured" -> context.getString(R.string.lampa_error_payments_not_configured)
            "pay_url_missing" -> context.getString(R.string.lampa_error_pay_url_missing)
            "forbidden" -> context.getString(R.string.lampa_error_forbidden)
            "order_not_found" -> context.getString(R.string.lampa_error_order_not_found)
            "test_payment_forbidden" -> context.getString(R.string.lampa_error_test_payment_forbidden)
            "bind_telegram_required" -> context.getString(R.string.lampa_error_bind_telegram_required)
            "network", null, "" -> context.getString(R.string.lampa_error_network)
            else -> context.getString(R.string.lampa_error_generic, code)
        }
    }
}
