package com.v2ray.ang.handler

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.ui.CheckUpdateActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.NotificationHelper
import java.util.concurrent.TimeUnit

/** Checks WinterVPN releases once per day and notifies only for a new version. */
object AppUpdateScheduler {
    private const val INITIAL_TASK_NAME = "zimavpn_app_update_initial_v1"
    private const val PREF_LAST_NOTIFIED_VERSION = "pref_zimavpn_last_notified_version"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val remote = RemoteWorkManager.getInstance(context.applicationContext)

        val initial = OneTimeWorkRequestBuilder<UpdateTask>()
            .setConstraints(constraints)
            .build()
        remote.enqueueUniqueWork(INITIAL_TASK_NAME, ExistingWorkPolicy.KEEP, initial)

        val periodic = PeriodicWorkRequestBuilder<UpdateTask>(
            24, TimeUnit.HOURS,
            1, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .addTag(AppConfig.APP_UPDATE_TASK_NAME)
            .build()
        remote.enqueueUniquePeriodicWork(
            AppConfig.APP_UPDATE_TASK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
    }

    class UpdateTask(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        @SuppressLint("MissingPermission")
        override suspend fun doWork(): Result {
            return try {
                val update = UpdateCheckerManager.checkForUpdate(includePreRelease = false)
                val version = update.latestVersion
                if (update.hasUpdate && !version.isNullOrBlank()) {
                    val lastNotified = MmkvManager.decodeSettingsString(PREF_LAST_NOTIFIED_VERSION)
                    if (lastNotified != version && canNotify(applicationContext)) {
                        val intent = Intent(applicationContext, CheckUpdateActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        val pendingIntent = PendingIntent.getActivity(
                            applicationContext,
                            1401,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        NotificationHelper.notify(
                            NotificationChannelType.APP_UPDATE,
                            applicationContext,
                            applicationContext.getString(R.string.update_new_version_found, version),
                            applicationContext.getString(R.string.update_available_notification, version),
                            pendingIntent
                        )
                        MmkvManager.encodeSettings(PREF_LAST_NOTIFIED_VERSION, version)
                    }
                }
                Result.success()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "WinterVPN update check failed", e)
                // A periodic retry storm is wasteful on blocked or private GitHub repos.
                // The normal 24-hour task will check again, while manual checks stay available.
                Result.success()
            }
        }

        private fun canNotify(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
