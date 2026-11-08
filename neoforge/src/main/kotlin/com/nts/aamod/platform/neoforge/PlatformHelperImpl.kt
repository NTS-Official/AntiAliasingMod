package com.nts.aamod.platform.neoforge

import com.nts.aamod.aamod.platform.neoforge.PlatformHelperImpl as NeoForgePlatform

object PlatformHelperImpl {
    @get:JvmStatic
    val platformName: String
        get() = NeoForgePlatform.platformName

    @get:JvmStatic
    val isClient: Boolean
        get() = NeoForgePlatform.isClient
}
