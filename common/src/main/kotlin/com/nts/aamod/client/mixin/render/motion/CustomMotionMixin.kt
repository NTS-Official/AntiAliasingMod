package com.nts.aamod.client.mixin.render.motion

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.sugar.Local
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.feature.CustomFeatureRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(CustomFeatureRenderer::class)
abstract class CustomMotionMixin {
    @WrapOperation(method = ["buildGroup"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector\$CustomGeometryRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack\$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V")])
    private fun `aamod$customGeometry`(
        renderer: SubmitNodeCollector.CustomGeometryRenderer, pose: PoseStack.Pose, consumer: VertexConsumer,
        original: Operation<Void>, @Local submit: CustomFeatureRenderer.Submit
    ) {
        MotionCapture.withSubmission(submit) {
            MotionCapture.withGeometry(renderer.javaClass, pose.pose(), false) { original.call(renderer, pose, consumer) }
        }
    }
}
