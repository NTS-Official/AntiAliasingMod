package com.nts.aamod.client.mixin.option;

import com.nts.aamod.option.VideoAntiAliasingSettings;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public abstract class OptionsMixin {
    @Inject(method = "load", at = @At("HEAD"))
    private void aamod$beginLoading(CallbackInfo callback) {
        VideoAntiAliasingSettings.beginLoading();
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void aamod$endLoading(CallbackInfo callback) {
        VideoAntiAliasingSettings.endLoading();
    }

    @Inject(method = "processOptions", at = @At("TAIL"))
    private void aamod$processOptions(@Coerce Object access, CallbackInfo callback) {
        int loadedMode = ((OptionFieldAccess) access).aamod$processInteger(
                VideoAntiAliasingSettings.OPTIONS_KEY, VideoAntiAliasingSettings.getCurrentAAMode());
        VideoAntiAliasingSettings.setLoadedMode(loadedMode);
    }

    @Inject(method = "applyGraphicsPreset", at = @At("TAIL"))
    private void aamod$applyPreset(GraphicsPreset preset, CallbackInfo callback) {
        VideoAntiAliasingSettings.applyFromPreset(preset.getSerializedName());
    }
}
