package com.nts.aamod.platform.fabric

import net.fabricmc.api.EnvType
import net.fabricmc.loader.api.FabricLoader

/** [PlatformHelper] 在 Fabric 上的实现，由 Architectury 在构建期注入调用。  */
object PlatformHelperImpl {
    val platformName: String
        get() = "Fabric"

    val isClient: Boolean
        get() = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
}
