package com.nts.aamod.client.mixin.render.effects

import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.nts.aamod.client.antialiasing.taa.composition.FrozenPassRecorder
import com.nts.aamod.client.antialiasing.taa.composition.FrozenRenderPassAccess
import com.nts.aamod.client.antialiasing.taa.composition.TemporalCommandCapture
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(CommandEncoder::class)
abstract class CommandEncoderCaptureMixin {
    @Unique
    private var aamodPendingCapture: FrozenPassRecorder? = null

    @Inject(method = ["createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)Lcom/mojang/blaze3d/systems/RenderPass;"], at = [At("HEAD")])
    private fun `aamod$prepareCapture`(descriptor: RenderPassDescriptor, callback: CallbackInfoReturnable<RenderPass>) {
        aamodPendingCapture = TemporalCommandCapture.capture(descriptor)
    }

    @Inject(method = ["createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)Lcom/mojang/blaze3d/systems/RenderPass;"], at = [At("RETURN")])
    private fun `aamod$attachCapture`(descriptor: RenderPassDescriptor, callback: CallbackInfoReturnable<RenderPass>) {
        val recorder = aamodPendingCapture ?: return
        aamodPendingCapture = null
        (callback.returnValue as FrozenRenderPassAccess).aamodFrozenPass = recorder
    }
}
