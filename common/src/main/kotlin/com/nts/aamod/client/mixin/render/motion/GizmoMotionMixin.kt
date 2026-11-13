package com.nts.aamod.client.mixin.render.motion

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import net.minecraft.client.renderer.feature.FeatureFrameContext
import net.minecraft.client.renderer.feature.GizmoFeatureRenderer
import org.spongepowered.asm.mixin.Mixin

@Mixin(GizmoFeatureRenderer::class)
abstract class GizmoMotionMixin {
    @WrapMethod(method = ["buildGroup"])
    private fun `aamod$prepareWorldGizmos`(context: FeatureFrameContext, submits: List<GizmoFeatureRenderer.Submit>, original: Operation<Void>) {
        MotionCapture.prepareGizmos(submits) { original.call(context, submits) }
    }
}
