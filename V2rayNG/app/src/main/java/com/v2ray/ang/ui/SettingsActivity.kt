package com.v2ray.ang.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.appcompat.app.AlertDialog
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.PriorityFailoverManager
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MmkvPreferenceDataStore
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.core.ConnectionJournal
import com.v2ray.ang.util.FailureLogRecorder
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(R.layout.activity_settings, showHomeAsUp = true, title = getString(R.string.title_settings))
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val localDns by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_LOCAL_DNS_ENABLED) }
        private val fakeDns by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_FAKE_DNS_ENABLED) }
        private val appendHttpProxy by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_APPEND_HTTP_PROXY) }

        //        private val localDnsPort by lazy { findPreference<EditTextPreference>(AppConfig.PREF_LOCAL_DNS_PORT) }
        private val vpnDns by lazy { findPreference<EditTextPreference>(AppConfig.PREF_VPN_DNS) }
        private val vpnBypassLan by lazy { findPreference<ListPreference>(AppConfig.PREF_VPN_BYPASS_LAN) }
        private val vpnInterfaceAddress by lazy { findPreference<ListPreference>(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX) }

        private val mux by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_MUX_ENABLED) }
        private val muxConcurrency by lazy { findPreference<EditTextPreference>(AppConfig.PREF_MUX_CONCURRENCY) }
        private val muxXudpConcurrency by lazy { findPreference<EditTextPreference>(AppConfig.PREF_MUX_XUDP_CONCURRENCY) }
        private val muxXudpQuic by lazy { findPreference<ListPreference>(AppConfig.PREF_MUX_XUDP_QUIC) }

        private val fragment by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_FRAGMENT_ENABLED) }
        private val fragmentPackets by lazy { findPreference<ListPreference>(AppConfig.PREF_FRAGMENT_PACKETS) }
        private val fragmentLength by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_LENGTH) }
        private val fragmentInterval by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_INTERVAL) }
        private val fragmentMaxSplit by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_MAXSPLIT) }

        private val mode by lazy { findPreference<ListPreference>(AppConfig.PREF_MODE) }
        private val enableRootMode by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_ROOT_MODE_ENABLE) }
        private val lanSharing by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_ROOT_LAN_SHARING) }

        private val hevTunLogLevel by lazy { findPreference<ListPreference>(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL) }
        private val hevTunRwTimeout by lazy { findPreference<EditTextPreference>(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) }
        private val resetSettings by lazy { findPreference<Preference>(AppConfig.PREF_RESET_SETTINGS) }

        private val enableLocalProxy by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_ENABLE_LOCAL_PROXY) }
        private val socksPort by lazy { findPreference<EditTextPreference>(AppConfig.PREF_SOCKS_PORT) }
        private val dynamicSocksPort by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_DYNAMIC_SOCKS_PORT) }
        private val socksUsername by lazy { findPreference<EditTextPreference>(AppConfig.PREF_SOCKS_USERNAME) }
        private val socksPassword by lazy { findPreference<EditTextPreference>(AppConfig.PREF_SOCKS_PASSWORD) }
        private val socksEnableUdp by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_SOCKS_ENABLE_UDP) }
        private val proxySharing by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_PROXY_SHARING) }
        private val connectionDiagnostics by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_CONNECTION_DIAGNOSTICS_ENABLED) }
        private val connectionJournal by lazy { findPreference<Preference>(AppConfig.PREF_CONNECTION_JOURNAL) }
        private val failureLogEnabled by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_FAILURE_LOG_ENABLED) }
        private val failureLogView by lazy { findPreference<Preference>(AppConfig.PREF_FAILURE_LOG_VIEW) }
        private val priorityProbeIntervals by lazy {
            findPreference<Preference>(AppConfig.PREF_PRIORITY_PROBE_INTERVALS)
        }
        private val showBatteryUsage by lazy {
            findPreference<CheckBoxPreference>(AppConfig.PREF_NOTIFICATION_SHOW_BATTERY_USAGE)
        }
        private val showRoutingMode by lazy {
            findPreference<CheckBoxPreference>(AppConfig.PREF_NOTIFICATION_SHOW_ROUTING_MODE)
        }

        override fun onCreatePreferences(bundle: Bundle?, s: String?) {
            // Use MMKV as the storage backend for all Preferences
            // This prevents inconsistencies between SharedPreferences and MMKV
            preferenceManager.preferenceDataStore = MmkvPreferenceDataStore()

            addPreferencesFromResource(R.xml.pref_settings)

            initPreferenceSummaries()
            updatePriorityProbeIntervalsSummary()
            priorityProbeIntervals?.setOnPreferenceClickListener {
                showPriorityProbeIntervalsDialog()
                true
            }
            showBatteryUsage?.setOnPreferenceChangeListener { _, _ ->
                // Preference persists after this callback; post the daemon message
                // so its MMKV read observes the new value.
                requireActivity().window.decorView.post {
                    com.v2ray.ang.util.MessageUtil.sendMsg2Service(
                        requireContext(),
                        AppConfig.MSG_BATTERY_STATS_SETTING_CHANGED,
                        "",
                    )
                }
                true
            }
            showRoutingMode?.setOnPreferenceChangeListener { _, _ ->
                requireActivity().window.decorView.post {
                    NotificationManager.refreshConnectionDetails()
                }
                true
            }

            fun updateConnectionJournalAvailability(enabled: Boolean) {
                connectionJournal?.isEnabled = enabled
                connectionJournal?.summary = if (enabled) "Показать соединения, зафиксированные с момента включения." else "Сначала включите диагностику приложений."
            }
            updateConnectionJournalAvailability(connectionDiagnostics?.isChecked == true)
            connectionDiagnostics?.setOnPreferenceChangeListener { _, value ->
                val enabled = value as Boolean
                if (enabled) ConnectionJournal.clear()
                updateConnectionJournalAvailability(enabled)
                // The extra diagnostic routing rule is part of the generated
                // Xray JSON, so reload a running core after the preference has
                // been persisted by Preference.
                requireActivity().window.decorView.post {
                    if (CoreServiceManager.isRunning()) {
                        CoreServiceManager.reloadVService(requireContext())
                    }
                }
                true
            }
            connectionJournal?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Журнал соединений")
                    .setMessage(ConnectionJournal.summary())
                    .setNegativeButton("Закрыть", null)
                    .setPositiveButton("Очистить") { _, _ -> ConnectionJournal.clear() }
                    .show()
                true
            }

            fun updateFailureLogAvailability(enabled: Boolean) {
                failureLogView?.isEnabled = enabled
                failureLogView?.summary = if (enabled) {
                    getString(R.string.summary_pref_failure_log_view)
                } else {
                    "Сначала включите запись логов падений."
                }
            }
            updateFailureLogAvailability(failureLogEnabled?.isChecked == true)
            failureLogEnabled?.setOnPreferenceChangeListener { _, value ->
                val enabled = value as Boolean
                requireActivity().window.decorView.post {
                    FailureLogRecorder.refreshEnabled()
                    updateFailureLogAvailability(enabled)
                }
                true
            }
            failureLogView?.setOnPreferenceClickListener {
                showFailureLogDialog()
                true
            }

            localDns?.setOnPreferenceChangeListener { _, any ->
                updateLocalDns(any as Boolean)
                true
            }

            mux?.setOnPreferenceChangeListener { _, newValue ->
                updateMux(newValue as Boolean)
                true
            }
            muxConcurrency?.setOnPreferenceChangeListener { _, newValue ->
                updateMuxConcurrency(newValue as String)
                true
            }
            muxXudpConcurrency?.setOnPreferenceChangeListener { _, newValue ->
                updateMuxXudpConcurrency(newValue as String)
                true
            }

            fragment?.setOnPreferenceChangeListener { _, newValue ->
                updateFragment(newValue as Boolean)
                true
            }

            mode?.setOnPreferenceChangeListener { pref, newValue ->
                val valueStr = newValue.toString()
                (pref as? ListPreference)?.let { lp ->
                    val idx = lp.findIndexOfValue(valueStr)
                    lp.summary = if (idx >= 0) lp.entries[idx] else valueStr
                }
                updateMode(valueStr)
                true
            }

            mode?.dialogLayoutResource = R.layout.preference_with_help_link

            resetSettings?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.dialog_reset_settings_title)
                    .setMessage(R.string.dialog_reset_settings_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.title_pref_reset_settings) { _, _ ->
                        SettingsManager.resetSettingsToDefaults(requireContext())
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.fragment_settings, SettingsFragment())
                            .commitAllowingStateLoss()
                        requireContext().toastSuccess(R.string.toast_settings_reset)
                        if (CoreServiceManager.isRunning()) {
                            CoreServiceManager.reloadVService(requireContext())
                        }
                    }
                    .show()
                true
            }

            enableLocalProxy?.setOnPreferenceChangeListener { _, newValue ->
                updateEnableLocalProxy(newValue as Boolean)
                true
            }

            dynamicSocksPort?.setOnPreferenceChangeListener { _, newValue ->
                updateDynamicSocksPort(newValue as Boolean)
                true
            }

            enableRootMode?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true && !RootManager.cachedRoot()) {
                    lifecycleScope.launch {
                        if (checkAndRequestRoot()) {
                            enableRootMode?.isChecked = true
                        }
                    }
                    false
                } else {
                    true
                }
            }

            lanSharing?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true && !RootManager.cachedRoot()) {
                    lifecycleScope.launch {
                        if (checkAndRequestRoot()) {
                            lanSharing?.isChecked = true
                        }
                    }
                    false
                } else {
                    true
                }
            }

        }

        private fun initPreferenceSummaries() {
            fun updateSummary(pref: androidx.preference.Preference) {
                when (pref) {
                    is EditTextPreference -> {
                        if (pref.key == AppConfig.PREF_SOCKS_PASSWORD) {
                            pref.summary = if (pref.text.isNullOrEmpty()) "" else "******"
                        } else {
                            pref.summary = pref.text.orEmpty()
                        }
                        pref.setOnPreferenceChangeListener { p, newValue ->
                            if (p.key == AppConfig.PREF_SOCKS_PASSWORD) {
                                p.summary = if ((newValue as? String).isNullOrEmpty()) "" else "******"
                            } else {
                                p.summary = (newValue as? String).orEmpty()
                            }
                            true
                        }
                    }

                    is ListPreference -> {
                        pref.summary = pref.entry ?: ""
                        pref.setOnPreferenceChangeListener { p, newValue ->
                            val lp = p as ListPreference
                            val idx = lp.findIndexOfValue(newValue as? String)
                            lp.summary = (if (idx >= 0) lp.entries[idx] else newValue) as CharSequence?
                            true
                        }
                    }

                    is CheckBoxPreference, is androidx.preference.SwitchPreferenceCompat -> {
                    }
                }
            }

            fun traverse(group: androidx.preference.PreferenceGroup) {
                for (i in 0 until group.preferenceCount) {
                    when (val p = group.getPreference(i)) {
                        is androidx.preference.PreferenceGroup -> traverse(p)
                        else -> updateSummary(p)
                    }
                }
            }

            preferenceScreen?.let { traverse(it) }
        }

        private fun updatePriorityProbeIntervalsSummary() {
            val screenOn = SettingsManager.getPriorityProbeIntervalMs(screenOn = true) / 1_000L
            val screenOff = SettingsManager.getPriorityProbeIntervalMs(screenOn = false) / 1_000L
            priorityProbeIntervals?.summary = getString(
                R.string.summary_pref_priority_probe_intervals_value,
                screenOn,
                screenOff,
            )
        }

        private fun showPriorityProbeIntervalsDialog() {
            val content = layoutInflater.inflate(R.layout.dialog_priority_probe_intervals, null)
            val screenOn = content.findViewById<android.widget.EditText>(R.id.probe_interval_screen_on)
            val screenOff = content.findViewById<android.widget.EditText>(R.id.probe_interval_screen_off)
            screenOn.setText((SettingsManager.getPriorityProbeIntervalMs(true) / 1_000L).toString())
            screenOff.setText((SettingsManager.getPriorityProbeIntervalMs(false) / 1_000L).toString())

            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.title_pref_priority_probe_intervals)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val onSeconds = screenOn.text?.toString()?.toLongOrNull()
                    val offSeconds = screenOff.text?.toString()?.toLongOrNull()
                    if (onSeconds == null || onSeconds !in 2L..86_400L ||
                        offSeconds == null || offSeconds !in 10L..86_400L
                    ) {
                        requireContext().toastError(R.string.error_invalid_probe_intervals)
                        return@setOnClickListener
                    }
                    MmkvManager.encodeSettings(AppConfig.PREF_PRIORITY_PROBE_SCREEN_ON_SECONDS, onSeconds)
                    MmkvManager.encodeSettings(AppConfig.PREF_PRIORITY_PROBE_SCREEN_OFF_SECONDS, offSeconds)
                    updatePriorityProbeIntervalsSummary()
                    PriorityFailoverManager.onProbeIntervalsChanged()
                    dialog.dismiss()
                }
            }
            dialog.show()
        }

        private suspend fun checkAndRequestRoot(): Boolean {
            val hasRoot = RootManager.refresh()
            if (!isAdded) return false
            if (!hasRoot) {
                context?.toastError(R.string.toast_root_required)
            }
            return hasRoot
        }

        override fun onStart() {
            super.onStart()
            // Hev TUN is always forced on; keep local-proxy UI locked accordingly.
            updateHevTunSettings(true)

            // Initialize mode-dependent UI states
            updateMode(MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, VPN))

            // Initialize local proxy state
            updateEnableLocalProxy(MmkvManager.decodeSettingsBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true))

            // Initialize mux-dependent UI states
            updateMux(MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false))

            // Initialize fragment-dependent UI states
            updateFragment(MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false))

            updateDynamicSocksPort(MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false))
        }

        private fun updateMode(value: String?) {
            val vpn = value == VPN
            localDns?.isEnabled = vpn
            fakeDns?.isEnabled = vpn
            appendHttpProxy?.isEnabled = vpn
//            localDnsPort?.isEnabled = vpn
            vpnDns?.isEnabled = vpn
            vpnBypassLan?.isEnabled = vpn
            vpnInterfaceAddress?.isEnabled = vpn
            hevTunLogLevel?.isEnabled = vpn
            hevTunRwTimeout?.isEnabled = vpn
            if (vpn) {
                updateLocalDns(
                    MmkvManager.decodeSettingsBool(
                        AppConfig.PREF_LOCAL_DNS_ENABLED,
                        false
                    )
                )
                updateHevTunSettings(true)
            } else {
                updateHevTunSettings(false)
            }
        }

        private fun updateLocalDns(enabled: Boolean) {
            fakeDns?.isEnabled = enabled
//            localDnsPort?.isEnabled = enabled
            vpnDns?.isEnabled = !enabled
        }

        private fun updateMux(enabled: Boolean) {
            muxConcurrency?.isEnabled = enabled
            muxXudpConcurrency?.isEnabled = enabled
            muxXudpQuic?.isEnabled = enabled
            if (enabled) {
                updateMuxConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY, "8"))
                updateMuxXudpConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_CONCURRENCY, "8"))
            }
        }

        private fun updateMuxConcurrency(value: String?) {
            val concurrency = value?.toIntOrNull() ?: 8
            muxConcurrency?.summary = concurrency.toString()
        }


        private fun updateMuxXudpConcurrency(value: String?) {
            if (value == null) {
                muxXudpQuic?.isEnabled = true
            } else {
                val concurrency = value.toIntOrNull() ?: 8
                muxXudpConcurrency?.summary = concurrency.toString()
                muxXudpQuic?.isEnabled = concurrency >= 0
            }
        }

        private fun updateFragment(enabled: Boolean) {
            fragmentPackets?.isEnabled = enabled
            fragmentLength?.isEnabled = enabled
            fragmentInterval?.isEnabled = enabled
            fragmentMaxSplit?.isEnabled = enabled
        }

        private fun updateDynamicSocksPort(enabled: Boolean) {
            socksPort?.isEnabled = (enableLocalProxy?.isChecked == true) && !enabled
        }

        private fun updateEnableLocalProxy(enabled: Boolean) {
            val dynamic = MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
            socksPort?.isEnabled = enabled && !dynamic
            dynamicSocksPort?.isEnabled = enabled
            socksUsername?.isEnabled = enabled
            socksPassword?.isEnabled = enabled
            socksEnableUdp?.isEnabled = enabled
            proxySharing?.isEnabled = enabled

            if (!enabled) {
                if (appendHttpProxy?.isChecked == true) {
                    appendHttpProxy?.isChecked = false
                    MmkvManager.encodeSettings(AppConfig.PREF_APPEND_HTTP_PROXY, false)
                }
                appendHttpProxy?.isEnabled = false
            } else {
                val vpn = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) == VPN
                appendHttpProxy?.isEnabled = vpn
            }
        }

        private fun updateHevTunSettings(enabled: Boolean) {
            hevTunLogLevel?.isEnabled = enabled
            hevTunRwTimeout?.isEnabled = enabled

            if (enabled) {
                if (enableLocalProxy?.isChecked == false) {
                    enableLocalProxy?.isChecked = true
                    MmkvManager.encodeSettings(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)
                }
                enableLocalProxy?.isEnabled = false
            } else {
                enableLocalProxy?.isEnabled = true
            }
            updateEnableLocalProxy(enableLocalProxy?.isChecked == true)
        }

        private fun showFailureLogDialog() {
            val ctx = requireContext()
            AlertDialog.Builder(ctx)
                .setTitle(R.string.title_pref_failure_log_view)
                .setMessage(FailureLogRecorder.summary(ctx))
                .setNegativeButton("Закрыть", null)
                .setNeutralButton("Очистить") { _, _ ->
                    FailureLogRecorder.clear(ctx)
                    requireContext().toastSuccess("Логи падений очищены")
                }
                .setPositiveButton("Поделиться") { _, _ ->
                    shareLatestFailureLog()
                }
                .show()
        }

        private fun shareLatestFailureLog() {
            lifecycleScope.launch(Dispatchers.IO) {
                val file = FailureLogRecorder.exportLatest(requireContext())
                withContext(Dispatchers.Main) {
                    if (file == null) {
                        requireContext().toastError("Нет сохранённых логов падений")
                        return@withContext
                    }
                    try {
                        val uri = FileProvider.getUriForFile(
                            requireContext(),
                            "${requireContext().packageName}.cache",
                            file,
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, file.name)
                            putExtra(Intent.EXTRA_TITLE, file.name)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = ClipData.newUri(requireContext().contentResolver, file.name, uri)
                        }
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.title_pref_failure_log_view)))
                    } catch (e: Exception) {
                        requireContext().toastError(e.localizedMessage ?: e.toString())
                    }
                }
            }
        }
    }

    fun onModeHelpClicked(view: View) {
        Utils.openUri(this, AppConfig.APP_WIKI_MODE)
    }
}
