package com.nts.aamod.client.mixin.render;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.nts.aamod.client.antialiasing.AntiAliasingController;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow @Final private LevelTargetBundle targets;

    @Inject(method = "addAlwaysOnTopPass", at = @At("HEAD"))
    private void aamod$worldResolve(FrameGraphBuilder frameGraph, FeatureRenderDispatcher.PreparedFrame featureFrame,
                                   GpuBufferSlice fog, CallbackInfo callback) {
        AntiAliasingController.addWorldResolve(frameGraph, this.targets);
    }
}
