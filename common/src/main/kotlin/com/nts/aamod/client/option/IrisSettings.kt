package com.nts.aamod.client.option

import com.mojang.serialization.Codec
import com.nts.aamod.client.compat.iris.IrisCompatibility
import com.nts.aamod.client.mixin.option.OptionFieldAccess
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

object IrisSettings {
    const val AUTOMATIC = 0
    const val AFTER_SHADERS = 1
    val policy = OptionInstance(
        "aamod.config.iris.policy",
        { _: Int -> Tooltip.create(Component.translatable("aamod.config.iris.policy.tooltip")
            .append("\n\n").append(IrisCompatibility.describe())) },
        { _, value -> Component.translatable("aamod.config.iris.policy.$value") },
        OptionInstance.Enum(listOf(AUTOMATIC, AFTER_SHADERS), Codec.INT),
        AUTOMATIC,
        { _ -> }
    )

    fun resetDefaults() = policy.set(AUTOMATIC)

    fun processOptions(access: OptionFieldAccess) {
        val selected = access.`aamod$processInteger`("aamod_iris_policy", policy.get())
        policy.set(if (selected == AFTER_SHADERS) AFTER_SHADERS else AUTOMATIC)
    }
}
