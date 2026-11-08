package com.nts.aamod.client.option

import com.nts.aamod.client.mixin.option.OptionFieldAccess
import net.minecraft.client.OptionInstance
import net.minecraft.client.Options
import net.minecraft.network.chat.Component
import java.util.Locale

data class FxaaParameters(
    val subpixel: Float,
    val edgeThreshold: Float,
    val minimumThreshold: Float,
    val searchSteps: Int
)

object FxaaSettings {
    const val DEFAULT_SUBPIXEL = 0.75
    const val DEFAULT_EDGE_THRESHOLD = 0.166
    const val DEFAULT_MINIMUM_THRESHOLD = 0.0833
    const val DEFAULT_SEARCH_STEPS = 5
    const val MAX_SEARCH_STEPS = 5

    val subpixel = decimalOption("subpixel", DEFAULT_SUBPIXEL)
    val edgeThreshold = decimalOption("edge_threshold", DEFAULT_EDGE_THRESHOLD)
    val minimumThreshold = decimalOption("minimum_threshold", DEFAULT_MINIMUM_THRESHOLD)
    val searchSteps = OptionInstance(
        "aamod.config.fxaa.search_steps",
        OptionInstance.cachedConstantTooltip(Component.translatable("aamod.config.fxaa.search_steps.tooltip")),
        { caption, value -> Options.genericValueLabel(caption, Component.literal(value.toString())) },
        OptionInstance.IntRange(1, MAX_SEARCH_STEPS),
        DEFAULT_SEARCH_STEPS,
        { _ -> }
    )
    val options: List<OptionInstance<*>> = listOf(subpixel, edgeThreshold, minimumThreshold, searchSteps)

    fun snapshot() = FxaaParameters(
        subpixel.get().toFloat(),
        edgeThreshold.get().toFloat(),
        minimumThreshold.get().toFloat(),
        searchSteps.get()
    )

    fun resetDefaults() {
        subpixel.set(DEFAULT_SUBPIXEL)
        edgeThreshold.set(DEFAULT_EDGE_THRESHOLD)
        minimumThreshold.set(DEFAULT_MINIMUM_THRESHOLD)
        searchSteps.set(DEFAULT_SEARCH_STEPS)
    }

    fun processOptions(access: OptionFieldAccess) {
        processDecimal(access, "aamod_fxaa_subpixel", subpixel, DEFAULT_SUBPIXEL)
        processDecimal(access, "aamod_fxaa_edge_threshold", edgeThreshold, DEFAULT_EDGE_THRESHOLD)
        processDecimal(access, "aamod_fxaa_minimum_threshold", minimumThreshold, DEFAULT_MINIMUM_THRESHOLD)
        val loadedSteps = access.`aamod$processInteger`("aamod_fxaa_search_steps", searchSteps.get())
        searchSteps.set(if (loadedSteps in 1..MAX_SEARCH_STEPS) loadedSteps else DEFAULT_SEARCH_STEPS)
    }

    private fun processDecimal(access: OptionFieldAccess, key: String, option: OptionInstance<Double>, defaultValue: Double) {
        val loaded = access.`aamod$processFloat`(key, option.get().toFloat()).toDouble()
        option.set(if (loaded.isFinite() && loaded in 0.0..1.0) loaded else defaultValue)
    }

    private fun decimalOption(name: String, defaultValue: Double): OptionInstance<Double> = OptionInstance(
        "aamod.config.fxaa.$name",
        OptionInstance.cachedConstantTooltip(Component.translatable("aamod.config.fxaa.$name.tooltip")),
        { caption, value -> Options.genericValueLabel(caption, Component.literal(String.format(Locale.ROOT, "%.4f", value))) },
        OptionInstance.UnitDouble.INSTANCE,
        defaultValue,
        { _ -> }
    )
}
