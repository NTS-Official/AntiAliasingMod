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
    const val DEFAULT_EFFECTS = true
    const val DEFAULT_EFFECT_BUDGET_MIB = 2048
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
    val effects = OptionInstance.createBoolean(
        "aamod.config.taa.effects",
        OptionInstance.cachedConstantTooltip(Component.translatable("aamod.config.taa.effects.tooltip")),
        DEFAULT_EFFECTS
    )
    val effectBudget = OptionInstance(
        "aamod.config.taa.effect_budget",
        OptionInstance.cachedConstantTooltip(Component.translatable("aamod.config.taa.effect_budget.tooltip")),
        { caption, value -> Options.genericValueLabel(caption, Component.literal("$value MiB")) },
        OptionInstance.IntRange(128, 4096),
        DEFAULT_EFFECT_BUDGET_MIB,
        { _ -> }
    )

    fun resetDefaults() {
        sequence.set(DEFAULT_SEQUENCE)
        samples.set(DEFAULT_SAMPLES)
        sharpness.set(DEFAULT_SHARPNESS)
        effects.set(DEFAULT_EFFECTS)
        effectBudget.set(DEFAULT_EFFECT_BUDGET_MIB)
    }

    fun processOptions(access: OptionFieldAccess) {
        val selectedSequence = access.`aamod$processInteger`("aamod_taa_sequence", sequence.get())
        val selectedSamples = access.`aamod$processInteger`("aamod_taa_samples", samples.get())
        val selectedSharpness = access.`aamod$processFloat`("aamod_taa_sharpness", sharpness.get().toFloat()).toDouble()
        val selectedEffects = access.`aamod$processString`("aamod_taa_effects", effects.get().toString())
        val selectedBudget = access.`aamod$processString`("aamod_taa_effect_budget_mib", effectBudget.get().toString()).toIntOrNull()
        sequence.set(if (selectedSequence in TemporalJitterSequence.entries.indices) selectedSequence else DEFAULT_SEQUENCE)
        samples.set(if (selectedSamples in listOf(8, 16, 32)) selectedSamples else DEFAULT_SAMPLES)
        sharpness.set(if (selectedSharpness.isFinite() && selectedSharpness in 0.0..1.0) selectedSharpness else DEFAULT_SHARPNESS)
        effects.set(when (selectedEffects) { "true" -> true; "false" -> false; else -> DEFAULT_EFFECTS })
        effectBudget.set(if (selectedBudget != null && selectedBudget in 128..4096) selectedBudget else DEFAULT_EFFECT_BUDGET_MIB)
    }
}
