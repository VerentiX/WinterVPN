package com.v2ray.ang.dto.entities

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = true,
    var updateInterval: Long = 720, // in minutes, default to 12 hours
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null,
    /** Optional metadata returned by subscription providers in HTTP response headers. */
    var profileTitle: String? = null,
    var uploadBytes: Long = -1,
    var downloadBytes: Long = -1,
    var totalBytes: Long = -1,
    /** Unix time in seconds, as defined by Subscription-Userinfo. */
    var expireAt: Long = -1,
)

