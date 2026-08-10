package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemSubscriptionCardMainBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SubscriptionCardAdapter(
    private val listener: Listener
) : RecyclerView.Adapter<SubscriptionCardAdapter.CardViewHolder>() {

    interface Listener {
        fun onSelectProfile(guid: String)
        fun onViewProfileConfig(guid: String)
        fun onUpdateSubscription(subscription: SubscriptionCache)
        fun onMeasureSubscription(subscriptionId: String)
        fun onEditSubscription(subscriptionId: String)
    }

    private data class ProfileEntry(val guid: String, val profile: ProfileItem)
    private data class CardEntry(val subscription: SubscriptionCache, val profiles: List<ProfileEntry>)

    private var cards = emptyList<CardEntry>()
    private var query = ""
    /** Opt-in expand; empty means all cards are collapsed. */
    private val expanded = mutableSetOf<String>()
    private val visibleLimits = mutableMapOf<String, Int>()

    @SuppressLint("NotifyDataSetChanged")
    fun reload(filter: String = query) {
        query = filter.trim()
        val needle = query.lowercase()
        val subscriptions = MmkvManager.decodeSubscriptions()
        val hasRemoteSubscription = subscriptions.any { it.subscription.url.isNotBlank() }
        cards = subscriptions
            .filterNot { subscription ->
                hasRemoteSubscription && subscription.guid == AppConfig.DEFAULT_SUBSCRIPTION_ID &&
                    subscription.subscription.url.isBlank() &&
                    MmkvManager.decodeServerList(subscription.guid).isEmpty()
            }
            .mapNotNull { subscription ->
                val allProfiles = MmkvManager.decodeServerList(subscription.guid).mapNotNull { guid ->
                    MmkvManager.decodeServerConfig(guid)?.let { ProfileEntry(guid, it) }
                }
                val subscriptionMatches = needle.isEmpty() ||
                    subscription.subscription.remarks.lowercase().contains(needle)
                val visibleProfiles = if (subscriptionMatches) allProfiles else allProfiles.filter { entry ->
                    entry.profile.remarks.lowercase().contains(needle) ||
                        entry.profile.server.orEmpty().lowercase().contains(needle) ||
                        SubscriptionProfileAdapter.protocolDescription(entry.profile)
                            .lowercase().contains(needle)
                }
                if (subscriptionMatches || visibleProfiles.isNotEmpty()) {
                    CardEntry(subscription, visibleProfiles)
                } else null
            }
        notifyDataSetChanged()
    }

    fun indexOfSubscription(subscriptionId: String): Int =
        cards.indexOfFirst { it.subscription.guid == subscriptionId }

    fun revealSubscription(subscriptionId: String): Int {
        val index = indexOfSubscription(subscriptionId)
        if (index >= 0 && expanded.add(subscriptionId)) notifyItemChanged(index)
        return index
    }

    /** Update delay/selection texts without rebuilding the whole card list. */
    fun notifyProfileStatusChanged(guid: String? = null) {
        val selected = MmkvManager.getSelectServer()
        cards.forEachIndexed { index, card ->
            if (guid == null || card.profiles.any { it.guid == guid } ||
                card.profiles.any { it.guid == selected }
            ) {
                notifyItemChanged(index, PAYLOAD_STATUS)
            }
        }
    }

    override fun getItemCount(): Int = cards.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val binding = ItemSubscriptionCardMainBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CardViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_STATUS)) {
            bindStatus(holder, cards[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val card = cards[position]
        val item = card.subscription.subscription
        val context = holder.binding.root.context
        val isExpanded = expanded.contains(card.subscription.guid)
        val limit = visibleLimits[card.subscription.guid] ?: INITIAL_VISIBLE_PROFILES

        holder.binding.subscriptionName.text = item.profileTitle?.takeIf { it.isNotBlank() } ?: item.remarks
        val updateMeta = if (item.lastUpdated > 0) {
            context.getString(
                R.string.subscription_updated_at,
                Utils.formatTimestamp(item.lastUpdated),
                card.profiles.size
            )
        } else {
            context.getString(R.string.subscription_never_updated, card.profiles.size)
        }
        val autoUpdateMeta = if (item.autoUpdate) {
            val interval = if (item.updateInterval % 60L == 0L) {
                context.getString(R.string.subscription_interval_hours, item.updateInterval / 60L)
            } else {
                context.getString(R.string.subscription_interval_minutes, item.updateInterval)
            }
            context.getString(R.string.subscription_auto_update_interval, interval)
        } else {
            context.getString(R.string.subscription_auto_update_off)
        }
        holder.binding.subscriptionMeta.text = "$updateMeta · $autoUpdateMeta"
        val hasTraffic = item.uploadBytes >= 0 || item.downloadBytes >= 0 || item.totalBytes >= 0
        val used = item.uploadBytes.coerceAtLeast(0) + item.downloadBytes.coerceAtLeast(0)
        holder.binding.trafficContainer.visibility = if (hasTraffic) View.VISIBLE else View.GONE
        holder.binding.subscriptionTrafficText.text = if (item.totalBytes > 0) {
            context.getString(R.string.subscription_traffic_compact, used.toTrafficString(), item.totalBytes.toTrafficString())
        } else {
            context.getString(R.string.subscription_traffic_unlimited, used.toTrafficString())
        }
        holder.binding.subscriptionTrafficProgress.progress = if (item.totalBytes > 0) {
            ((used.toDouble() / item.totalBytes.toDouble()) * 10_000.0).toInt().coerceIn(0, 10_000)
        } else 0

        val hasExpiry = item.expireAt > 0
        holder.binding.subscriptionExpiry.visibility = if (hasExpiry) View.VISIBLE else View.GONE
        if (hasExpiry) {
            val secondsLeft = item.expireAt - System.currentTimeMillis() / 1000L
            holder.binding.subscriptionExpiry.text = if (secondsLeft <= 0) {
                context.getString(R.string.subscription_expired)
            } else {
                val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(item.expireAt * 1000L))
                val days = TimeUnit.SECONDS.toDays(secondsLeft)
                context.getString(R.string.subscription_expires_at, date, days)
            }
        }
        holder.binding.subscriptionDetails.visibility = if (hasTraffic || hasExpiry) View.VISIBLE else View.GONE
        holder.binding.expandIcon.rotation = if (isExpanded) 180f else 0f
        holder.binding.profileContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.binding.updateSubscription.visibility = if (item.url.isBlank()) View.INVISIBLE else View.VISIBLE
        holder.binding.measureSubscription.visibility =
            if (card.profiles.isEmpty()) View.GONE else View.VISIBLE

        val toggle = View.OnClickListener { toggleExpanded(holder, card.subscription.guid) }
        holder.binding.expandIcon.setOnClickListener(toggle)
        holder.binding.subscriptionName.setOnClickListener(toggle)
        holder.binding.subscriptionMeta.setOnClickListener(toggle)
        holder.binding.subscriptionDetails.setOnClickListener(toggle)
        holder.binding.updateSubscription.setOnClickListener {
            listener.onUpdateSubscription(card.subscription)
        }
        holder.binding.measureSubscription.setOnClickListener {
            listener.onMeasureSubscription(card.subscription.guid)
        }
        holder.binding.editSubscription.setOnClickListener {
            listener.onEditSubscription(card.subscription.guid)
        }

        if (isExpanded) {
            val selected = MmkvManager.getSelectServer()
            val visible = card.profiles.take(limit)
            holder.profilesAdapter.submitList(
                visible.map { SubscriptionProfileAdapter.rowFor(it.guid, it.profile, selected) }
            )
            val remaining = card.profiles.size - visible.size
            if (remaining > 0) {
                holder.binding.showMoreProfiles.visibility = View.VISIBLE
                holder.binding.showMoreProfiles.text =
                    context.getString(R.string.subscription_show_more, remaining)
                holder.binding.showMoreProfiles.setOnClickListener {
                    visibleLimits[card.subscription.guid] = limit + INITIAL_VISIBLE_PROFILES
                    val adapterPosition = holder.bindingAdapterPosition
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        notifyItemChanged(adapterPosition)
                    }
                }
            } else {
                holder.binding.showMoreProfiles.visibility = View.GONE
                holder.binding.showMoreProfiles.setOnClickListener(null)
            }
        } else {
            holder.profilesAdapter.submitList(emptyList())
            holder.binding.showMoreProfiles.visibility = View.GONE
        }
    }

    private fun toggleExpanded(holder: CardViewHolder, subscriptionId: String) {
        if (!expanded.add(subscriptionId)) expanded.remove(subscriptionId)
        val adapterPosition = holder.bindingAdapterPosition
        if (adapterPosition != RecyclerView.NO_POSITION) notifyItemChanged(adapterPosition)
    }

    private fun bindStatus(holder: CardViewHolder, card: CardEntry) {
        if (!expanded.contains(card.subscription.guid)) return
        val selected = MmkvManager.getSelectServer()
        val limit = visibleLimits[card.subscription.guid] ?: INITIAL_VISIBLE_PROFILES
        val visible = card.profiles.take(limit)
        holder.profilesAdapter.submitList(
            visible.map { SubscriptionProfileAdapter.rowFor(it.guid, it.profile, selected) }
        )
    }

    inner class CardViewHolder(
        val binding: ItemSubscriptionCardMainBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        val profilesAdapter = SubscriptionProfileAdapter(
            onSelect = listener::onSelectProfile,
            onViewConfig = listener::onViewProfileConfig,
        )

        init {
            binding.profileList.layoutManager = LinearLayoutManager(binding.root.context)
            binding.profileList.adapter = profilesAdapter
            binding.profileList.itemAnimator = null
            binding.profileList.isNestedScrollingEnabled = false
            binding.profileList.setHasFixedSize(false)
        }
    }

    companion object {
        private const val INITIAL_VISIBLE_PROFILES = 40
        private const val PAYLOAD_STATUS = "status"
    }
}
