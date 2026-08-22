package com.v2ray.ang.dto

import android.graphics.drawable.Drawable

data class AppInfo(
    val appName: String,
    val packageName: String,
    val appIcon: Drawable? = null,
    val isSystemApp: Boolean,
    var isSelected: Int
)