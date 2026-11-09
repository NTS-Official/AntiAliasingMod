package com.nts.aamod.client.mixin.render.motion

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.sugar.Local
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.blaze3d.vertex.VertexConsumer
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer
import net.minecraft.client.resources.model.geometry.BakedQuad
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(BlockModelFeatureRenderer::class)
abstract class BlockModelMotionMixin {
    @WrapOperation(method = ["buildGroup"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/BlockModelFeatureRenderer;putPartQuads(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModelPart;Lcom/mojang/blaze3d/vertex/PoseStack\$Pose;Lcom/mojang/blaze3d/vertex/QuadInstance;I[ILcom/mojang/blaze3d/vertex/VertexConsumer;)V")])
    private fun `aamod$blockSubmission`(
        part: BlockStateModelPart, pose: PoseStack.Pose, instance: QuadInstance, color: Int, tints: IntArray, consumer: VertexConsumer,
        original: Operation<Void>, @Local submit: BlockModelFeatureRenderer.Submit
    ) {
        MotionCapture.withSubmission(submit) { original.call(part, pose, instance, color, tints, consumer) }
    }

    private companion object {
        @WrapOperation(method = ["putQuad"], at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack\$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V")])
        @JvmStatic
        private fun `aamod$blockQuad`(consumer: VertexConsumer, pose: PoseStack.Pose, quad: BakedQuad, instance: QuadInstance, original: Operation<Void>) {
            MotionCapture.withGeometry(quad, pose.pose(), true) { original.call(consumer, pose, quad, instance) }
        }
    }
}
