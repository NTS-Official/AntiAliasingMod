package com.nts.aamod.aamod.neoforge

import com.nts.aamod.AAMod
import com.nts.aamod.AAMod.init
import com.nts.aamod.aamod.neoforge.client.AAModNeoForgeClient
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment

@Mod(AAMod.MOD_ID)
class AAModNeoForge {
    init {
        init()
        if (FMLEnvironment.getDist().isClient) {
            AAModNeoForgeClient.initClient()
        }
    }
}
