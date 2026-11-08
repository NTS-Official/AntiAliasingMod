package com.nts.aamod.platform.fabric

import net.fabricmc.api.EnvType
import net.fabricmc.loader.api.FabricLoader

/** [PlatformHelper] 在 Fabric 上的实现，由 Architectury 在构建期注入调用。  */
object PlatformHelperImpl {
    @JvmStatic
    fun getModVersion(modId: String): String? =
        FabricLoader.getInstance().getModContainer(modId).orElse(null)?.metadata?.version?.friendlyString

    @get:JvmStatic
    val platformName: String
        get() = "Fabric"

    @get:JvmStatic
    val isClient: Boolean
        get() = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
}
