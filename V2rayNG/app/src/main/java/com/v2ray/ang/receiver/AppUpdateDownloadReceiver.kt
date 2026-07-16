package com.v2ray.ang.receiver

import android.Manifest
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.handler.AppUpdateInstaller
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.NotificationHelper

/** Offers the completed APK to Android's installer through a user-tapped notification. */
class AppUpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val expectedId = MmkvManager.decodeSettingsLong(AppUpdateInstaller.PREF_DOWNLOAD_ID, -1L)
        if (completedId <= 0L || completedId != expectedId) return

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val successful = manager.query(DownloadManager.Query().setFilterById(completedId)).use { cursor ->
            cursor.moveToFirst() &&
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                DownloadManager.STATUS_SUCCESSFUL
        }
        if (!successful) return

        val apkUri = manager.getUriForDownloadedFile(completedId) ?: return
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, AppUpdateInstaller.APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { clipData = ClipData.newRawUri("ZimaVPN update", apkUri) }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1402,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val version = MmkvManager.decodeSettingsString(
            AppUpdateInstaller.PREF_DOWNLOAD_VERSION,
            ""
        ).orEmpty()
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            NotificationHelper.notify(
                NotificationChannelType.APP_UPDATE,
                context,
                context.getString(R.string.app_name),
                context.getString(R.string.update_download_complete, version),
                pendingIntent
            )
        } else {
            // The system DownloadManager notification remains available. Try to open the
            // installer immediately as a best effort when app notifications are disabled.
            runCatching { context.startActivity(installIntent) }
        }
    }
}
