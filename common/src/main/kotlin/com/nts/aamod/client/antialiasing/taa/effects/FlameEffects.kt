package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.vertex.VertexConsumer
import com.nts.aamod.client.antialiasing.taa.motion.MotionOwnerAccess
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.feature.FlameFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.TextureAtlas

object FlameEffects {
    fun capture(
        submit: FlameFeatureRenderer.Submit, builder: VertexConsumer
    ): EffectQuadConsumer? {
        if (!TemporalEffects.active) return null
        val identity = (submit.entityRenderState() as MotionOwnerAccess).aamodMotionOwner ?: return null
        val owner = TemporalEffects.featureOwner(identity)
        val occurrence = TemporalEffects.featureOccurrence(EffectKind.FLAME, owner)
        val atlas = net.minecraft.client.Minecraft.getInstance().textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS) as? TextureAtlas
        val layer = SingleQuadParticle.Layer(false, TextureAtlas.LOCATION_BLOCKS,
            RenderTypes.entityCutoutCull(TextureAtlas.LOCATION_BLOCKS).pipeline())
        return EffectQuadConsumer(builder) { part, position, uv, colors ->
            val key = EffectSurfaceKey(EffectKind.FLAME, owner, part, "${layer.pipeline().location}|$occurrence")
            val alphas = FloatArray(4) { (colors[it] ushr 24) / 255f }
            val revision = atlas?.let { AtlasAnimationRevision.revision(it, uv) } ?: 0L
            TemporalEffects.capture(EffectQuad(key, layer, position, uv, colors[0], alphas), EffectDomain.OPAQUE,
                EffectAppearance(uv, colors[0], revision, alphas))
        }
    }
}
