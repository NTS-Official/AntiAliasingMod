package com.nts.aamod.fabric

import com.nts.aamod.AAMod.init
import net.fabricmc.api.ModInitializer

/** Fabric 通用入口，唯一职责是把初始化转交给 common。  */
class AAModFabric : ModInitializer {
    override fun onInitialize() {
        init()
    }
}
