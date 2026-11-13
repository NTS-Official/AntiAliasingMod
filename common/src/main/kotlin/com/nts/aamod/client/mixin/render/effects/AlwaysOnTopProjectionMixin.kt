package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.framegraph.FramePass
import com.nts.aamod.client.antialiasing.AntiAliasingController
import net.minecraft.client.renderer.LevelRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(LevelRenderer::class)
abstract class AlwaysOnTopProjectionMixin {
    @WrapOperation(method = ["addAlwaysOnTopPass"], at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FramePass;executes(Ljava/lang/Runnable;)V")])
    private fun `aamod$unjitteredLateWorld`(pass: FramePass, draw: Runnable, original: Operation<Void>) {
        original.call(pass, Runnable { AntiAliasingController.renderAlwaysOnTop { draw.run() } })
    }
}
