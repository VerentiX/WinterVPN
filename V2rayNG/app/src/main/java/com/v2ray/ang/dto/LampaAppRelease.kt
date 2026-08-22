package com.v2ray.ang.dto

data class LampaAppRelease(
    val ok: Boolean = false,
    val tag: String = "",
    val name: String = "",
    val publishedAt: String = "",
    val apk: Asset? = null,
    val assets: List<Asset> = emptyList(),
    val downloadUrl: String = ""
) {
    data class Asset(
        val name: String = "",
        val url: String = "",
        val size: Long = 0,
        val arch: String = ""
    )
}
