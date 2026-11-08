package com.nts.aamod.client.gui

import com.nts.aamod.client.option.FxaaSettings
import com.nts.aamod.client.option.SpatialSettings
import com.nts.aamod.client.option.IrisSettings
import com.nts.aamod.client.compat.iris.IrisCompatibility
import com.nts.aamod.client.option.VideoAntiAliasingSettings
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class AntiAliasingConfigScreen(parent: Screen) : OptionsSubScreen(
    parent,
    Minecraft.getInstance().options,
    Component.translatable("aamod.config.title")
) {
    private var irisStatus: StringWidget? = null

    override fun addOptions() {
        list?.let { optionList ->
            optionList.addBig(VideoAntiAliasingSettings.getOption())
            optionList.addHeader(Component.translatable("aamod.config.iris"))
            optionList.addBig(IrisSettings.policy)
            optionList.addHeader(IrisCompatibility.describe())
            val entries: List<*> = optionList.children()
            irisStatus = (entries.last() as ContainerEventHandler).children().filterIsInstance<StringWidget>().single()
            optionList.addHeader(Component.translatable("aamod.config.fxaa"))
            FxaaSettings.options.forEach(optionList::addBig)
            optionList.addHeader(Component.translatable("aamod.config.ssaa"))
            optionList.addBig(SpatialSettings.ssaaScale)
            optionList.addHeader(Component.translatable("aamod.config.smaa"))
            optionList.addBig(SpatialSettings.smaaQuality)
        }
    }

    override fun tick() {
        super.tick()
        irisStatus?.let { widget ->
            widget.message = IrisCompatibility.describe()
            widget.width = font.width(widget.message)
        }
    }

    override fun addFooter() {
        val footer = layout.addToFooter(LinearLayout.horizontal().spacing(8))
        footer.addChild(Button.builder(Component.translatable("aamod.config.fxaa.reset")) {
            FxaaSettings.resetDefaults()
            FxaaSettings.options.forEach(::resetOption)
        }.width(150).build())
        footer.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(150).build())
    }
}
