package com.v2ray.ang.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.navigation.NavigationView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppFeatures
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.SubscriptionUpdateError
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppUpdateInstaller
import com.v2ray.ang.handler.AppUpdateScheduler
import com.v2ray.ang.handler.LampaSubscriptionMetadata
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionRefreshManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.handler.SubscriptionUrlResolver
import com.v2ray.ang.util.LampaErrorMessages
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    companion object {
        const val EXTRA_PAYMENT_SUCCESS = "payment_success"
        const val EXTRA_PAYMENT_SUB_ID = "payment_sub_id"

        /** Unblocks the power button if the daemon never answers START/STOP. */
        private const val TOGGLE_ACK_TIMEOUT_MS = 4_000L
        /** Swallow mash taps so they do not become Stop 100ms after Start succeeded. */
        private const val START_MASH_GUARD_MS = 800L
        private const val TRIAL_SUB_ID_KEY = "LAMPA_TRIAL_SUB_ID"
        private const val TRIAL_GUID_KEY = "LAMPA_TRIAL_GUID"
    }
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var subscriptionCardAdapter: SubscriptionCardAdapter
    private var powerRingAnimator: ObjectAnimator? = null
    private var lastAppliedRunningState: Boolean? = null
    private var winterEffectsActive = false
    /** True while the cinematic shield launch is in flight (delays frost until impact). */
    private var shieldCeremonyActive = false
    private var shieldIconSeated = false
    private var toggleAckTimeoutJob: Job? = null
    /** Offer a pre-downloaded APK install dialog at most once per MainActivity instance. */
    private var offeredReadyUpdate = false
    /** Start connect animation only after the system VPN consent screen, if it was needed. */
    private var connectAfterVpnPermission = false

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK && connectAfterVpnPermission) {
            connectAfterVpnPermission = false
            startConnect()
        } else {
            connectAfterVpnPermission = false
            mainViewModel.settleToggle()
            applyRunningState(false, mainViewModel.isRunning.value == true)
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
        setupToolbar(
            binding.toolbar,
            false,
            if (AppFeatures.isConsumerBuild) "" else getString(R.string.app_name),
        )
        setupSnowAnimation()

        subscriptionCardAdapter = SubscriptionCardAdapter(object : SubscriptionCardAdapter.Listener {
            override fun onSelectProfile(guid: String) = selectProfile(guid)
            override fun onViewProfileConfig(guid: String) {
                if (!AppFeatures.allowConfigView()) return
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
                if (AppFeatures.isConsumerBuild) {
                    showSubscriptionManageDialog(subscriptionId)
                } else {
                    requestActivityLauncher.launch(
                        Intent(this@MainActivity, SubEditActivity::class.java).putExtra("subId", subscriptionId)
                    )
                }
            }
            override fun onSelectSubscription(subscription: SubscriptionCache) {
                selectSubscription(subscription)
            }
            override fun onRenewSubscription(subscription: SubscriptionCache) {
                openSubscriptionRenewal(subscription)
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
        applyConsumerUi()
        setupSplitTunnelCard()
        binding.btnPaymentSuccessDone.setOnClickListener {
            binding.paymentSuccessOverlay.visibility = View.GONE
        }
        handlePaymentReturn(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePaymentReturn(intent)
    }

    private fun handlePaymentReturn(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_PAYMENT_SUCCESS, false) != true) return
        intent.removeExtra(EXTRA_PAYMENT_SUCCESS)
        val paidSubId = intent.getStringExtra(EXTRA_PAYMENT_SUB_ID).orEmpty()
        intent.removeExtra(EXTRA_PAYMENT_SUB_ID)
        RenewSubscriptionActivity.markPaymentThanksShown()

        binding.tvPaymentSuccessTitle.setText(R.string.subscription_payment_success_title)
        binding.tvPaymentSuccessMessage.setText(R.string.subscription_payment_success_confirming)
        binding.paymentSuccessOverlay.visibility = View.VISIBLE

        refreshAfterSuccessfulPayment(paidSubId)
    }

    private fun refreshAfterSuccessfulPayment(paidSubId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            var updated = false
            val beforeExpire = resolvePaidSubscription(paidSubId)?.subscription?.expireAt ?: 0L
            run {
                repeat(6) { attempt ->
                    if (attempt > 0) delay(1500)
                    val target = resolvePaidSubscription(paidSubId) ?: return@repeat
                    val result = AngConfigManager.updateConfigViaSub(target)
                    SubscriptionUpdater.syncOne(subId = target.guid)
                    val subId = SubscriptionUrlResolver.extractSubId(target.subscription.url)
                    if (!subId.isNullOrBlank()) {
                        LampaSubscriptionMetadata.refreshFromApi(subId)?.let { snapshot ->
                            LampaSubscriptionMetadata.applySnapshot(target.subscription, snapshot)
                            MmkvManager.encodeSubscription(target.guid, target.subscription)
                        }
                    }
                    val afterExpire = resolvePaidSubscription(paidSubId)?.subscription?.expireAt ?: 0L
                    val configsOk = result.successCount > 0 || result.configCount > 0
                    if (configsOk) {
                        updated = true
                        if (beforeExpire <= 0L || afterExpire > beforeExpire || attempt == 5) {
                            return@run
                        }
                    }
                }
            }
            if (updated && resolvePaidSubscription(paidSubId)?.subscription?.isTrial != true) {
                removeLocalTrialSubscriptions()
            }
            withContext(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                if (::subscriptionCardAdapter.isInitialized) {
                    subscriptionCardAdapter.reload()
                }
                refreshSelectedProfile()
                binding.tvPaymentSuccessMessage.setText(
                    if (updated) R.string.subscription_payment_success_message
                    else R.string.subscription_payment_success_update_later,
                )
            }
        }
    }

    private fun resolvePaidSubscription(paidSubId: String): SubscriptionCache? {
        val all = MmkvManager.decodeSubscriptions()
        if (paidSubId.isNotBlank()) {
            all.firstOrNull { SubscriptionUrlResolver.extractSubId(it.subscription.url) == paidSubId }
                ?.let { return it }
        }
        return all.firstOrNull { it.subscription.url.isNotBlank() }
    }

    private fun applyConsumerUi() {
        if (!AppFeatures.isConsumerBuild) return
        binding.layoutTest.visibility = View.GONE
        binding.animationToggleBar.visibility = View.GONE
        binding.drawerLayout.setDrawerLockMode(
            androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        )
        binding.toolbar.navigationIcon = null
        binding.toolbar.title = ""
        title = ""
    }

    private fun isLocalTrialSubscription(cache: SubscriptionCache): Boolean {
        val storedSubId = MmkvManager.decodeSettingsString(TRIAL_SUB_ID_KEY).orEmpty()
        val storedGuid = MmkvManager.decodeSettingsString(TRIAL_GUID_KEY).orEmpty()
        return cache.subscription.isTrial ||
            (storedGuid.isNotBlank() && cache.guid == storedGuid) ||
            (storedSubId.isNotBlank() &&
                SubscriptionUrlResolver.extractSubId(cache.subscription.url) == storedSubId)
    }

    private fun removeLocalTrialSubscriptions() {
        MmkvManager.decodeSubscriptions()
            .filter(::isLocalTrialSubscription)
            .forEach { SettingsManager.removeSubscriptionWithDefault(it.guid) }
        MmkvManager.encodeSettings(TRIAL_SUB_ID_KEY, "")
        MmkvManager.encodeSettings(TRIAL_GUID_KEY, "")
    }

    private fun setupSplitTunnelCard() {
        MmkvManager.ensureSplitTunnelDefaults()
        binding.splitTunnelCard.visibility = View.VISIBLE
        binding.splitTunnelCard.setOnClickListener {
            requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
        }
        refreshSplitTunnelSummary()
    }

    private fun refreshSplitTunnelSummary() {
        val count = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.size ?: 0
        val bypass = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, true)
        binding.tvSplitTunnelSummary.text = when {
            count <= 0 -> getString(R.string.split_tunnel_summary_all)
            bypass -> getString(R.string.split_tunnel_summary_bypass, count)
            else -> getString(R.string.split_tunnel_summary_proxy, count)
        }
    }

    private fun selectSubscription(subscription: SubscriptionCache) {
        val profiles = MmkvManager.decodeServerList(subscription.guid)
        val firstGuid = profiles.firstOrNull()
        if (firstGuid.isNullOrEmpty()) {
            toast(R.string.subscription_select_first)
            updateSubscription(subscription)
            return
        }
        selectProfile(firstGuid)
        subscriptionCardAdapter.setActiveSubscription(subscription.guid)
    }

    private fun openSubscriptionRenewal(subscription: SubscriptionCache) {
        val subId = SubscriptionUrlResolver.extractSubId(subscription.subscription.url)
        if (!subId.isNullOrBlank()) {
            RenewSubscriptionActivity.launch(this, subId)
            return
        }
        val support = subscription.subscription.supportUrl?.trim().orEmpty()
        val target = support.ifBlank { AppConfig.LAMPA_TELEGRAM_URL }
        Utils.openUri(this, target)
    }

    private fun showSubscriptionManageDialog(subscriptionId: String) {
        val item = MmkvManager.decodeSubscription(subscriptionId) ?: return
        val cache = SubscriptionCache(subscriptionId, item)
        val options = if (AppFeatures.isConsumerBuild) {
            arrayOf(
                getString(R.string.subscription_renew),
                getString(R.string.title_sub_update),
                getString(R.string.menu_item_del_config),
            )
        } else {
            arrayOf(
                getString(R.string.title_sub_update),
                getString(R.string.subscription_renew),
                getString(R.string.menu_item_del_config),
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.subscription_manage)
            .setItems(options) { _, which ->
                if (AppFeatures.isConsumerBuild) {
                    when (which) {
                        0 -> openSubscriptionRenewal(cache)
                        1 -> updateSubscription(cache)
                        2 -> confirmDeleteSubscription(subscriptionId)
                    }
                } else {
                    when (which) {
                        0 -> updateSubscription(cache)
                        1 -> openSubscriptionRenewal(cache)
                        2 -> confirmDeleteSubscription(subscriptionId)
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteSubscription(subscriptionId: String) {
        AlertDialog.Builder(this)
            .setMessage(R.string.subscription_delete_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                SettingsManager.removeSubscriptionWithDefault(subscriptionId)
                mainViewModel.reloadServerList()
                subscriptionCardAdapter.reload()
                refreshSelectedProfile()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                binding.powerGlow.animate().alpha(1f).setDuration(220L).start()
                startConnectedPulseIfNeeded()
            }
        } else {
            cancelShieldCeremony()
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
        return binding.powerLoadingRing.visibility == View.VISIBLE
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
        mainViewModel.geoDataErrorAction.observe(this) { message ->
            if (message.isNullOrBlank()) return@observe
            AlertDialog.Builder(this)
                .setTitle(R.string.toast_services_failure)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        mainViewModel.isRunning.observe(this) { isRunning ->
            cancelToggleAckTimeout()
            applyRunningState(false, isRunning)
            if (isRunning == true) {
                subscriptionCardAdapter.notifyProfileStatusChanged()
            }
        }
        mainViewModel.togglePending.observe(this) { pending ->
            if (pending != true) {
                cancelToggleAckTimeout()
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
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
        val currentlyRunning = mainViewModel.isRunning.value == true
        if (mainViewModel.togglePending.value == true) {
            // Extra Start taps while connecting race TUN close. A stuck
            // "connected" UI must still accept Stop so the user is not trapped.
            if (!currentlyRunning) return
        }

        if (!currentlyRunning && !hasSelectedProfile()) {
            updateConnectButtonAvailability()
            return
        }

        if (currentlyRunning) {
            if (mainViewModel.shouldIgnoreStopMash(START_MASH_GUARD_MS)) {
                return
            }
            mainViewModel.beginToggle()
            applyRunningState(isLoading = true, isRunning = true)
            CoreServiceManager.stopVService(this)
            scheduleToggleAckTimeout()
            return
        }

        if (SettingsManager.isVpnMode()) {
            val consent = VpnService.prepare(this)
            if (consent != null) {
                connectAfterVpnPermission = true
                requestVpnPermission.launch(consent)
                return
            }
        }
        startConnect()
    }

    private fun startConnect() {
        mainViewModel.beginToggle()
        applyRunningState(isLoading = true, isRunning = false)
        startV2Ray()
        scheduleToggleAckTimeout()
    }

    private fun scheduleToggleAckTimeout() {
        cancelToggleAckTimeout()
        toggleAckTimeoutJob = lifecycleScope.launch {
            delay(TOGGLE_ACK_TIMEOUT_MS)
            if (mainViewModel.togglePending.value != true) return@launch
            LogUtil.w(AppConfig.TAG, "VPN toggle ack timed out; unlocking button")
            mainViewModel.forceToggleIdle(running = false)
        }
    }

    private fun cancelToggleAckTimeout() {
        toggleAckTimeoutJob?.cancel()
        toggleAckTimeoutJob = null
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
            mainViewModel.settleToggle()
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
        binding.frostOverlay.setFrozenImmediate(false)
        binding.animationSnow.animate().cancel()
        binding.animationSnow.cancelAnimation()
        binding.animationSnow.alpha = 0f
        binding.animationSnow.visibility = View.GONE
    }

    private fun setSnowAnimationEnabled(enabled: Boolean) {
        setWinterEffectsEnabled(enabled, animate = true)
    }

    private fun setWinterEffectsEnabled(enabled: Boolean, animate: Boolean = true) {
        winterEffectsActive = false
        binding.frostOverlay.melt()
        binding.frostOverlay.setFrozenImmediate(false)
        binding.animationSnow.animate().cancel()
        binding.animationSnow.cancelAnimation()
        binding.animationSnow.alpha = 0f
        binding.animationSnow.visibility = View.GONE
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
     * Flip the dock face: empty cradle ↔ powered seated shield.
     */
    private fun playPowerIconFlip(
        powered: Boolean,
        onSettled: (() -> Unit)? = null
    ) {
        val content = binding.powerBtnContent
        content.animate().cancel()
        content.cameraDistance = 12_000f * resources.displayMetrics.density

        content.animate()
            .rotationY(90f)
            .setDuration(170L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding.ivFabIcon.setPowered(powered, animate = false)
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
        if (enabled) {
            binding.powerLoadingRing.visibility = View.VISIBLE
            binding.powerLoadingRing.alpha = 1f
            startPowerLoadingRingSpin()
        } else {
            stopPowerLoadingRingSpin()
            binding.powerLoadingRing.rotation = 0f
            binding.powerLoadingRing.alpha = 0f
            binding.powerLoadingRing.visibility = View.INVISIBLE
        }
    }

    private fun startPowerLoadingRingSpin() {
        if (powerRingAnimator?.isRunning == true) return
        powerRingAnimator?.cancel()
        powerRingAnimator = ObjectAnimator.ofFloat(binding.powerLoadingRing, View.ROTATION, 0f, 360f).apply {
            duration = 1_100L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopPowerLoadingRingSpin() {
        powerRingAnimator?.cancel()
        powerRingAnimator = null
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        val winterOn = isWinterAnimationsEnabled()
        if (isLoading) {
            updateConnectButtonAvailability(isRunning)
            setPowerLoading(true)
            binding.fab.clearAnimation()
            binding.powerBtnContent.animate().cancel()
            binding.powerBtnContent.rotationY = 0f
            if (!shieldCeremonyActive) {
                // Empty cradle while connecting / shield is still flying in.
                binding.ivFabIcon.setPowered(false, animate = false)
            }
            if (winterOn) {
                binding.powerGlow.animate().alpha(0.45f).setDuration(220L).start()
            } else {
                binding.powerGlow.animate().cancel()
                binding.powerGlow.alpha = 0f
            }
            binding.fab.contentDescription = getString(R.string.zeus_status_connecting)
            // Stay unfilled while connecting / shield is still in flight.
            binding.fab.setBackgroundResource(R.drawable.bg_power_btn_inactive)
            // Connect ceremony: 3D shield launch, then ice cracks on impact.
            if (lastAppliedRunningState != true) {
                if (winterOn) {
                    winterEffectsActive = true
                    playShieldConnectCeremony()
                }
            } else {
                cancelShieldCeremony()
                setWinterEffectsEnabled(false, animate = winterOn)
            }
            return
        }

        // Keep the waiting ring spinning until the shield ceremony finishes.
        if (shieldCeremonyActive) {
            setPowerLoading(true)
        } else {
            setPowerLoading(false)
        }

        refreshSelectedProfile()
        updateConnectButtonAvailability(isRunning)

        val stateChanged = lastAppliedRunningState != isRunning
        lastAppliedRunningState = isRunning

        if (isRunning) {
            // Dark chrome while empty; teal connected chrome when shield is seated.
            val buttonFilled = isRunning && (!shieldCeremonyActive || shieldIconSeated)
            binding.fab.setBackgroundResource(
                if (buttonFilled) R.drawable.bg_power_btn_connected
                else R.drawable.bg_power_btn_inactive
            )
            if (winterOn) {
                val glowTarget = if (buttonFilled) 1f else 0.45f
                binding.powerGlow.animate().alpha(glowTarget).setDuration(320L).start()
            } else {
                binding.powerGlow.animate().cancel()
                binding.powerGlow.alpha = 0f
                binding.fab.clearAnimation()
            }

            if (stateChanged) {
                if (!winterOn) {
                    setWinterEffectsEnabled(false, animate = false)
                }
                setTestState(getString(R.string.connection_connected))
                // Scale bounce fights punchPowerButton during the shield landing.
                if (!shieldCeremonyActive) {
                    playSoftStateTransition()
                }
                when {
                    shieldCeremonyActive && !shieldIconSeated -> {
                        // Empty dock while the shield flies; outer ring shows loading.
                        binding.ivFabIcon.setPowered(false, animate = false)
                        binding.ivFabIcon.alpha = 1f
                    }
                    shieldCeremonyActive && shieldIconSeated -> {
                        // Already revealed under the landing shield — don't flip/re-animate.
                        binding.ivFabIcon.setPowered(true, animate = false)
                        binding.ivFabIcon.alpha = 1f
                    }
                    shieldIconSeated -> {
                        binding.ivFabIcon.setPowered(true, animate = true)
                        binding.ivFabIcon.alpha = 1f
                        if (winterOn && !isPowerLoading()) {
                            startConnectedPulseIfNeeded()
                        }
                    }
                    else -> {
                        playPowerIconFlip(powered = true) {
                            if (winterOn && !isPowerLoading()) {
                                startConnectedPulseIfNeeded()
                            }
                        }
                    }
                }
            } else {
                // Running state can be emitted more than once while the connect ceremony is
                // still in flight. Do not let a duplicate emission reveal the dock shield
                // before ShieldLaunchOverlayView reports the actual landing impact.
                binding.ivFabIcon.setPowered(
                    on = !shieldCeremonyActive || shieldIconSeated,
                    animate = false
                )
                if (winterOn && (!shieldCeremonyActive || shieldIconSeated)) {
                    startConnectedPulseIfNeeded()
                }
            }

            binding.fab.contentDescription = getString(R.string.action_stop_service)
            binding.layoutTest.isFocusable = true
        } else {
            cancelShieldCeremony()
            shieldIconSeated = false
            binding.fab.setBackgroundResource(R.drawable.bg_power_btn_inactive)
            binding.fab.clearAnimation()
            binding.powerGlow.animate().alpha(0f).setDuration(260L).start()

            setWinterEffectsEnabled(false, animate = winterOn)
            if (stateChanged) {
                playSoftStateTransition()
                playPowerIconFlip(powered = false)
            } else {
                binding.ivFabIcon.setPowered(false, animate = false)
            }

            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
        }
    }

    /**
     * Shield flies out in 3D, orbits, then slams into the button — ice lines start on impact.
     */
    private fun playShieldConnectCeremony() {
        // Debounce: never restart while a ceremony is already in flight.
        if (shieldCeremonyActive || binding.shieldLaunchOverlay.isPlaying) return
        cancelShieldCeremony()
        shieldCeremonyActive = true
        shieldIconSeated = false
        setPowerLoading(true)

        binding.ivFabIcon.animate().cancel()
        // Keep the empty tech dock visible while the shield flies.
        binding.ivFabIcon.setPowered(false, animate = false)
        binding.ivFabIcon.alpha = 1f
        binding.fab.animate().cancel()
        binding.fab.setBackgroundResource(R.drawable.bg_power_btn_inactive)
        binding.fab.alpha = 1f

        // Keep snow hidden until the shield seats — only frost starts with impact.
        binding.animationSnow.animate().cancel()
        binding.animationSnow.cancelAnimation()
        binding.animationSnow.alpha = 0f
        binding.animationSnow.visibility = View.GONE

        binding.shieldLaunchOverlay.play(
            binding.fab,
            onImpact = {
                shieldIconSeated = true
                setPowerLoading(false)
                binding.root.post {
                    if (!shieldIconSeated) return@post
                    seatConnectedButtonLook()
                    binding.ivFabIcon.setPowered(true, animate = false)
                    binding.ivFabIcon.alpha = 1f
                    punchPowerButton()
                }
                binding.root.postDelayed({
                    if (!shieldIconSeated || !isWinterAnimationsEnabled()) return@postDelayed
                    binding.frostOverlay.freezeFrom(binding.fab, binding.layoutTest)
                }, 200L)
            },
            onEnd = {
                shieldCeremonyActive = false
                setPowerLoading(false)
                if (!shieldIconSeated) {
                    shieldIconSeated = true
                    seatConnectedButtonLook()
                    binding.ivFabIcon.setPowered(true, animate = false)
                }
                binding.ivFabIcon.alpha = 1f
                if (mainViewModel.isRunning.value == true && isWinterAnimationsEnabled()) {
                    startConnectedPulseIfNeeded()
                }
            }
        )
    }

    private fun seatConnectedButtonLook() {
        binding.fab.setBackgroundResource(R.drawable.bg_power_btn_connected)
        if (isWinterAnimationsEnabled()) {
            binding.powerGlow.animate().cancel()
            binding.powerGlow.animate().alpha(1f).setDuration(180L).start()
        }
    }

    private fun cancelShieldCeremony() {
        if (binding.shieldLaunchOverlay.isPlaying || shieldCeremonyActive) {
            binding.shieldLaunchOverlay.cancel()
        }
        shieldCeremonyActive = false
        setPowerLoading(false)
        binding.ivFabIcon.animate().cancel()
        binding.ivFabIcon.alpha = 1f
        binding.ivFabIcon.setPowered(mainViewModel.isRunning.value == true, animate = false)
        // If cancelled mid-open, restore a real button face.
        if (binding.fab.background == null) {
            binding.fab.setBackgroundResource(
                if (mainViewModel.isRunning.value == true) {
                    R.drawable.bg_power_btn_connected
                } else {
                    R.drawable.bg_power_btn_inactive
                }
            )
        }
    }

    private fun punchPowerButton() {
        binding.fab.clearAnimation()
        binding.fab.animate().cancel()
        binding.powerGlow.animate().cancel()
        binding.powerGlow.alpha = 1f
        // Single settle — chained scales + glow tween stole frames from the landing handoff.
        binding.fab.scaleX = 0.92f
        binding.fab.scaleY = 0.92f
        binding.fab.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(140L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun ensureSnowVisible(animate: Boolean) {
        binding.animationSnow.animate().cancel()
        binding.animationSnow.cancelAnimation()
        binding.animationSnow.alpha = 0f
        binding.animationSnow.visibility = View.GONE
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

    override fun onStart() {
        super.onStart()
        resumeWinterEffectsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.requestServiceState()
        refreshSelectedProfile()
        updateConnectButtonAvailability()
        if (::subscriptionCardAdapter.isInitialized) subscriptionCardAdapter.reload()
        refreshSubscriptionsOnAppOpen()
        refreshLampaSubscriptionMetadata()
        AppUpdateScheduler.checkIfDue(this)
        AppUpdateInstaller.resumePendingDownloadIfNeeded(this)
        maybeOfferReadyUpdate()
        if (::subscriptionCardAdapter.isInitialized) {
            refreshSplitTunnelSummary()
        }
    }

    private fun maybeOfferReadyUpdate() {
        if (offeredReadyUpdate) return
        val ready = AppUpdateInstaller.getReadyUpdate(this) ?: return
        offeredReadyUpdate = true
        val notes = ready.releaseNotes?.trim().orEmpty()
        val message = if (notes.isNotEmpty()) {
            notes
        } else {
            getString(R.string.update_ready_to_install, ready.version)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_new_version_found, ready.version))
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ ->
                installReadyUpdate(ready)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun installReadyUpdate(ready: AppUpdateInstaller.ReadyUpdate) {
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
            // Allow the dialog again after the user returns from settings.
            offeredReadyUpdate = false
            return
        }
        if (!AppUpdateInstaller.launchDownloadedApk(this, ready.apkFile)) {
            toastError(R.string.update_install_launch_failed)
        }
    }

    override fun onPause() {
        pauseWinterEffects()
        super.onPause()
    }

    override fun onStop() {
        // Screen-off / background — ensure nothing keeps churning frames.
        pauseWinterEffects()
        super.onStop()
    }

    private fun pauseWinterEffects() {
        binding.shieldLaunchOverlay.pause()
        binding.frostOverlay.pause()
        binding.ivFabIcon.pauseMotion()
        if (binding.animationSnow.isAnimating) {
            binding.animationSnow.pauseAnimation()
        }
        // Connecting ring is infinite — pause so it doesn't spin while minimized.
        powerRingAnimator?.takeIf { it.isRunning }?.pause()
    }

    private fun resumeWinterEffectsIfNeeded() {
        binding.shieldLaunchOverlay.resume()
        binding.frostOverlay.resume()
        binding.ivFabIcon.resumeMotion()
        if (winterEffectsActive &&
            isWinterAnimationsEnabled() &&
            binding.animationSnow.visibility == View.VISIBLE &&
            !binding.animationSnow.isAnimating
        ) {
            binding.animationSnow.resumeAnimation()
        }
        powerRingAnimator?.takeIf { it.isPaused }?.resume()
    }

    private fun refreshLampaSubscriptionMetadata() {
        if (!AppFeatures.isConsumerBuild) return
        lifecycleScope.launch(Dispatchers.IO) {
            var changed = false
            MmkvManager.decodeSubscriptions().forEach { cache ->
                val subId = SubscriptionUrlResolver.extractSubId(cache.subscription.url) ?: return@forEach
                val snapshot = LampaSubscriptionMetadata.refreshFromApi(subId) ?: return@forEach
                LampaSubscriptionMetadata.applySnapshot(cache.subscription, snapshot)
                MmkvManager.encodeSubscription(cache.guid, cache.subscription)
                changed = true
            }
            if (changed) {
                withContext(Dispatchers.Main) {
                    subscriptionCardAdapter.reload()
                }
            }
        }
    }

    private fun syncLampaSubscriptionMetadata(subscription: SubscriptionCache) {
        if (!AppFeatures.isConsumerBuild) return
        val subId = SubscriptionUrlResolver.extractSubId(subscription.subscription.url) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val snapshot = LampaSubscriptionMetadata.refreshFromApi(subId) ?: return@launch
            LampaSubscriptionMetadata.applySnapshot(subscription.subscription, snapshot)
            MmkvManager.encodeSubscription(subscription.guid, subscription.subscription)
            withContext(Dispatchers.Main) {
                subscriptionCardAdapter.reload()
            }
        }
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (AppFeatures.isConsumerBuild) {
            menu.add(0, R.id.add_config, 0, R.string.subscription_import)
                .setIcon(R.drawable.ic_add_24dp)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, R.id.import_clipboard, 1, R.string.menu_item_import_config_clipboard)
                .setIcon(R.drawable.ic_content_paste_24dp)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            listOf(R.id.add_config, R.id.import_clipboard).forEach { id ->
                menu.findItem(id)?.icon?.mutate()?.setTint(Color.WHITE)
            }
            return true
        }
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
            menu.add(0, 1, 0, R.string.menu_item_import_config_clipboard)
            menu.add(0, 2, 1, R.string.menu_item_import_config_qrcode)
            menu.add(0, 3, 2, R.string.menu_item_import_config_manual)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> {
                        importFromClipboard()
                        true
                    }
                    2 -> {
                        importFromQrCode()
                        true
                    }
                    3 -> {
                        showManualImportDialog()
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
                    val importingKnownTrial = MmkvManager.decodeSubscriptions()
                        .filter(::isLocalTrialSubscription)
                        .any { it.subscription.url.trim() == raw.trim() }
                    if (!importingKnownTrial) removeLocalTrialSubscriptions()
                    if (count > 0) {
                        toast(getString(R.string.title_import_config_count, count))
                    } else {
                        toastSuccess(R.string.toast_success)
                    }
                    mainViewModel.reloadServerList()
                    setupGroupTab()
                    refreshSelectedProfile()
                    updateConnectButtonAvailability()
                    if (AppFeatures.isConsumerBuild) {
                        selectFirstAvailableSubscription()
                    }
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
                hideLoading()
                when {
                    result.successCount > 0 -> {
                        syncLampaSubscriptionMetadata(subscription)
                        toast(getString(R.string.title_update_config_count, result.configCount))
                    }
                    result.error != null || result.failureCount > 0 -> {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.title_sub_update)
                            .setMessage(
                                LampaErrorMessages.subscriptionUpdate(
                                    this@MainActivity,
                                    result.error ?: SubscriptionUpdateError.UNKNOWN,
                                    result.errorDetail,
                                ),
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    result.skipCount > 0 -> {
                        toastError(R.string.subscription_update_error_disabled)
                    }
                    else -> {
                        toastError(R.string.toast_failure)
                    }
                }
                mainViewModel.reloadServerList()
                subscriptionCardAdapter.reload()
            }
        }
    }

    private fun selectFirstAvailableSubscription() {
        val subscription = MmkvManager.decodeSubscriptions()
            .firstOrNull { it.subscription.url.isNotBlank() } ?: return
        selectSubscription(subscription)
    }

    private fun selectProfile(guid: String) {
        if (MmkvManager.getSelectServer() == guid) return
        MmkvManager.setSelectServer(guid)
        refreshSelectedProfile()
        updateConnectButtonAvailability()
        subscriptionCardAdapter.notifyProfileStatusChanged(guid)
        subscriptionCardAdapter.setActiveSubscription(
            MmkvManager.decodeServerConfig(guid)?.subscriptionId
        )
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
        if (AppFeatures.isConsumerBuild) return false
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
