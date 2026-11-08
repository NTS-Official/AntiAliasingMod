package com.nts.aamod.option

import com.mojang.serialization.Codec
import com.nts.aamod.client.antialiasing.AntiAliasingController
import com.nts.aamod.client.mixin.option.OptionsAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

object VideoAntiAliasingSettings {
    const val OFF = 0
    const val FXAA = 1
    const val SMAA = 2
    const val TAA = 3

    const val OPTIONS_KEY = "antialiasing"

    @JvmField val MODES = listOf(OFF, FXAA, SMAA, TAA)
    @JvmField val MODES_STR = listOf("OFF", "FXAA", "SMAA", "TAA")

    private val OPTION: OptionInstance<Int> = OptionInstance(
        "options.antialiasing",
        { _ ->
            Tooltip.create(
                Component.translatable("options.antialiasing.tooltip")
                    .append("\n\n")
                    .append(Component.translatable("options.antialiasing.status." + AntiAliasingController.getStatus().state.name.lowercase()))
            )
        },
        { _, value -> Component.translatable(getModeKey(value)) },
        OptionInstance.Enum<Int>(MODES, Codec.INT),
        OFF,
        { _ -> changePresetToCustom() }
    )

    @JvmStatic fun getOption() = OPTION

    @JvmStatic fun getCurrentAAMode() = OPTION.get()

    private fun getModeKey(mode: Int) = "options.antialiasing.mode.$mode"

    private var suppressPresetCustom = false

    @JvmStatic
    fun beginLoading() {
        suppressPresetCustom = true
        OPTION.set(OFF)
    }

    @JvmStatic
    fun endLoading() {
        suppressPresetCustom = false
    }

    @JvmStatic
    fun setLoadedMode(mode: Int) {
        setModeQuietly(if (mode in MODES) mode else OFF)
    }

    private fun setModeQuietly(mode: Int) {
        val previousSuppression = suppressPresetCustom
        suppressPresetCustom = true
        try {
            OPTION.set(mode)
        } finally {
            suppressPresetCustom = previousSuppression
        }
    }

    @JvmStatic
    fun applyFromPreset(serializedName: String) {
        val mode = when (serializedName) {
            "fast" -> OFF
            "fancy" -> SMAA
            "fabulous" -> TAA
            else -> return
        }
        setModeQuietly(mode)
    }

    private fun changePresetToCustom() {
        if (suppressPresetCustom) return
        val options = Minecraft.getInstance().options
        (options as OptionsAccessor).`aamod$setGraphicsPresetToCustom`()
    }
}
