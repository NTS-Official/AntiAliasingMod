package com.nts.aamod.aamod.neoforge.client

import com.nts.aamod.client.AAModClient
import com.nts.aamod.client.gui.AntiAliasingConfigScreen
import net.neoforged.fml.ModContainer
import net.neoforged.neoforge.client.gui.IConfigScreenFactory

object AAModNeoForgeClient {
    fun initClient(container: ModContainer) {
        AAModClient.initClient()
        container.registerExtensionPoint(IConfigScreenFactory::class.java, IConfigScreenFactory { _, parent ->
            AntiAliasingConfigScreen(parent)
        })
    }
}
