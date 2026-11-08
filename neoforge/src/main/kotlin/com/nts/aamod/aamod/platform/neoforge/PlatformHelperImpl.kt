package com.nts.aamod.aamod.platform.neoforge

import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.ModList

object PlatformHelperImpl {
    fun getModVersion(modId: String): String? =
        ModList.get().getModContainerById(modId).orElse(null)?.modInfo?.version?.toString()

    val platformName: String
        get() = "NeoForge"

    val isClient: Boolean
        get() = FMLEnvironment.getDist() === Dist.CLIENT
}
