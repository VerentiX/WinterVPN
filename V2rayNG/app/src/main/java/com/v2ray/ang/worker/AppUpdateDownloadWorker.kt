package com.v2ray.ang.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.handler.AppUpdateInstaller
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.NotificationHelper

/**
 * Resumable background APK download. WorkManager retries on network loss;
 * [AppUpdateInstaller.download] continues from the `.part` file via HTTP Range.
 */
class AppUpdateDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val pending = AppUpdateInstaller.pendingCheckResult()
        if (pending == null) {
            LogUtil.i(AppConfig.TAG, "No pending app update to download")
            return Result.success()
        }

        val ready = AppUpdateInstaller.getReadyUpdate(applicationContext)
        if (ready != null && ready.version == pending.latestVersion) {
            notifyReady(ready.version, ready.apkFile.absolutePath)
            return Result.success()
        }

        val downloaded = AppUpdateInstaller.download(applicationContext, pending) { _, _ -> }
        val apk = downloaded.apkFile
        if (apk == null) {
            LogUtil.w(AppConfig.TAG, "Background update download failed: ${downloaded.error}")
            return Result.retry()
        }

        notifyReady(pending.latestVersion.orEmpty(), apk.absolutePath)
        return Result.success()
    }

    private fun notifyReady(version: String, apkPath: String) {
        if (version.isBlank()) return
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (!canNotify) return

        val openIntent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_READY_UPDATE, true)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            1403,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationHelper.notify(
            NotificationChannelType.APP_UPDATE,
            applicationContext,
            applicationContext.getString(R.string.app_name),
            applicationContext.getString(R.string.update_download_complete, version),
            pendingIntent
        )
        LogUtil.i(AppConfig.TAG, "Background update ready: $version ($apkPath)")
    }

    companion object {
        const val EXTRA_READY_UPDATE = "extra_zimavpn_update_ready"
    }
}
