package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.databinding.ActivityCheckUpdateBinding
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.handler.AppUpdateInstaller
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.launch

class CheckUpdateActivity : BaseActivity() {

    private val binding by lazy { ActivityCheckUpdateBinding.inflate(layoutInflater) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(binding.root)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.update_check_for_update))

        binding.layoutCheckUpdate.setOnClickListener {
            checkForUpdates(binding.checkPreRelease.isChecked)
        }

        binding.checkPreRelease.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, isChecked)
        }
        binding.checkPreRelease.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)

        "v${BuildConfig.VERSION_NAME} (${CoreNativeManager.getLibVersion()})".also {
            binding.tvVersion.text = it
        }

        checkForUpdates(binding.checkPreRelease.isChecked)
    }

    private fun checkForUpdates(includePreRelease: Boolean) {
        toast(R.string.update_checking_for_update)
        showLoading()

        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(includePreRelease)
                if (result.hasUpdate) {
                    showUpdateDialog(result)
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to check for updates: ${e.message}")
                toastError(e.message ?: getString(R.string.toast_failure))
            } finally {
                hideLoading()
            }
        }
    }

    private fun showUpdateDialog(result: CheckUpdateResult) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_new_version_found, result.latestVersion))
            .setMessage(result.releaseNotes)
            .setPositiveButton(R.string.update_now) { _, _ ->
                startUpdateDownload(result)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startUpdateDownload(result: CheckUpdateResult) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            toast(R.string.update_install_permission)
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        binding.layoutUpdateDownload.visibility = View.VISIBLE
        setDownloadProgress(getString(R.string.update_download_preparing), null)
        lifecycleScope.launch {
            val downloaded = AppUpdateInstaller.download(this@CheckUpdateActivity, result) { bytes, total ->
                runOnUiThread {
                    val received = Formatter.formatShortFileSize(this@CheckUpdateActivity, bytes)
                    val size = total.takeIf { it > 0L }?.let {
                        Formatter.formatShortFileSize(this@CheckUpdateActivity, it)
                    }
                    if (size == null) {
                        setDownloadProgress(getString(R.string.update_download_progress_unknown, received), null)
                    } else {
                        val percent = (bytes * 100L / total).toInt().coerceIn(0, 100)
                        setDownloadProgress(
                            getString(R.string.update_download_progress, percent, received, size),
                            percent
                        )
                    }
                }
            }
            val apk = downloaded.apkFile
            if (apk == null) {
                binding.tvUpdateDownloadStatus.text = downloaded.error
                    ?: getString(R.string.update_download_failed, 0)
                return@launch
            }
            setDownloadProgress(getString(R.string.update_download_installing), 100)
            if (!AppUpdateInstaller.launchDownloadedApk(this@CheckUpdateActivity, apk)) {
                binding.tvUpdateDownloadStatus.text = getString(R.string.update_install_launch_failed)
            }
        }
    }

    private fun setDownloadProgress(message: String, percent: Int?) {
        binding.tvUpdateDownloadStatus.text = message
        binding.updateDownloadProgress.isIndeterminate = percent == null
        if (percent != null) {
            binding.updateDownloadProgress.setProgressCompat(percent.coerceIn(0, 100), true)
        }
    }
}
