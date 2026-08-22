package com.v2ray.ang.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.databinding.ItemSubscriptionProfileCompactBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.handler.MmkvManager

class SubscriptionProfileAdapter(
    private val onSelect: (String) -> Unit,
    private val onViewConfig: (String) -> Unit,
) : ListAdapter<SubscriptionProfileAdapter.Row, SubscriptionProfileAdapter.RowViewHolder>(Diff) {

    data class Row(
        val guid: String,
        val remarks: String,
        val protocol: String,
        val delayText: String,
        val selected: Boolean,
    )

    object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = oldItem.guid == newItem.guid
        override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean = oldItem == newItem
        override fun getChangePayload(oldItem: Row, newItem: Row): Any? {
            if (oldItem.remarks == newItem.remarks &&
                oldItem.protocol == newItem.protocol &&
                (oldItem.delayText != newItem.delayText || oldItem.selected != newItem.selected)
            ) {
                return PAYLOAD_STATUS
            }
            return null
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val binding = ItemSubscriptionProfileCompactBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RowViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_STATUS)) {
            holder.bindStatus(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class RowViewHolder(
        private val binding: ItemSubscriptionProfileCompactBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row) {
            binding.profileName.text = row.remarks
            binding.profileProtocol.text = row.protocol
            bindStatus(row)
            binding.profileRow.setOnClickListener { onSelect(row.guid) }
            binding.viewProfileConfig.visibility =
                if (com.v2ray.ang.AppFeatures.allowConfigView()) View.VISIBLE else View.GONE
            binding.viewProfileConfig.setOnClickListener {
                if (com.v2ray.ang.AppFeatures.allowConfigView()) {
                    onViewConfig(row.guid)
                }
            }
        }

        fun bindStatus(row: Row) {
            binding.profileDelay.text = row.delayText
            binding.selectedIndicator.visibility = if (row.selected) View.VISIBLE else View.INVISIBLE
        }
    }

    companion object {
        private const val PAYLOAD_STATUS = "status"

        fun protocolDescription(profile: ProfileItem): String {
            if (profile.configType.isComplexType()) return profile.configType.name
            return buildList {
                add(profile.configType.name)
                profile.network?.takeIf { it.isNotBlank() }?.let { add(it) }
                profile.security?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(" / ")
        }

        fun rowFor(guid: String, profile: ProfileItem, selectedGuid: String?): Row = Row(
            guid = guid,
            remarks = profile.remarks,
            protocol = protocolDescription(profile),
            delayText = MmkvManager.decodeServerAffiliationInfo(guid)?.getTestDelayString().orEmpty(),
            selected = guid == selectedGuid,
        )
    }
}
