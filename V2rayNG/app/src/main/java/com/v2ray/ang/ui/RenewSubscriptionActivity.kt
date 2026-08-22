package com.v2ray.ang.ui



import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.v2ray.ang.R

import com.v2ray.ang.databinding.ActivityRenewSubscriptionBinding

import com.v2ray.ang.dto.LampaPlan

import com.v2ray.ang.dto.LampaSubscriptionResponse

import com.v2ray.ang.dto.LampaSubscriptionSnapshot

import com.v2ray.ang.extension.toastError

import com.v2ray.ang.handler.AngConfigManager

import com.v2ray.ang.handler.LampaSubscriptionMetadata

import com.v2ray.ang.handler.LampaBillingClient

import com.v2ray.ang.handler.MmkvManager

import com.v2ray.ang.handler.SubscriptionUrlResolver

import com.v2ray.ang.util.LampaErrorMessages

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.Job

import kotlinx.coroutines.delay

import kotlinx.coroutines.isActive

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext



class RenewSubscriptionActivity : BaseActivity() {



    private val binding by lazy { ActivityRenewSubscriptionBinding.inflate(layoutInflater) }

    private val subId by lazy { intent.getStringExtra(EXTRA_SUB_ID).orEmpty() }

    private var pendingOrderId: String? = null

    private var plans: List<LampaPlan> = emptyList()
    private var paymentMethods: List<String> = listOf("sbp", "usdt")
    private var allowTestPayment = false

    private var paymentPollJob: Job? = null

    private var successShown = false



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContentViewWithToolbar(

            binding.root,

            showHomeAsUp = true,

            title = getString(R.string.subscription_renew),

        )

        if (subId.isEmpty()) {

            toastError(R.string.subscription_select_first)

            finish()

            return

        }



        pendingOrderId = intent.getStringExtra(EXTRA_ORDER_ID)

            ?: MmkvManager.decodeSettingsString(PREF_PENDING_ORDER)?.takeIf { it.isNotBlank() }



        binding.plansList.layoutManager = LinearLayoutManager(this)
        binding.plansList.isNestedScrollingEnabled = false
        binding.btnSuccessDone.setOnClickListener { finish() }



        if (intent.getBooleanExtra(EXTRA_PAYMENT_SUCCESS, false) && !paymentThanksAlreadyShown()) {
            showPaymentSuccess(confirming = true)
        }



        loadSubscription()

    }



    override fun onResume() {
        super.onResume()
        if (MmkvManager.decodeSettingsString(PREF_PENDING_ORDER).isNullOrBlank()) {
            pendingOrderId = null
        }
        startPaymentPolling()
    }



    override fun onPause() {

        paymentPollJob?.cancel()

        paymentPollJob = null

        super.onPause()

    }



    private fun loadSubscription() {

        binding.progress.visibility = View.VISIBLE

        binding.tvError.visibility = View.GONE

        lifecycleScope.launch {

            val response = withContext(Dispatchers.IO) {

                LampaBillingClient.fetchSubscription(subId)

            }

            binding.progress.visibility = View.GONE

            if (response?.ok != true) {

                showError(LampaErrorMessages.billingApi(this@RenewSubscriptionActivity, response?.error))

                return@launch

            }

            bindSubscription(response)

        }

    }



    private fun bindSubscription(response: LampaSubscriptionResponse) {

        val snapshot = LampaSubscriptionMetadata.snapshotFrom(response)
        persistSnapshot(snapshot)
        val tariff = snapshot.tariff
        val active = snapshot.activePackage()
        allowTestPayment = response.allowTestPayment
        paymentMethods = response.paymentMethods.orEmpty()
        binding.tvTariffTitle.text = LampaSubscriptionMetadata.formatTariffTitle(tariff, active)
            ?: getString(R.string.subscription_default_name)
        val currentDays = active?.let { LampaSubscriptionMetadata.currentDaysLeft(it) } ?: 0
        val nextUpcoming = snapshot.visiblePackages().firstOrNull { it.upcoming }
        binding.tvTariffMeta.text = when {
            currentDays > 0 -> getString(R.string.subscription_days_left, currentDays)
            nextUpcoming != null -> getString(
                R.string.subscription_next_starts,
                LampaSubscriptionMetadata.formatStartDay(nextUpcoming.startsAt),
            )
            else -> getString(R.string.subscription_expired)
        }
        SubscriptionPackageViews.bind(
            binding.ownedPackages,
            binding.packagesHeader,
            snapshot.visiblePackages(),
        )
        val totalDays = tariff?.daysLeft ?: 0
        val showTotal = totalDays > currentDays && snapshot.visiblePackages().any { it.upcoming }
        binding.tvSubscriptionUntil.visibility = if (showTotal) View.VISIBLE else View.GONE
        if (showTotal) {
            binding.tvSubscriptionUntil.text = getString(R.string.subscription_until_total, totalDays)
        }



        if (!response.paymentsEnabled) {
            showError(
                getString(
                    if (response.bindTelegramRequired) R.string.subscription_renew_bind_bot
                    else R.string.subscription_renew_unavailable,
                ),
            )
            return
        }



        plans = response.plans

        binding.plansList.adapter = PlanAdapter(plans) { plan -> choosePaymentMethod(plan) }

    }



    private fun choosePaymentMethod(plan: LampaPlan) {
        val sheet = BottomSheetDialog(this, R.style.PaymentBottomSheet)
        val view = layoutInflater.inflate(R.layout.sheet_payment_methods, null, false)
        sheet.setContentView(view)
        sheet.setOnShowListener {
            (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        view.findViewById<TextView>(R.id.sheet_title).text = plan.title
        view.findViewById<TextView>(R.id.sheet_meta).text = getString(
            R.string.subscription_plan_meta,
            plan.trafficGb,
            plan.days,
        ) + " · " + formatPlanPrice(plan)

        val list = view.findViewById<LinearLayout>(R.id.methods_list)
        val methods = enabledPaymentMethods()
        if ("sbp" in methods) {
            addPaymentMethodRow(
                list,
                R.drawable.ic_pay_sbp,
                R.drawable.bg_pay_icon_sbp,
                R.string.subscription_pay_sbp,
                R.string.subscription_pay_sbp_hint,
            ) {
                sheet.dismiss()
                startPayment(plan, "sbp", test = false)
            }
        }
        if ("usdt" in methods) {
            addPaymentMethodRow(
                list,
                R.drawable.ic_pay_crypto,
                R.drawable.bg_pay_icon_crypto,
                R.string.subscription_pay_usdt,
                R.string.subscription_pay_usdt_hint,
            ) {
                sheet.dismiss()
                startPayment(plan, "usdt", test = false)
            }
        }
        if (allowTestPayment && "sbp" in methods) {
            addPaymentMethodRow(
                list,
                R.drawable.ic_pay_test,
                R.drawable.bg_pay_icon_test,
                R.string.subscription_pay_sbp_test,
                R.string.subscription_pay_test_hint,
            ) {
                sheet.dismiss()
                startPayment(plan, "sbp", test = true)
            }
        }
        sheet.show()
    }

    private fun enabledPaymentMethods(): List<String> {
        val fromApi = paymentMethods.filter { it.equals("sbp", true) || it.equals("usdt", true) }
            .map { it.lowercase() }
        return fromApi.ifEmpty { listOf("sbp", "usdt") }
    }

    private fun addPaymentMethodRow(
        parent: LinearLayout,
        icon: Int,
        iconBg: Int,
        title: Int,
        subtitle: Int,
        onClick: () -> Unit,
    ) {
        val row = layoutInflater.inflate(R.layout.item_payment_method, parent, false)
        row.findViewById<View>(R.id.method_icon_wrap).setBackgroundResource(iconBg)
        row.findViewById<ImageView>(R.id.method_icon).setImageResource(icon)
        row.findViewById<TextView>(R.id.method_title).setText(title)
        row.findViewById<TextView>(R.id.method_subtitle).setText(subtitle)
        row.setOnClickListener { onClick() }
        parent.addView(row)
    }



    private fun startPayment(plan: LampaPlan, method: String, test: Boolean) {

        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {

            val result = withContext(Dispatchers.IO) {

                LampaBillingClient.createPayment(subId, plan.id, method, test)

            }

            binding.progress.visibility = View.GONE

            if (result?.ok != true || result.payUrl.isNullOrBlank()) {

                toastError(LampaErrorMessages.billingApi(this@RenewSubscriptionActivity, result?.error))

                return@launch

            }

            pendingOrderId = result.orderId
            clearPaymentThanksShown()
            MmkvManager.encodeSettings(PREF_PENDING_ORDER, result.orderId.orEmpty())

            MmkvManager.encodeSettings(PREF_PENDING_SUB, subId)

            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.payUrl)))

        }

    }



    private fun startPaymentPolling() {

        if (paymentPollJob?.isActive == true) return

        paymentPollJob = lifecycleScope.launch {

            while (isActive) {

                checkPendingPaymentOnce()

                delay(PAYMENT_POLL_MS)

            }

        }

    }



    private suspend fun checkPendingPaymentOnce() {

        val orderId = pendingOrderId ?: MmkvManager.decodeSettingsString(PREF_PENDING_ORDER)?.takeIf { it.isNotBlank() }

        val pendingSub = MmkvManager.decodeSettingsString(PREF_PENDING_SUB)

        if (orderId.isNullOrBlank() || pendingSub != subId) {

            if (successShown) {

                withContext(Dispatchers.Main) {

                    binding.successProgress.visibility = View.GONE

                    binding.tvSuccessMessage.text = getString(R.string.subscription_payment_success_message)

                }

            }

            return

        }



        val status = withContext(Dispatchers.IO) {

            LampaBillingClient.paymentStatus(orderId, subId)

        } ?: return



        when (status.status) {

            "paid" -> {
                MmkvManager.encodeSettings(PREF_PENDING_ORDER, "")
                MmkvManager.encodeSettings(PREF_PENDING_SUB, "")
                pendingOrderId = null
                val alreadyThanked = paymentThanksAlreadyShown()
                if (!alreadyThanked) {
                    withContext(Dispatchers.Main) {
                        showPaymentSuccess(confirming = false)
                    }
                }
                refreshLocalSubscription()
                loadSubscription()
            }

            "pending" -> {

                if (successShown) {

                    withContext(Dispatchers.Main) {

                        binding.successProgress.visibility = View.VISIBLE

                        binding.tvSuccessMessage.text = getString(R.string.subscription_payment_success_confirming)

                    }

                }

            }

            else -> {

                MmkvManager.encodeSettings(PREF_PENDING_ORDER, "")

            }

        }

    }



    private fun showPaymentSuccess(confirming: Boolean) {

        successShown = true

        binding.successOverlay.visibility = View.VISIBLE

        binding.btnSuccessDone.visibility = if (confirming) View.GONE else View.VISIBLE

        binding.successProgress.visibility = if (confirming) View.VISIBLE else View.GONE

        binding.tvSuccessMessage.text = if (confirming) {

            getString(R.string.subscription_payment_success_confirming)

        } else {

            getString(R.string.subscription_payment_success_message)

        }

    }



    private fun persistSnapshot(snapshot: LampaSubscriptionSnapshot) {
        val cache = MmkvManager.decodeSubscriptions().firstOrNull {
            SubscriptionUrlResolver.extractSubId(it.subscription.url) == subId
        } ?: return
        LampaSubscriptionMetadata.applySnapshot(cache.subscription, snapshot)
        MmkvManager.encodeSubscription(cache.guid, cache.subscription)
    }

    private fun refreshLocalSubscription() {

        val cache = MmkvManager.decodeSubscriptions().firstOrNull {

            SubscriptionUrlResolver.extractSubId(it.subscription.url) == subId

        } ?: return

        lifecycleScope.launch(Dispatchers.IO) {

            AngConfigManager.updateConfigViaSub(cache)

        }

    }



    private fun showError(message: String) {

        binding.tvError.visibility = View.VISIBLE

        binding.tvError.text = message

    }



    private inner class PlanAdapter(

        private val items: List<LampaPlan>,

        private val onClick: (LampaPlan) -> Unit,

    ) : RecyclerView.Adapter<PlanAdapter.Holder>() {



        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.plan_card)
            val title: TextView = view.findViewById(R.id.plan_title)
            val meta: TextView = view.findViewById(R.id.plan_meta)
            val price: TextView = view.findViewById(R.id.plan_price)
            val gb: TextView = view.findViewById(R.id.plan_gb)
            val recommended: TextView = view.findViewById(R.id.plan_recommended)
        }



        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {

            val view = LayoutInflater.from(parent.context)

                .inflate(R.layout.item_lampa_plan, parent, false)

            return Holder(view)

        }



        override fun getItemCount(): Int = items.size



        override fun onBindViewHolder(holder: Holder, position: Int) {
            val plan = items[position]
            val recommendedGb = items.maxOfOrNull { it.trafficGb } ?: 0
            val isRecommended = plan.trafficGb == recommendedGb && recommendedGb > 0 && items.size > 1
            holder.title.text = plan.title
            holder.gb.text = plan.trafficGb.toString()
            holder.meta.text = getString(R.string.subscription_plan_meta, plan.trafficGb, plan.days)
            holder.price.text = formatPlanPrice(plan)
            holder.recommended.visibility = if (isRecommended) View.VISIBLE else View.GONE
            holder.card.strokeColor = if (isRecommended) {
                0xFFFFB74D.toInt()
            } else {
                0x33FFFFFF
            }
            holder.card.strokeWidth = if (isRecommended) 2 else 1
            holder.itemView.setOnClickListener { onClick(plan) }
        }

    }

    private fun formatPlanPrice(plan: LampaPlan): String {
        val catalog = plan.catalogPriceRub.takeIf { it > 0 } ?: plan.priceRub
        return if (plan.discountRub > 0 && plan.priceRub < catalog) {
            getString(R.string.subscription_plan_referral_price, plan.priceRub, catalog)
        } else {
            getString(R.string.subscription_plan_price, plan.priceRub)
        }
    }



    companion object {

        const val EXTRA_SUB_ID = "sub_id"
        const val EXTRA_PAYMENT_SUCCESS = "payment_success"
        const val EXTRA_ORDER_ID = "order_id"
        const val PREF_PENDING_ORDER = "pref_lampa_pending_order"
        const val PREF_PENDING_SUB = "pref_lampa_pending_sub"
        const val PREF_PAYMENT_THANKS_SHOWN = "pref_lampa_payment_thanks_shown"
        private const val PAYMENT_POLL_MS = 3000L

        fun clearPendingPayment() {
            MmkvManager.encodeSettings(PREF_PENDING_ORDER, "")
            MmkvManager.encodeSettings(PREF_PENDING_SUB, "")
        }

        fun markPaymentThanksShown() {
            MmkvManager.encodeSettings(PREF_PAYMENT_THANKS_SHOWN, "1")
            clearPendingPayment()
        }

        fun paymentThanksAlreadyShown(): Boolean {
            return MmkvManager.decodeSettingsString(PREF_PAYMENT_THANKS_SHOWN) == "1"
        }

        fun clearPaymentThanksShown() {
            MmkvManager.encodeSettings(PREF_PAYMENT_THANKS_SHOWN, "")
        }

        fun launch(context: android.content.Context, subId: String) {

            context.startActivity(

                Intent(context, RenewSubscriptionActivity::class.java)

                    .putExtra(EXTRA_SUB_ID, subId),

            )

        }

    }

}

