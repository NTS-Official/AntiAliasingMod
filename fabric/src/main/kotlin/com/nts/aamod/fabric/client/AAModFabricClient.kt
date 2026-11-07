package com.nts.aamod.fabric.client

import com.nts.aamod.client.AAModClient.initClient
import net.fabricmc.api.ClientModInitializer

/** Fabric 客户端入口。  */
class AAModFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        initClient()
    }
}
