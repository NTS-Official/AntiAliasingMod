package com.nts.aamod.client.mixin.render.motion

import com.llamalad7.mixinextras.injector.ModifyReturnValue
import com.mojang.blaze3d.vertex.VertexConsumer
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(RenderTypeFeatureRenderer::class)
abstract class MotionVertexBuilderMixin {
    @ModifyReturnValue(method = ["getVertexBuilder"], at = [At("RETURN")])
    private fun `aamod$captureVertices`(consumer: VertexConsumer, type: RenderType): VertexConsumer = MotionCapture.wrap(consumer, type)
}
