package com.nts.aamod.fabric.client

import com.nts.aamod.client.gui.AntiAliasingConfigScreen
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

class AAModModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<AntiAliasingConfigScreen> =
        ConfigScreenFactory(::AntiAliasingConfigScreen)
}
