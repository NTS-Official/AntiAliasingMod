package com.nts.aamod.client.mixin.option

import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(targets = ["net.minecraft.client.Options\$FieldAccess"])
interface OptionFieldAccess {
    @Invoker("process")
    fun `aamod$processInteger`(name: String?, value: Int): Int

    @Invoker("process")
    fun `aamod$processFloat`(name: String?, value: Float): Float

    @Invoker("process")
    fun `aamod$processString`(name: String?, value: String): String
}
