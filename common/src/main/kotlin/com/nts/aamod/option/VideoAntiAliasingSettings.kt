package com.nts.aamod.option

import com.mojang.serialization.Codec
import com.nts.aamod.AAMod
import com.nts.aamod.AAMod.MOD_ID
import com.nts.aamod.AAMod.MOD_NAME
import com.nts.aamod.client.mixin.option.OptionsAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import java.io.File
import java.nio.charset.StandardCharsets

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
        { _ -> Tooltip.create(Component.translatable("options.antialiasing.tooltip")) },
        { c, value -> Component.translatable(getModeKey(value)) },
        OptionInstance.Enum<Int>(MODES, Codec.INT),
        OFF,
        { _ -> changePresetToCustom()}
    )

    @JvmStatic
    fun getOption(): OptionInstance<Int> = OPTION

    @JvmStatic
    fun getCurrentAAMode(): Int = OPTION.get()

    private fun getModeKey(mode: Int) = "options.antialiasing.mode.$mode"

    // 阻止特殊情况的预设跳变
    private var suppressPresetCustom = false

    // 写取值但不动画质预设
    private fun setModeQuietly(mode: Int) {
        suppressPresetCustom = true
        try {
            OPTION.set(mode)
        } finally {
            suppressPresetCustom = false
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
        suppressPresetCustom = true
        try {
            OPTION.set(mode)
        } finally {
            suppressPresetCustom = false
        }
    }

    private fun changePresetToCustom() {
        if (suppressPresetCustom) return
        runCatching {
            val options = Minecraft.getInstance().options
            (options as OptionsAccessor).`aamod$setGraphicsPresetToCustom`()
        }
    }

    private fun getModeName(mode: Int): String {
        return if (mode in MODES) MODES_STR[mode]
        else "Unknown Mode: $mode"
    }


    @JvmStatic
    fun loadFromFile(file: File) {
        if (!file.isFile) {
            return
        }
    try {
            for (line in file.readLines(StandardCharsets.UTF_8)) {
                if (!line.startsWith("$OPTIONS_KEY:")) continue
                break
            }
        } catch (t: Throwable) {
            AAMod.LOGGER.warn("[$MOD_ID] $MOD_NAME could not read $OPTIONS_KEY from $file !", t)
            return
        }
    }

    @JvmStatic
    fun saveToFile(file: File) {
        if (!file.isFile) return
        try {
            val eol = System.lineSeparator()
            val kept = file.readLines(StandardCharsets.UTF_8).filterNot{ it.startsWith("$OPTIONS_KEY:") }
            val text = (kept + ("${OPTIONS_KEY}:${OPTION.get()}")).joinToString(eol) + eol
            file.writeText(text, StandardCharsets.UTF_8)
        } catch (t: Throwable) { AAMod.LOGGER.warn("[$MOD_ID] $MOD_NAME could not write $OPTIONS_KEY to $file !", t) }
    }
}