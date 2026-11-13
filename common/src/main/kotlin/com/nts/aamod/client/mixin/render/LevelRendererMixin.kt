package com.nts.aamod.client.mixin.render

import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.framegraph.FrameGraphBuilder
import com.nts.aamod.client.antialiasing.AntiAliasingController
import com.nts.aamod.client.antialiasing.taa.composition.NativeTransparencyContract
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.PostChain
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

    @WrapOperation(method = ["render"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PostChain;addToFrame(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;IILnet/minecraft/client/renderer/PostChain\$TargetBundle;)V")])
    private fun `aamod$prepareEffectsComposition`(
        chain: PostChain, frameGraph: FrameGraphBuilder, width: Int, height: Int,
        bundle: PostChain.TargetBundle, original: Operation<Void>
    ) {
        if (NativeTransparencyContract.isTransparency(chain) && bundle === targets) {
            AntiAliasingController.addEffectsComposition(frameGraph, checkNotNull(targets), chain)
        }
        original.call(chain, frameGraph, width, height, bundle)
    }

    @Inject(method = ["addAlwaysOnTopPass"], at = [At("HEAD")])
    private fun `aamod$worldResolve`(
        frameGraph: FrameGraphBuilder, featureFrame: FeatureRenderDispatcher.PreparedFrame?,
        fog: GpuBufferSlice?, callback: CallbackInfo?
    ) {
        AntiAliasingController.addWorldResolve(frameGraph, this.targets!!)
    }
}
