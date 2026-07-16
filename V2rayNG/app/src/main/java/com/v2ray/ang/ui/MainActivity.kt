package com.v2ray.ang.ui

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.navigation.NavigationView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.GeoAssetUpdater
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var subscriptionCardAdapter: SubscriptionCardAdapter
    private var geoProgressVisible = false
    private val geoUpdateStatus by lazy { findViewById<TextView>(R.id.tv_geo_update_status) }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.app_name))
        setupSnowAnimation()

        subscriptionCardAdapter = SubscriptionCardAdapter(object : SubscriptionCardAdapter.Listener {
            override fun onSelectProfile(guid: String) = selectProfile(guid)
            override fun onViewProfileConfig(guid: String) {
                requestActivityLauncher.launch(
                    Intent(this@MainActivity, ConfigViewerActivity::class.java).putExtra("guid", guid)
                )
            }
            override fun onUpdateSubscription(subscription: SubscriptionCache) {
                updateSubscription(subscription)
            }
            override fun onEditSubscription(subscriptionId: String) {
                requestActivityLauncher.launch(
                    Intent(this@MainActivity, SubEditActivity::class.java).putExtra("subId", subscriptionId)
                )
            }
        })
        binding.subscriptionCards.layoutManager = LinearLayoutManager(this)
        binding.subscriptionCards.adapter = subscriptionCardAdapter

        // setup navigation drawer
        setupNavigationDrawer()

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        setupGroupTab()
        setupViewModel()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun setupNavigationDrawer() {
        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.updateListAction.observe(this) {
            subscriptionCardAdapter.reload()
            updateConnectButtonAvailability()
        }
        mainViewModel.geoDataRepairAction.observe(this) {
            showGeoUpdateProgress(getString(R.string.geo_update_repairing), 0)
        }
        mainViewModel.geoAssetsReadyAction.observe(this) { ready ->
            if (ready && geoProgressVisible) {
                showGeoUpdateProgress(getString(R.string.geo_update_ready), 100)
                lifecycleScope.launch {
                    delay(1_200L)
                    hideGeoUpdateProgress()
                }
            }
        }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
        observeGeoAssetUpdates()
    }

    private fun observeGeoAssetUpdates() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(AppConfig.GEO_BOOTSTRAP_TASK_NAME)
            .observe(this) { workInfos ->
                val visibleWork = workInfos.filter {
                    it.tags.contains(GeoAssetUpdater.VISIBLE_PROGRESS_TAG)
                }
                val info = visibleWork.firstOrNull { !it.state.isFinished } ?: visibleWork.lastOrNull()
                    ?: return@observe
                if (info.state.isFinished && !geoProgressVisible) return@observe
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        showGeoUpdateProgress(getString(R.string.geo_update_waiting), null)
                    }
                    WorkInfo.State.RUNNING -> {
                        val percent = info.progress.getInt(GeoAssetUpdater.PROGRESS_PERCENT, 0)
                        showGeoUpdateProgress(getString(R.string.geo_update_downloading, percent), percent)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val successMessage = if (
                            info.outputData.getBoolean(GeoAssetUpdater.INPUT_RECONNECT, false)
                        ) {
                            getString(R.string.geo_update_success)
                        } else {
                            getString(R.string.geo_update_ready)
                        }
                        showGeoUpdateProgress(successMessage, 100)
                        lifecycleScope.launch {
                            delay(1_200L)
                            hideGeoUpdateProgress()
                        }
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> if (geoProgressVisible) {
                        geoUpdateStatus.text = getString(R.string.geo_update_failed)
                        lifecycleScope.launch {
                            delay(4_000L)
                            hideGeoUpdateProgress()
                        }
                    }
                }
            }
    }

    private fun showGeoUpdateProgress(message: String, percent: Int?) {
        geoProgressVisible = true
        binding.progressBar.isIndeterminate = percent == null
        if (percent != null) {
            binding.progressBar.setProgressCompat(percent.coerceIn(0, 100), true)
        }
        binding.progressBar.visibility = View.VISIBLE
        geoUpdateStatus.text = message
        geoUpdateStatus.visibility = View.VISIBLE
    }

    private fun hideGeoUpdateProgress() {
        if (!geoProgressVisible) return
        geoProgressVisible = false
        binding.progressBar.visibility = View.INVISIBLE
        binding.progressBar.isIndeterminate = true
        binding.progressBar.progress = 0
        geoUpdateStatus.visibility = View.GONE
    }

    private fun setupGroupTab() {
        if (mainViewModel.subscriptionId.isNotEmpty()) {
            mainViewModel.subscriptionIdChanged("")
        }
        subscriptionCardAdapter.reload()
    }

    fun refreshGroupTabTitles() {
        subscriptionCardAdapter.reload()
    }

    private fun handleFabAction() {
        if (mainViewModel.isRunning.value != true && !hasSelectedProfile()) {
            updateConnectButtonAvailability()
            return
        }
        applyRunningState(isLoading = true, isRunning = false)

        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // service not running: keep existing no-op (could show a message if desired)
        }
    }

    private fun startV2Ray() {
        if (!hasSelectedProfile()) {
            updateConnectButtonAvailability()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }

        CoreServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.restartVService(this)
        } else {
            startV2Ray()
        }
    }

    fun reloadV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.reloadVService(this)
        } else {
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private fun setupSnowAnimation() {
        binding.animationSnow.addLottieOnCompositionLoadedListener {
            if (mainViewModel.isRunning.value == true) {
                setSnowAnimationEnabled(true)
            }
        }
    }

    private fun setSnowAnimationEnabled(enabled: Boolean) {
        binding.animationSnow.apply {
            if (enabled) {
                visibility = View.VISIBLE
                alpha = 0.7f
                if (!isAnimating) {
                    playAnimation()
                }
            } else {
                cancelAnimation()
                visibility = View.GONE
            }
        }
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.isEnabled = false
            binding.ivFabIcon.setImageResource(R.drawable.ic_fab_check)
            binding.fab.clearAnimation()
            return
        }

        refreshSelectedProfile()
        updateConnectButtonAvailability(isRunning)

        if (isRunning) {
            binding.ivFabIcon.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.setBackgroundResource(R.drawable.bg_power_btn_active)

            // Запуск анимации пульсации кнопки
            val pulseAnimation = android.view.animation.ScaleAnimation(
                1.0f, 1.04f, 1.0f, 1.04f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 1500
                repeatCount = android.view.animation.Animation.INFINITE
                repeatMode = android.view.animation.Animation.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }
            binding.fab.startAnimation(pulseAnimation)

            // ВКЛЮЧАЕМ АНИМАЦИЮ СНЕГА
            setSnowAnimationEnabled(true)

            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
        } else {
            binding.ivFabIcon.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.setBackgroundResource(R.drawable.bg_power_btn_inactive)
            binding.fab.clearAnimation()

            // ВЫКЛЮЧАЕМ АНИМАЦИЮ СНЕГА
            setSnowAnimationEnabled(false)

            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
        }
    }

    private fun hasSelectedProfile(): Boolean {
        val guid = MmkvManager.getSelectServer() ?: return false
        return MmkvManager.decodeAllServerList().contains(guid) &&
            MmkvManager.decodeServerConfig(guid) != null
    }

    private fun updateConnectButtonAvailability(
        isRunning: Boolean = mainViewModel.isRunning.value == true
    ) {
        val enabled = isRunning || hasSelectedProfile()
        binding.fab.isEnabled = enabled
        binding.fab.isClickable = enabled
        binding.fab.alpha = if (enabled) 1f else 0.42f
    }
    fun refreshSelectedProfile() {
        val profileName = MmkvManager.getSelectServer()
            ?.let(MmkvManager::decodeServerConfig)
            ?.remarks
            ?.takeIf { it.isNotBlank() }
        binding.tvSelectedProfile.text = profileName ?: getString(R.string.zeus_no_profile)
    }

    override fun onResume() {
        super.onResume()
        refreshSelectedProfile()
        updateConnectButtonAvailability()
        if (::subscriptionCardAdapter.isInitialized) subscriptionCardAdapter.reload()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    subscriptionCardAdapter.reload(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                subscriptionCardAdapter.reload("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.add_subscription_clipboard -> {
            addSubscriptionFromClipboard()
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.locate_selected_config -> {
            locateSelectedServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun addSubscriptionFromClipboard() {
        val clipboard = runCatching { Utils.getClipboard(this) }.getOrNull()
            ?.trim()?.lineSequence()?.firstOrNull()?.trim()
        if (!Utils.isValidSubUrl(clipboard)) {
            toastError(R.string.subscription_empty_clipboard)
            return
        }
        if (MmkvManager.decodeSubscriptions().any { it.subscription.url == clipboard }) {
            toast(R.string.subscription_already_exists)
            return
        }

        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val subscription = runCatching { AngConfigManager.addSubscription(clipboard) }.getOrNull()
            val result = subscription?.let { AngConfigManager.updateConfigViaSub(it) }
            subscription?.let { SubscriptionUpdater.syncOne(subId = it.guid) }
            withContext(Dispatchers.Main) {
                if (subscription == null) {
                    toastError(R.string.toast_failure)
                } else if (result != null && result.successCount > 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toastError(R.string.toast_failure)
                }
                mainViewModel.reloadServerList()
                setupGroupTab()
                hideLoading()
            }
        }
    }

    private fun updateSubscription(subscription: SubscriptionCache) {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.updateConfigViaSub(subscription)
            SubscriptionUpdater.syncOne(subId = subscription.guid)
            withContext(Dispatchers.Main) {
                if (result.successCount > 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toastError(R.string.toast_failure)
                }
                mainViewModel.reloadServerList()
                subscriptionCardAdapter.reload()
                hideLoading()
            }
        }
    }

    private fun selectProfile(guid: String) {
        if (MmkvManager.getSelectServer() == guid) return
        MmkvManager.setSelectServer(guid)
        refreshSelectedProfile()
        updateConnectButtonAvailability()
        subscriptionCardAdapter.reload()
        if (mainViewModel.isRunning.value == true) reloadV2Ray()
    }

    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()
                }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = subscriptionCardAdapter.revealSubscription(targetSubscriptionId)
        if (targetGroupIndex < 0) {
            toast(R.string.toast_server_not_found_in_group)
            return
        }
        binding.subscriptionCards.smoothScrollToPosition(targetGroupIndex)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.sub_setting -> requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

}
