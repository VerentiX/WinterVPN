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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Checks Hattabych releases once per day and notifies only for a new version. */
object AppUpdateScheduler {
    private const val INITIAL_TASK_NAME = "zimavpn_app_update_initial_v1"
    private const val PREF_LAST_NOTIFIED_VERSION = "pref_zimavpn_last_notified_version"
    private const val PREF_LAST_CHECK_AT = "pref_zimavpn_last_update_check_at"
    private val CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(24)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkInFlight = AtomicBoolean(false)

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

    /**
     * Same 24-hour interval as WorkManager, but runs in the UI process.
     * HyperOS often never fires the `:bg` JobScheduler jobs, so opening the
     * app is the reliable trigger — still at most once per day.
     */
    fun checkIfDue(context: Context) {
        val now = System.currentTimeMillis()
        val lastCheck = MmkvManager.decodeSettingsLong(PREF_LAST_CHECK_AT, 0L)
        if (lastCheck > 0L && now - lastCheck < CHECK_INTERVAL_MS) return
        if (!checkInFlight.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                runCheck(appContext)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "App update check failed", e)
            } finally {
                checkInFlight.set(false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    internal suspend fun runCheck(context: Context) {
        val update = UpdateCheckerManager.checkForUpdate(includePreRelease = false)
        MmkvManager.encodeSettings(PREF_LAST_CHECK_AT, System.currentTimeMillis())
        val version = update.latestVersion
        if (!update.hasUpdate || version.isNullOrBlank()) {
            LogUtil.i(AppConfig.TAG, "App update: already on latest")
            return
        }
        LogUtil.i(AppConfig.TAG, "App update: found $version")
        AppUpdateInstaller.enqueueBackgroundDownload(context, update)

        val lastNotified = MmkvManager.decodeSettingsString(PREF_LAST_NOTIFIED_VERSION)
        if (lastNotified == version || !canNotify(context)) return

        val intent = Intent(context, CheckUpdateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            1401,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationHelper.notify(
            NotificationChannelType.APP_UPDATE,
            context,
            context.getString(R.string.update_new_version_found, version),
            context.getString(R.string.update_available_notification, version),
            pendingIntent
        )
        MmkvManager.encodeSettings(PREF_LAST_NOTIFIED_VERSION, version)
    }

    private fun canNotify(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    class UpdateTask(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            return try {
                runCheck(applicationContext)
                Result.success()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "WinterVPN update check failed", e)
                Result.retry()
            }
        }
    }
}
