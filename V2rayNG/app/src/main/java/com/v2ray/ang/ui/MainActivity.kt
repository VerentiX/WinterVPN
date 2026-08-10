package com.v2ray.ang.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
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
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.GeoAssetUpdater
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionRefreshManager
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
    private var powerRingAnimator: ObjectAnimator? = null
    private var lastAppliedRunningState: Boolean? = null
    private var winterEffectsActive = false
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
            override fun onMeasureSubscription(subscriptionId: String) {
                val count = MmkvManager.decodeServerList(subscriptionId).size
                toast(getString(R.string.connection_test_testing_count, count))
                mainViewModel.testSubscriptionRealPing(subscriptionId)
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
        setupPowerButtonPressFeedback()
        setupAnimationToggle()
        setupGroupTab()
        setupViewModel()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun isWinterAnimationsEnabled(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_UI_WINTER_ANIMATIONS, true)
    }

    private fun setupAnimationToggle() {
        binding.switchAnimations.setOnCheckedChangeListener(null)
        binding.switchAnimations.isChecked = isWinterAnimationsEnabled()
        // Switch is visual only — whole chip (label, padding, switch) toggles via the bar.
        binding.switchAnimations.isClickable = false
        binding.switchAnimations.isFocusable = false
        binding.switchAnimations.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_UI_WINTER_ANIMATIONS, isChecked)
            applyAnimationPreference(isChecked)
        }
        binding.animationToggleBar.setOnClickListener {
            binding.switchAnimations.toggle()
        }
    }

    private fun applyAnimationPreference(enabled: Boolean) {
        if (enabled) {
            if (mainViewModel.isRunning.value == true) {
                setWinterEffectsEnabled(true, animate = true)
                binding.powerGlow.animate().alpha(1f).setDuration(220L).start()
                startConnectedPulseIfNeeded()
            }
        } else {
            setWinterEffectsEnabled(false, animate = true)
            binding.powerGlow.animate().cancel()
            binding.powerGlow.alpha = 0f
            binding.fab.clearAnimation()
            // Keep a clean pressable button without ambient pulse/glow/frost.
            binding.fab.animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
        }
    }

    private fun setupPowerButtonPressFeedback() {
        binding.fab.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (view.isEnabled) {
                        view.clearAnimation()
                        view.animate().cancel()
                        view.animate()
                            .scaleX(0.96f)
                            .scaleY(0.96f)
                            .setDuration(120L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().cancel()
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(220L)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            if (mainViewModel.isRunning.value == true &&
                                !isPowerLoading() &&
                                isWinterAnimationsEnabled()
                            ) {
                                startConnectedPulseIfNeeded()
                            }
                        }
                        .start()
                }
            }
            false
        }
    }

    private fun isPowerLoading(): Boolean {
        return binding.powerLoadingRing.visibility == View.VISIBLE &&
            (powerRingAnimator?.isRunning == true)
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
        mainViewModel.updateListAction.observe(this) { index ->
            if (index == null || index < 0) {
                subscriptionCardAdapter.reload()
            } else {
                subscriptionCardAdapter.notifyProfileStatusChanged()
            }
            updateConnectButtonAvailability()
        }
        mainViewModel.profileDelayUpdatedAction.observe(this) { guid ->
            subscriptionCardAdapter.notifyProfileStatusChanged(guid.ifEmpty { null })
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
            if (isRunning == true) {
                subscriptionCardAdapter.notifyProfileStatusChanged()
            }
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
            toast(getString(R.string.connection_not_connected))
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
        if (!isWinterAnimationsEnabled()) {
            binding.frostOverlay.setFrozenImmediate(false)
            binding.animationSnow.cancelAnimation()
            binding.animationSnow.visibility = View.GONE
            return
        }
        binding.animationSnow.addLottieOnCompositionLoadedListener {
            if (mainViewModel.isRunning.value == true && isWinterAnimationsEnabled()) {
                setWinterEffectsEnabled(true, animate = false)
            }
        }
        if (mainViewModel.isRunning.value == true) {
            binding.frostOverlay.post {
                if (!isWinterAnimationsEnabled()) return@post
                winterEffectsActive = true
                binding.frostOverlay.setFrozenImmediate(true, binding.fab, binding.layoutTest)
            }
        }
    }

    private fun setSnowAnimationEnabled(enabled: Boolean) {
        setWinterEffectsEnabled(enabled, animate = true)
    }

    private fun setWinterEffectsEnabled(enabled: Boolean, animate: Boolean = true) {
        if (enabled && !isWinterAnimationsEnabled()) {
            // Still allow forced cleanup path via enabled=false.
            setWinterEffectsEnabled(false, animate = false)
            return
        }
        if (enabled) {
            winterEffectsActive = true
            if (animate) {
                binding.frostOverlay.freezeFrom(binding.fab, binding.layoutTest)
            } else {
                binding.frostOverlay.setFrozenImmediate(true, binding.fab, binding.layoutTest)
            }
            binding.animationSnow.apply {
                // Full-screen snowfall; cracks are clipped separately in FrostOverlayView.
                val lp = layoutParams as? android.widget.FrameLayout.LayoutParams
                if (lp != null) {
                    lp.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    lp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    lp.gravity = android.view.Gravity.CENTER
                    layoutParams = lp
                }
                scaleX = 1.15f
                scaleY = 1.08f
                visibility = View.VISIBLE
                if (!isAnimating) playAnimation()
                animate().cancel()
                if (animate) {
                    alpha = 0f
                    animate().alpha(0.4f).setDuration(1_200L)
                        .setStartDelay(250L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                } else {
                    alpha = 0.4f
                }
            }
        } else {
            if (!winterEffectsActive && animate) return
            winterEffectsActive = false
            if (animate) {
                binding.frostOverlay.melt()
                binding.animationSnow.animate().cancel()
                binding.animationSnow.animate()
                    .alpha(0f)
                    .setDuration(700L)
                    .withEndAction {
                        binding.animationSnow.cancelAnimation()
                        binding.animationSnow.visibility = View.GONE
                    }
                    .start()
            } else {
                binding.frostOverlay.setFrozenImmediate(false)
                binding.animationSnow.animate().cancel()
                binding.animationSnow.cancelAnimation()
                binding.animationSnow.alpha = 0f
                binding.animationSnow.visibility = View.GONE
            }
        }
    }

    private fun startConnectedPulseIfNeeded() {
        if (!isWinterAnimationsEnabled()) {
            binding.fab.clearAnimation()
            return
        }
        if (binding.fab.animation != null) return
        // Very soft breathing — barely noticeable, no “jump”.
        val pulseAnimation = android.view.animation.ScaleAnimation(
            1.0f, 1.015f, 1.0f, 1.015f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 2400
            repeatCount = android.view.animation.Animation.INFINITE
            repeatMode = android.view.animation.Animation.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            startOffset = 200
        }
        binding.fab.startAnimation(pulseAnimation)
    }

    /** Soft settle when state changes — no overshoot bounce. */
    private fun playSoftStateTransition(onSettled: (() -> Unit)? = null) {
        binding.fab.clearAnimation()
        binding.fab.animate().cancel()
        binding.powerBtnContent.animate().cancel()
        binding.ivFabIcon.animate().cancel()

        binding.fab.scaleX = 0.98f
        binding.fab.scaleY = 0.98f
        binding.fab.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(280L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { onSettled?.invoke() }
            .start()
    }

    /**
     * 3D flip in the button center: power ↔ shield (like a toggle face turn).
     */
    private fun playPowerIconFlip(
        toIconRes: Int,
        onSettled: (() -> Unit)? = null
    ) {
        val content = binding.powerBtnContent
        content.animate().cancel()
        content.cameraDistance = 12_000f * resources.displayMetrics.density

        // Halfway out → swap face → halfway in
        content.animate()
            .rotationY(90f)
            .setDuration(170L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding.ivFabIcon.setImageResource(toIconRes)
                content.rotationY = -90f
                content.animate()
                    .rotationY(0f)
                    .setDuration(190L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        content.rotationY = 0f
                        onSettled?.invoke()
                    }
                    .start()
            }
            .start()
    }

    private fun setPowerLoading(enabled: Boolean) {
        binding.powerLoadingRing.apply {
            if (enabled) {
                visibility = View.VISIBLE
                animate().alpha(1f).setDuration(180L).start()
                if (powerRingAnimator?.isRunning != true) {
                    powerRingAnimator = ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
                        duration = 900L
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = LinearInterpolator()
                        start()
                    }
                }
            } else {
                powerRingAnimator?.cancel()
                powerRingAnimator = null
                rotation = 0f
                animate().alpha(0f).setDuration(160L).withEndAction {
                    visibility = View.INVISIBLE
                }.start()
            }
        }
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        val winterOn = isWinterAnimationsEnabled()
        if (isLoading) {
            binding.fab.isEnabled = false
            setPowerLoading(true)
            binding.fab.clearAnimation()
            binding.powerBtnContent.animate().cancel()
            binding.powerBtnContent.rotationY = 0f
            binding.ivFabIcon.setImageResource(R.drawable.ic_power_24dp)
            if (winterOn) {
                binding.powerGlow.animate().alpha(0.45f).setDuration(220L).start()
            } else {
                binding.powerGlow.animate().cancel()
                binding.powerGlow.alpha = 0f
            }
            binding.fab.contentDescription = getString(R.string.zeus_status_connecting)
            // Freeze starts from the button as soon as connect is pressed.
            if (lastAppliedRunningState != true) {
                if (winterOn) {
                    winterEffectsActive = true
                    binding.frostOverlay.freezeFrom(binding.fab, binding.layoutTest)
                }
            } else {
                setWinterEffectsEnabled(false, animate = winterOn)
            }
            return
        }

        setPowerLoading(false)

        refreshSelectedProfile()
        updateConnectButtonAvailability(isRunning)

        val stateChanged = lastAppliedRunningState != isRunning
        lastAppliedRunningState = isRunning

        if (isRunning) {
            binding.fab.setBackgroundResource(R.drawable.bg_power_btn_active)
            if (winterOn) {
                binding.powerGlow.animate().alpha(1f).setDuration(320L).start()
            } else {
                binding.powerGlow.animate().cancel()
                binding.powerGlow.alpha = 0f
                binding.fab.clearAnimation()
            }

            if (stateChanged) {
                if (winterOn) {
                    setWinterEffectsEnabled(true, animate = true)
                } else {
                    setWinterEffectsEnabled(false, animate = false)
                }
                setTestState(getString(R.string.connection_connected))
                playSoftStateTransition()
                playPowerIconFlip(
                    toIconRes = R.drawable.ic_shield_24dp,
                ) {
                    if (winterOn && !isPowerLoading()) {
                        startConnectedPulseIfNeeded()
                    }
                }
            } else {
                binding.ivFabIcon.setImageResource(R.drawable.ic_shield_24dp)
                if (winterOn) {
                    startConnectedPulseIfNeeded()
                }
            }

            binding.fab.contentDescription = getString(R.string.action_stop_service)
            binding.layoutTest.isFocusable = true
        } else {
            binding.fab.setBackgroundResource(R.drawable.bg_power_btn_inactive)
            binding.fab.clearAnimation()
            binding.powerGlow.animate().alpha(0f).setDuration(260L).start()

            setWinterEffectsEnabled(false, animate = winterOn)
            if (stateChanged) {
                playSoftStateTransition()
                playPowerIconFlip(
                    toIconRes = R.drawable.ic_power_24dp,
                )
            } else {
                binding.ivFabIcon.setImageResource(R.drawable.ic_power_24dp)
            }

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
        mainViewModel.requestServiceState()
        refreshSelectedProfile()
        updateConnectButtonAvailability()
        if (::subscriptionCardAdapter.isInitialized) subscriptionCardAdapter.reload()
        refreshSubscriptionsOnAppOpen()
    }

    private fun refreshSubscriptionsOnAppOpen() {
        SubscriptionRefreshManager.refreshOnAppOpen { result ->
            if (result.configCount > 0 || result.successCount > 0) {
                mainViewModel.reloadServerList()
                if (::subscriptionCardAdapter.isInitialized) {
                    subscriptionCardAdapter.reload()
                }
                refreshSelectedProfile()
            }
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        // This screen always uses the dark cosmic toolbar, even when the phone
        // is in light mode. AppCompat otherwise tints icons dark-on-dark.
        listOf(R.id.add_config, R.id.import_clipboard).forEach { id ->
            menu.findItem(id)?.icon?.mutate()?.setTint(Color.WHITE)
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_clipboard -> {
            importFromClipboard()
            true
        }

        R.id.add_config -> {
            showAddConfigMenu(item)
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

    private fun showAddConfigMenu(item: MenuItem) {
        val anchor = findViewById<View>(item.itemId) ?: binding.toolbar
        androidx.appcompat.widget.PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.menu_item_import_config_qrcode)
            menu.add(0, 2, 1, R.string.menu_item_import_config_manual)
            menu.add(0, 3, 2, R.string.menu_item_import_config_clipboard)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> {
                        importFromQrCode()
                        true
                    }
                    2 -> {
                        showManualImportDialog()
                        true
                    }
                    3 -> {
                        importFromClipboard()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun importFromQrCode() {
        launchQRCodeScanner { scanResult ->
            val text = scanResult?.trim().orEmpty()
            if (text.isEmpty()) return@launchQRCodeScanner
            importConfigText(text)
        }
    }

    private fun showManualImportDialog() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val input = android.widget.EditText(this).apply {
            minLines = 3
            maxLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            hint = getString(R.string.hint_add_config_manual)
            setText(runCatching { Utils.getClipboard(this@MainActivity) }.getOrNull().orEmpty())
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.title_add_config_manual)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    toastError(R.string.toast_none_data)
                } else {
                    importConfigText(text)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importFromClipboard() {
        val clipboard = runCatching { Utils.getClipboard(this) }.getOrNull()?.trim().orEmpty()
        if (clipboard.isEmpty()) {
            toastError(R.string.toast_none_data_clipboard)
            return
        }
        importConfigText(clipboard)
    }

    private fun importConfigText(raw: String) {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val (count, countSub) = runCatching {
                AngConfigManager.importBatchConfig(raw, "", false)
            }.getOrElse { 0 to 0 }
            withContext(Dispatchers.Main) {
                hideLoading()
                if (count + countSub > 0) {
                    if (count > 0) {
                        toast(getString(R.string.title_import_config_count, count))
                    } else {
                        toastSuccess(R.string.toast_success)
                    }
                    mainViewModel.reloadServerList()
                    setupGroupTab()
                    refreshSelectedProfile()
                    updateConnectButtonAvailability()
                } else {
                    toastError(R.string.toast_failure)
                }
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
        subscriptionCardAdapter.notifyProfileStatusChanged(guid)
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
