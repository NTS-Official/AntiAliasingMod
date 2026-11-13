package com.nts.aamod.client.mixin.render.effects

import com.nts.aamod.client.antialiasing.AntiAliasingController
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(FeatureRenderDispatcher.PreparedFrame::class)
abstract class OpaqueWorldCaptureMixin {
    @Inject(method = ["executeSolid"], at = [At("TAIL")])
    private fun `aamod$captureOpaque`(callback: CallbackInfo) {
        AntiAliasingController.opaqueWorldDrawn(Minecraft.getInstance().gameRenderer.mainRenderTarget())
    }
}
