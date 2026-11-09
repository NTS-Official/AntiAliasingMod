package com.nts.aamod.client.option

import com.mojang.serialization.Codec
import com.nts.aamod.client.mixin.option.OptionFieldAccess
import com.nts.aamod.client.antialiasing.taa.TemporalJitterSequence
import net.minecraft.client.OptionInstance
import net.minecraft.client.Options
import net.minecraft.network.chat.Component
import java.util.Locale

object TemporalSettings {
    const val DEFAULT_SEQUENCE = 0
    const val DEFAULT_SAMPLES = 16
    const val DEFAULT_SHARPNESS = 0.0
    val sequence = OptionInstance(
        "aamod.config.taa.sequence",
        OptionInstance.cachedConstantTooltip(Component.translatable("aamod.config.taa.sequence.tooltip")),
        { _, value -> Component.translatable("aamod.config.taa.sequence.$value") },
        OptionInstance.Enum(TemporalJitterSequence.entries.indices.toList(), Codec.INT),
        DEFAULT_SEQUENCE,
        { _ -> }
    )
    val samples = OptionInstance(
        "aamod.config.taa.samples",
        OptionInstance.cachedConstantTooltip(Component.translatable("aamod.config.taa.samples.tooltip")),
        { _, value -> Component.literal(value.toString()) },
        OptionInstance.Enum(listOf(8, 16, 32), Codec.INT),
        DEFAULT_SAMPLES,
        { _ -> }
    )
    val sharpness = OptionInstance(
        "aamod.config.taa.sharpness",
        OptionInstance.cachedConstantTooltip(Component.translatable("aamod.config.taa.sharpness.tooltip")),
        { caption, value -> Options.genericValueLabel(caption, Component.literal(String.format(Locale.ROOT, "%.2f", value))) },
        OptionInstance.UnitDouble.INSTANCE,
        DEFAULT_SHARPNESS,
        { _ -> }
    )

    fun resetDefaults() {
        sequence.set(DEFAULT_SEQUENCE)
        samples.set(DEFAULT_SAMPLES)
        sharpness.set(DEFAULT_SHARPNESS)
    }

    fun processOptions(access: OptionFieldAccess) {
        val selectedSequence = access.`aamod$processInteger`("aamod_taa_sequence", sequence.get())
        val selectedSamples = access.`aamod$processInteger`("aamod_taa_samples", samples.get())
        val selectedSharpness = access.`aamod$processFloat`("aamod_taa_sharpness", sharpness.get().toFloat()).toDouble()
        sequence.set(if (selectedSequence in TemporalJitterSequence.entries.indices) selectedSequence else DEFAULT_SEQUENCE)
        samples.set(if (selectedSamples in listOf(8, 16, 32)) selectedSamples else DEFAULT_SAMPLES)
        sharpness.set(if (selectedSharpness.isFinite() && selectedSharpness in 0.0..1.0) selectedSharpness else DEFAULT_SHARPNESS)
    }
}
