package com.nts.aamod.client.mixin.option

import net.minecraft.client.Options
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(Options::class)
interface OptionsAccessor {
    @Invoker("setGraphicsPresetToCustom")
    fun `aamod$setGraphicsPresetToCustom`()
}
