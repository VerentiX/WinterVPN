package com.v2ray.ang.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppManagerUtil {
    private data class CachedAppMeta(
        val appName: String = "",
        val packageName: String = "",
        val isSystemApp: Boolean = false,
    )

    private data class CachedAppList(
        val apps: ArrayList<CachedAppMeta> = arrayListOf(),
    )

    @Volatile
    private var memoryCache: ArrayList<AppInfo>? = null

    fun peekCachedApps(): ArrayList<AppInfo>? {
        memoryCache?.let { return ArrayList(it) }
        val disk = readDiskCache()?.apps ?: return null
        if (disk.isEmpty()) return null
        val apps = disk.toAppInfo()
        memoryCache = ArrayList(apps)
        return apps
    }

    /**
     * Load installed apps. Uses a persisted name/package cache so the UI can
     * appear immediately; then merges with the live package list so newly
     * installed apps are added and uninstalled ones are dropped.
     */
    suspend fun loadNetworkAppList(context: Context): ArrayList<AppInfo> =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val installed = scanInstalled(appContext)
            val cached = readDiskCache()?.apps.orEmpty()
            val merged = mergeCache(cached, installed, appContext.packageManager)
            persist(merged)
            val apps = merged.toAppInfo()
            memoryCache = ArrayList(apps)
            apps
        }

    fun getLastUpdateTime(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    private fun scanInstalled(context: Context): List<ApplicationInfo> {
        return try {
            context.packageManager.getInstalledApplications(0)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to list installed apps", e)
            emptyList()
        }
    }

    private fun mergeCache(
        cached: List<CachedAppMeta>,
        installed: List<ApplicationInfo>,
        packageManager: PackageManager,
    ): ArrayList<CachedAppMeta> {
        val cachedByPackage = cached.associateBy { it.packageName }
        val result = ArrayList<CachedAppMeta>(installed.size)
        for (applicationInfo in installed) {
            val packageName = applicationInfo.packageName ?: continue
            val existing = cachedByPackage[packageName]
            if (existing != null) {
                result.add(existing)
                continue
            }
            val appName = try {
                applicationInfo.loadLabel(packageManager).toString()
            } catch (_: Exception) {
                packageName
            }
            val isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            result.add(CachedAppMeta(appName, packageName, isSystemApp))
        }
        return result
    }

    private fun persist(apps: ArrayList<CachedAppMeta>) {
        try {
            MmkvManager.encodeSettings(AppConfig.PREF_SPLIT_TUNNEL_APP_CACHE, JsonUtil.toJson(CachedAppList(apps)))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to save app list cache", e)
        }
    }

    private fun readDiskCache(): CachedAppList? {
        val json = MmkvManager.decodeSettingsString(AppConfig.PREF_SPLIT_TUNNEL_APP_CACHE) ?: return null
        if (json.isBlank()) return null
        return try {
            JsonUtil.fromJson(json, CachedAppList::class.java)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read app list cache", e)
            null
        }
    }

    private fun List<CachedAppMeta>.toAppInfo(): ArrayList<AppInfo> {
        val apps = ArrayList<AppInfo>(size)
        forEach { meta ->
            apps.add(
                AppInfo(
                    appName = meta.appName,
                    packageName = meta.packageName,
                    appIcon = null,
                    isSystemApp = meta.isSystemApp,
                    isSelected = 0,
                )
            )
        }
        return apps
    }
}
