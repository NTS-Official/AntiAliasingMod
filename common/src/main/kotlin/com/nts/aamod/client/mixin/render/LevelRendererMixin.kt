package com.nts.aamod.client.mixin.render

import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.framegraph.FrameGraphBuilder
import com.nts.aamod.client.antialiasing.AntiAliasingController
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LevelRenderer::class)
abstract class LevelRendererMixin {
    @Shadow
    @Final
    private val targets: LevelTargetBundle? = null

    @Inject(method = ["addAlwaysOnTopPass"], at = [At("HEAD")])
    private fun `aamod$worldResolve`(
        frameGraph: FrameGraphBuilder, featureFrame: FeatureRenderDispatcher.PreparedFrame?,
        fog: GpuBufferSlice?, callback: CallbackInfo?
    ) {
        AntiAliasingController.addWorldResolve(frameGraph, this.targets!!)
    }
}
