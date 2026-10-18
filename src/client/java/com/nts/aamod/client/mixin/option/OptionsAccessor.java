package com.nts.aamod.client.mixin.option;

import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Options.class)
public interface OptionsAccessor {
    @Invoker("setGraphicsPresetToCustom")
    void aamod$setGraphicsPresetToCustom();
}
