package com.nts.aamod.platform.neoforge

import com.nts.aamod.aamod.platform.neoforge.PlatformHelperImpl as NeoForgePlatform

object PlatformHelperImpl {
    @JvmStatic
    fun getModVersion(modId: String): String? = NeoForgePlatform.getModVersion(modId)

    @get:JvmStatic
    val platformName: String
        get() = NeoForgePlatform.platformName

    @get:JvmStatic
    val isClient: Boolean
        get() = NeoForgePlatform.isClient
}
