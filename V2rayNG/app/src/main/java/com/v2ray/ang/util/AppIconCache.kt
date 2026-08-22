package com.v2ray.ang.util

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.content.ContextCompat

object AppIconCache {
    private val cache = LruCache<String, Drawable>(96)

    fun load(context: Context, packageName: String): Drawable? {
        cache.get(packageName)?.let { return it }
        val icon = try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
        } ?: return null
        cache.put(packageName, icon)
        return icon
    }
}
