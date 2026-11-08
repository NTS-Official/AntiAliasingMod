package com.nts.aamod.aamod.neoforge

import com.nts.aamod.AAMod
import com.nts.aamod.AAMod.init
import com.nts.aamod.aamod.neoforge.client.AAModNeoForgeClient
import net.neoforged.fml.common.Mod
import net.neoforged.fml.ModContainer
import net.neoforged.fml.loading.FMLEnvironment

@Mod(AAMod.MOD_ID)
class AAModNeoForge(container: ModContainer) {
    init {
        init()
        if (FMLEnvironment.getDist().isClient) {
            AAModNeoForgeClient.initClient(container)
        }
    }
}
