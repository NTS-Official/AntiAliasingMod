package com.nts.aamod.client.mixin.option;

import com.nts.aamod.option.VideoAntiAliasingSettings;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingScreenMixin {
    @Inject(method = "qualityOptions", at = @At("RETURN"), cancellable = true)
    private static void qualityOptions(Options options, CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        OptionInstance<?>[] vanillaOptions = cir.getReturnValue();
        OptionInstance<?>[] extendedOptions = Arrays.copyOf(vanillaOptions, vanillaOptions.length + 1);
        extendedOptions[vanillaOptions.length] = VideoAntiAliasingSettings.getOption();
        cir.setReturnValue(extendedOptions);
    }
}
