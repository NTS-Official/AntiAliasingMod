package com.nts.aamod.client.option

import com.mojang.serialization.Codec
import com.nts.aamod.client.mixin.option.OptionFieldAccess
import net.minecraft.client.OptionInstance
import net.minecraft.network.chat.Component

object SpatialSettings {
    const val DEFAULT_SSAA_SCALE = 2
    const val DEFAULT_SMAA_QUALITY = 2
    val scales = listOf(1.5, 2.0, 3.0, 4.0)
    val ssaaScale = OptionInstance(
        "aamod.config.ssaa.scale",
        OptionInstance.cachedConstantTooltip(Component.translatable("aamod.config.ssaa.scale.tooltip")),
        { _, value -> Component.literal("${scales[value - 1]}×") },
        OptionInstance.Enum((1..scales.size).toList(), Codec.INT),
        DEFAULT_SSAA_SCALE,
        { _ -> }
    )
    val smaaQuality = OptionInstance(
        "aamod.config.smaa.quality",
        OptionInstance.cachedConstantTooltip(Component.translatable("aamod.config.smaa.quality.tooltip")),
        { _, value -> Component.translatable("aamod.config.smaa.quality.$value") },
        OptionInstance.Enum((0..3).toList(), Codec.INT),
        DEFAULT_SMAA_QUALITY,
        { _ -> }
    )

    fun scale(): Double = scales[ssaaScale.get() - 1]

    fun resetDefaults() {
        ssaaScale.set(DEFAULT_SSAA_SCALE)
        smaaQuality.set(DEFAULT_SMAA_QUALITY)
    }

    fun processOptions(access: OptionFieldAccess) {
        val scale = access.`aamod$processInteger`("aamod_ssaa_scale", ssaaScale.get())
        val quality = access.`aamod$processInteger`("aamod_smaa_quality", smaaQuality.get())
        ssaaScale.set(if (scale in 1..scales.size) scale else DEFAULT_SSAA_SCALE)
        smaaQuality.set(if (quality in 0..3) quality else DEFAULT_SMAA_QUALITY)
    }
}
