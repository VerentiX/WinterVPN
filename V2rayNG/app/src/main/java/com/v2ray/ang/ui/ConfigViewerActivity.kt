package com.v2ray.ang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.databinding.ActivityConfigViewerBinding
import com.v2ray.ang.extension.toastSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConfigViewerActivity : BaseActivity() {
    private val binding by lazy { ActivityConfigViewerBinding.inflate(layoutInflater) }
    private val guid by lazy { intent.getStringExtra("guid").orEmpty() }
    private var configContent = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.title_view_config),
        )
        loadConfig()
    }

    private fun loadConfig() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                CoreConfigManager.getV2rayConfig(applicationContext, guid)
            }
            binding.progress.visibility = View.GONE
            configContent = if (result.status) result.content else result.errorMessage
            binding.configContent.text = configContent
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_config_viewer, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.copy_config) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(getString(R.string.title_view_config), configContent)
            )
            toastSuccess(R.string.toast_success)
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
