package com.nts.aamod.aamod.platform.neoforge

import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.loading.FMLEnvironment

object PlatformHelperImpl {
    val platformName: String
        get() = "NeoForge"

    val isClient: Boolean
        get() = FMLEnvironment.getDist() === Dist.CLIENT
}
