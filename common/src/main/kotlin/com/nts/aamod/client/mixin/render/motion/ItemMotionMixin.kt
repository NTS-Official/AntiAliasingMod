package com.nts.aamod.client.mixin.render.motion

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.blaze3d.vertex.VertexConsumer
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import net.minecraft.client.renderer.feature.ItemFeatureRenderer
import net.minecraft.client.resources.model.geometry.BakedQuad
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(ItemFeatureRenderer::class)
abstract class ItemMotionMixin {
    @WrapMethod(method = ["prepareMainSubmit"])
    private fun `aamod$itemSubmission`(submit: ItemFeatureRenderer.Submit, original: Operation<Void>) {
        MotionCapture.withSubmission(submit) { original.call(submit) }
    }

    @WrapOperation(method = ["prepareMainSubmit"], at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack\$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V")])
    private fun `aamod$itemQuad`(consumer: VertexConsumer, pose: PoseStack.Pose, quad: BakedQuad, instance: QuadInstance, original: Operation<Void>) {
        MotionCapture.withGeometry(quad, pose.pose(), true) { original.call(consumer, pose, quad, instance) }
    }
}
