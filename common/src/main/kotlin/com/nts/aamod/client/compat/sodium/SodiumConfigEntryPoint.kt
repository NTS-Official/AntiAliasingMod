package com.nts.aamod.client.compat.sodium

import com.nts.aamod.AAMod
import com.nts.aamod.client.gui.AntiAliasingConfigScreen
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

class SodiumConfigEntryPoint : ConfigEntryPoint {
    override fun registerConfigLate(builder: ConfigBuilder) {
        builder.registerOwnModOptions()
            .setName(AAMod.MOD_NAME)
            .setNonTintedIcon(AAMod.id("textures/gui/config-icon.png"))
            .addPage(
                builder.createExternalPage()
                    .setName(Component.translatable("options.antialiasing"))
                    .setScreenConsumer { parent ->
                        Minecraft.getInstance().gui.setScreen(AntiAliasingConfigScreen(parent))
                    }
            )
    }
}
