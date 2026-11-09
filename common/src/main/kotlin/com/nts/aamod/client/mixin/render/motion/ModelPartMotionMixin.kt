package com.nts.aamod.client.mixin.render.motion

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import net.minecraft.client.model.geom.ModelPart
import org.spongepowered.asm.mixin.Mixin

@Mixin(ModelPart::class)
abstract class ModelPartMotionMixin {
    @WrapMethod(method = ["compile"])
    private fun `aamod$modelPart`(pose: PoseStack.Pose, consumer: VertexConsumer, light: Int, overlay: Int, color: Int, original: Operation<Void>) {
        MotionCapture.withGeometry(this, pose.pose(), true) { original.call(pose, consumer, light, overlay, color) }
    }
}
