package com.nts.aamod.client.mixin.option;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.client.Options$FieldAccess")
public interface OptionFieldAccess {
    @Invoker("process")
    int aamod$processInteger(String name, int value);
}
