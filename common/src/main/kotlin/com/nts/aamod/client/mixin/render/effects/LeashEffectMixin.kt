package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.vertex.VertexConsumer
import com.nts.aamod.client.antialiasing.taa.effects.EffectStripConsumer
import com.nts.aamod.client.antialiasing.taa.effects.LeashEffects
import net.minecraft.client.renderer.feature.LeashFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At

@Mixin(LeashFeatureRenderer::class)
abstract class LeashEffectMixin {
    @Unique private var aamodSubmit: LeashFeatureRenderer.Submit? = null
    @Unique private var aamodStrip: EffectStripConsumer? = null

    @WrapMethod(method = ["prepare"])
    private fun `aamod$leashScope`(submit: LeashFeatureRenderer.Submit, original: Operation<Void>) {
        aamodSubmit = submit
        try { original.call(submit) } finally {
            aamodStrip?.finish()
            aamodStrip = null
            aamodSubmit = null
        }
    }

    @WrapOperation(method = ["prepare"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/LeashFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;")])
    private fun `aamod$leashVertices`(renderer: LeashFeatureRenderer, type: RenderType, original: Operation<VertexConsumer>): VertexConsumer {
        val builder = original.call(renderer, type)
        val capture = aamodSubmit?.let { LeashEffects.capture(it, builder) }
        aamodStrip = capture
        return capture ?: builder
    }
}
