package com.nts.aamod.client.mixin.render.motion

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import org.spongepowered.asm.mixin.Mixin

@Mixin(ModelFeatureRenderer::class)
abstract class ModelMotionMixin {
    @WrapMethod(method = ["prepareModel"])
    private fun <State : Any> `aamod$modelSubmission`(submit: ModelFeatureRenderer.Submit<State>, original: Operation<Void>) {
        MotionCapture.withSubmission(submit) { original.call(submit) }
    }
}
