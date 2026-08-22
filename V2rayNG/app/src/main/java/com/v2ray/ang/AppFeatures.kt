package com.v2ray.ang

import com.v2ray.ang.BuildConfig

/** Release-facing product toggles (Lampa consumer UI in all builds). */
object AppFeatures {
    val isConsumerBuild: Boolean
        get() = BuildConfig.CONSUMER_MODE

    fun allowConfigView(): Boolean = !isConsumerBuild

    fun showRoutingSettings(): Boolean = !isConsumerBuild

    fun showAdvancedToolbar(): Boolean = !isConsumerBuild

    fun showConnectionTestCard(): Boolean = !isConsumerBuild

    fun showNavigationDrawer(): Boolean = !isConsumerBuild

    fun showAnimationToggle(): Boolean = !isConsumerBuild

    fun showSubscriptionProfiles(): Boolean = !isConsumerBuild
}
