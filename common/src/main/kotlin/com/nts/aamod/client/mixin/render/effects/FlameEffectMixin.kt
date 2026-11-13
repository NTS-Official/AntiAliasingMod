package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.vertex.VertexConsumer
import com.nts.aamod.client.antialiasing.taa.effects.FlameEffects
import net.minecraft.client.renderer.feature.FlameFeatureRenderer
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import org.spongepowered.asm.mixin.Mixin

@Mixin(FlameFeatureRenderer::class)
abstract class FlameEffectMixin {
    @WrapMethod(method = ["prepare"])
    private fun `aamod$captureFlame`(
        submit: FlameFeatureRenderer.Submit, builder: VertexConsumer,
        first: TextureAtlasSprite, second: TextureAtlasSprite, original: Operation<Void>
    ) {
        val capture = FlameEffects.capture(submit, builder)
        original.call(submit, capture ?: builder, first, second)
        capture?.finish()
    }
}
