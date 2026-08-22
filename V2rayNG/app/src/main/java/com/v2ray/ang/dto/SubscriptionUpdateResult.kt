package com.v2ray.ang.dto

/**
 * Result of subscription update operation
 */
data class SubscriptionUpdateResult(
    val configCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val skipCount: Int = 0,
    val error: SubscriptionUpdateError? = null,
    val errorDetail: String? = null,
) {
    operator fun plus(other: SubscriptionUpdateResult): SubscriptionUpdateResult {
        return SubscriptionUpdateResult(
            configCount = this.configCount + other.configCount,
            successCount = this.successCount + other.successCount,
            failureCount = this.failureCount + other.failureCount,
            skipCount = this.skipCount + other.skipCount,
            error = this.error ?: other.error,
            errorDetail = this.errorDetail ?: other.errorDetail,
        )
    }
}

enum class SubscriptionUpdateError {
    DISABLED,
    INVALID_URL,
    FETCH_FAILED,
    EMPTY_RESPONSE,
    PARSE_FAILED,
    UNKNOWN,
}
