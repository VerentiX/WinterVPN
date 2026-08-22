package com.v2ray.ang.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.v2ray.ang.R
import com.v2ray.ang.dto.LampaPackage
import com.v2ray.ang.handler.LampaSubscriptionMetadata

object SubscriptionPackageViews {

    fun bind(container: LinearLayout, header: TextView?, packages: List<LampaPackage>) {
        val visible = packages
            .filter { it.active || it.upcoming }
            .sortedWith(compareByDescending<LampaPackage> { it.active }.thenBy { it.startsAt })
        container.removeAllViews()
        if (visible.isEmpty()) {
            header?.visibility = View.GONE
            container.visibility = View.GONE
            return
        }
        header?.visibility = View.VISIBLE
        container.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(container.context)
        for (pkg in visible) {
            val row = inflater.inflate(R.layout.item_subscription_package, container, false)
            val line = row.findViewById<TextView>(R.id.package_line)
            val badge = row.findViewById<TextView>(R.id.package_now_badge)
            val dot = row.findViewById<View>(R.id.package_dot)
            line.text = LampaSubscriptionMetadata.formatPackageLine(container.context, pkg)
            if (pkg.active) {
                badge.visibility = View.VISIBLE
                dot.setBackgroundResource(R.drawable.bg_package_dot_active)
            } else {
                badge.visibility = View.GONE
                dot.setBackgroundResource(R.drawable.bg_package_dot_upcoming)
            }
            container.addView(row)
        }
    }
}
