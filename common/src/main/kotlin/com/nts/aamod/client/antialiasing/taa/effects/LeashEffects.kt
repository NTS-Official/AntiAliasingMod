package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.feature.LeashFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite

object LeashEffects {
    fun capture(submit: LeashFeatureRenderer.Submit, builder: VertexConsumer): EffectStripConsumer? {
        if (!TemporalEffects.active) return null
        val identity = (submit as FeatureEffectOwner).aamodEffectOwner ?: return null
        val owner = TemporalEffects.featureOwner(identity)
        val occurrence = TemporalEffects.featureOccurrence(EffectKind.LEASH, owner)
        val layer = SingleQuadParticle.Layer(false, MissingTextureAtlasSprite.getLocation(), RenderTypes.leash().pipeline())
        return EffectStripConsumer(builder) { part, position, uv, colors ->
            val key = EffectSurfaceKey(EffectKind.LEASH, owner, part, "${layer.pipeline().location}|$occurrence")
            val alphas = FloatArray(4) { (colors[it] ushr 24) / 255f }
            TemporalEffects.capture(EffectQuad(key, layer, position, uv, colors[0], alphas), EffectDomain.OPAQUE,
                EffectAppearance(uv, colors[0], alphas = alphas))
        }
    }
}
