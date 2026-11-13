package com.nts.aamod.client.gui

import com.nts.aamod.client.option.FxaaSettings
import com.nts.aamod.client.option.SpatialSettings
import com.nts.aamod.client.option.IrisSettings
import com.nts.aamod.client.compat.iris.IrisCompatibility
import com.nts.aamod.client.option.VideoAntiAliasingSettings
import com.nts.aamod.client.option.TemporalSettings
import com.nts.aamod.platform.PlatformHelper
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.network.chat.Component

class AntiAliasingConfigScreen(parent: Screen) : OptionsSubScreen(
    parent,
    Minecraft.getInstance().options,
    Component.translatable("aamod.config.title")
) {
    private var irisStatus: StringWidget? = null

    override fun addContents() {
        list = layout.addToContents(ResettableOptionsList(Minecraft.getInstance(), width, this))
        addOptions()
    }

    override fun addOptions() {
        irisStatus = null
        (list as? ResettableOptionsList)?.let { optionList ->
            optionList.addBig(VideoAntiAliasingSettings.getOption())
            if (PlatformHelper.getModVersion("iris") != null) {
                optionList.addHeader(Component.translatable("aamod.config.iris"))
                optionList.addOption(IrisSettings.policy, IrisSettings.AUTOMATIC)
                optionList.addHeader(IrisCompatibility.describe())
                val entries = optionList.children()
                irisStatus = (entries.last() as ContainerEventHandler).children().filterIsInstance<StringWidget>().single()
            }
            optionList.addHeader(Component.translatable("aamod.config.fxaa"))
            optionList.addOption(FxaaSettings.subpixel, FxaaSettings.DEFAULT_SUBPIXEL)
            optionList.addOption(FxaaSettings.edgeThreshold, FxaaSettings.DEFAULT_EDGE_THRESHOLD)
            optionList.addOption(FxaaSettings.minimumThreshold, FxaaSettings.DEFAULT_MINIMUM_THRESHOLD)
            optionList.addOption(FxaaSettings.searchSteps, FxaaSettings.DEFAULT_SEARCH_STEPS)
            optionList.addHeader(Component.translatable("aamod.config.ssaa"))
            optionList.addOption(SpatialSettings.ssaaScale, SpatialSettings.DEFAULT_SSAA_SCALE)
            optionList.addHeader(Component.translatable("aamod.config.smaa"))
            optionList.addOption(SpatialSettings.smaaQuality, SpatialSettings.DEFAULT_SMAA_QUALITY)
            optionList.addHeader(Component.translatable("aamod.config.taa"))
            optionList.addOption(TemporalSettings.sequence, TemporalSettings.DEFAULT_SEQUENCE)
            optionList.addOption(TemporalSettings.samples, TemporalSettings.DEFAULT_SAMPLES)
            optionList.addOption(TemporalSettings.sharpness, TemporalSettings.DEFAULT_SHARPNESS)
            optionList.addOption(TemporalSettings.effects, TemporalSettings.DEFAULT_EFFECTS)
            optionList.addOption(TemporalSettings.effectBudget, TemporalSettings.DEFAULT_EFFECT_BUDGET_MIB)
        }
    }

    override fun tick() {
        super.tick()
        (list as? ResettableOptionsList)?.updateResetButtons()
        irisStatus?.let { widget ->
            widget.message = IrisCompatibility.describe()
            widget.width = font.width(widget.message)
        }
    }
}
