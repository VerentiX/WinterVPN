package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemSubscriptionCardMainBinding
import com.v2ray.ang.databinding.ItemSubscriptionProfileCompactBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils

class SubscriptionCardAdapter(
    private val listener: Listener
) : RecyclerView.Adapter<SubscriptionCardAdapter.CardViewHolder>() {

    interface Listener {
        fun onSelectProfile(guid: String)
        fun onViewProfileConfig(guid: String)
        fun onUpdateSubscription(subscription: SubscriptionCache)
        fun onEditSubscription(subscriptionId: String)
    }

    private data class ProfileEntry(val guid: String, val profile: ProfileItem)
    private data class CardEntry(val subscription: SubscriptionCache, val profiles: List<ProfileEntry>)

    private var cards = emptyList<CardEntry>()
    private var query = ""
    private val collapsed = mutableSetOf<String>()

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
                        protocolDescription(entry.profile).lowercase().contains(needle)
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
        if (index >= 0 && collapsed.remove(subscriptionId)) notifyItemChanged(index)
        return index
    }

    override fun getItemCount(): Int = cards.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder = CardViewHolder(
        ItemSubscriptionCardMainBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val card = cards[position]
        val item = card.subscription.subscription
        val context = holder.binding.root.context
        val isExpanded = !collapsed.contains(card.subscription.guid)

        holder.binding.subscriptionName.text = item.remarks
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
        holder.binding.expandIcon.rotation = if (isExpanded) 180f else 0f
        holder.binding.profileContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.binding.updateSubscription.visibility = if (item.url.isBlank()) View.INVISIBLE else View.VISIBLE

        holder.binding.subscriptionHeader.setOnClickListener {
            if (!collapsed.add(card.subscription.guid)) collapsed.remove(card.subscription.guid)
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION) notifyItemChanged(adapterPosition)
        }
        holder.binding.updateSubscription.setOnClickListener { listener.onUpdateSubscription(card.subscription) }
        holder.binding.editSubscription.setOnClickListener { listener.onEditSubscription(card.subscription.guid) }

        holder.binding.profileContainer.removeAllViews()
        if (isExpanded) {
            val selected = MmkvManager.getSelectServer()
            card.profiles.forEach { entry ->
                val row = ItemSubscriptionProfileCompactBinding.inflate(
                    LayoutInflater.from(context), holder.binding.profileContainer, false
                )
                row.profileName.text = entry.profile.remarks
                row.profileProtocol.text = protocolDescription(entry.profile)
                row.profileDelay.text = MmkvManager.decodeServerAffiliationInfo(entry.guid)
                    ?.getTestDelayString().orEmpty()
                row.selectedIndicator.visibility = if (entry.guid == selected) View.VISIBLE else View.INVISIBLE
                row.profileRow.setOnClickListener { listener.onSelectProfile(entry.guid) }
                row.viewProfileConfig.setOnClickListener { listener.onViewProfileConfig(entry.guid) }
                holder.binding.profileContainer.addView(row.root)
            }
        }
    }

    private fun protocolDescription(profile: ProfileItem): String {
        if (profile.configType.isComplexType()) return profile.configType.name
        return buildList {
            add(profile.configType.name)
            profile.network?.takeIf { it.isNotBlank() }?.let { add(it) }
            profile.security?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" / ")
    }

    class CardViewHolder(val binding: ItemSubscriptionCardMainBinding) :
        RecyclerView.ViewHolder(binding.root)
}
