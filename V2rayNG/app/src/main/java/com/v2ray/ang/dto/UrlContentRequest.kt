package com.v2ray.ang.dto

data class UrlContentRequest(
    val url: String?,
    val timeout: Int = 15000,
    val httpPort: Int = 0,
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Some Russian mobile networks stall Cloudflare HTTP/2 after TLS. */
    val http1Only: Boolean = false,
)
