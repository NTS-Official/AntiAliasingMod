package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.PrimitiveTopology
import com.nts.aamod.client.antialiasing.taa.motion.GeometryKey
import com.nts.aamod.client.antialiasing.taa.motion.MotionBatch
import com.nts.aamod.client.antialiasing.taa.motion.MotionOwner
import com.nts.aamod.client.mixin.render.effects.EffectTextureBindingAccess
import com.nts.aamod.client.mixin.render.motion.RenderSetupAccess
import com.nts.aamod.client.mixin.render.motion.RenderTypeAccess
import net.minecraft.client.Minecraft
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite
import net.minecraft.client.renderer.texture.TextureAtlas
import org.joml.Vector4f
import java.util.WeakHashMap

/** Shares the existing model capture; never wraps or submits the native geometry a second time. */
object ModelEffects {
    private class Surface(var frame: Long, var geometry: FloatArray)
    private val surfaces = WeakHashMap<MotionOwner, MutableMap<GeometryKey, Surface>>()
    private var frame = -1L

    fun beginFrame(frameId: Long) {
        frame = frameId
        surfaces.values.forEach { entries -> entries.entries.removeIf { it.value.frame != frameId - 1 } }
    }

    internal fun capture(owner: MotionOwner, key: GeometryKey, batch: MotionBatch, clips: FloatArray, reliable: Boolean) {
        if (!captures(batch)) return
        val geometry = batch.geometry()
        val entries = surfaces.getOrPut(owner) { mutableMapOf() }
        val surface = entries.getOrPut(key) { Surface(frame, geometry) }
        val unchanged = surface.geometry.size == geometry.size && geometry.indices.all {
            kotlin.math.abs(surface.geometry[it] - geometry[it]) <= if (it % 5 < 3) 0.0001f else 0.000001f
        }
        surface.geometry = geometry
        surface.frame = frame
        val identity = TemporalEffects.featureOwner(surface)
        val coherenceOwner = TemporalEffects.featureOwner(owner)
        capture(batch, clips, identity, coherenceOwner, reliable && unchanged)
    }

    internal fun captureUnreliable(batch: MotionBatch, clips: FloatArray) {
        if (!captures(batch)) return
        capture(batch, clips, TemporalEffects.featureOwner(batch), null, false)
    }

    private fun captures(batch: MotionBatch): Boolean = TemporalEffects.active && batch.material.type.hasBlending() &&
        batch.material.type.outputTarget() != OutputTarget.OUTLINE_TARGET

    private fun capture(batch: MotionBatch, clips: FloatArray, identity: Long, coherenceOwner: Long?, reliable: Boolean) {
        val type = batch.material.type
        val setup = (type as RenderTypeAccess).`aamod$getState`() as Any as RenderSetupAccess
        val texture = (setup.`aamod$getTextures`()["Sampler0"] as? EffectTextureBindingAccess)?.`aamod$getLocation`()
            ?: MissingTextureAtlasSprite.getLocation()
        val domain = if (type.outputTarget() == OutputTarget.ITEM_ENTITY_TARGET) EffectDomain.ITEM_ENTITY else EffectDomain.TRANSLUCENT
        val layer = SingleQuadParticle.Layer(true, texture, type.pipeline())
        val primitive = if (type.primitiveTopology() == PrimitiveTopology.QUADS) 4 else 3
        val atlas = Minecraft.getInstance().textureManager.getTexture(texture) as? TextureAtlas
        val uvTransform = Vector4f()
        repeat(batch.count / primitive) { face ->
            val indices = IntArray(4) { face * primitive + minOf(it, primitive - 1) }
            val uv = FloatArray(8)
            for ((corner, index) in indices.withIndex()) {
                batch.material.textureMatrix.transform(uvTransform.set(batch.value(index, 3), batch.value(index, 4), 0f, 1f))
                uv[corner * 2] = uvTransform.x
                uv[corner * 2 + 1] = uvTransform.y
            }
            val revision = atlas?.let { AtlasAnimationRevision.revision(it, uv) } ?: 0L
            val alpha = FloatArray(4) { batch.value(indices[it], 5) }
            val color = ((alpha[0].coerceIn(0f, 1f) * 255f).toInt() shl 24) or 0xffffff
            val surfaceKey = EffectSurfaceKey(EffectKind.TRANSLUCENT, identity, face, "${type.pipeline().location}|$texture")
            val quad = EffectQuad(surfaceKey, layer, FloatArray(12), uv, color, alpha, batch.source, coherenceOwner)
            val clip = FloatArray(16) { clips[indices[it / 4] * 4 + it % 4] }
            TemporalEffects.captureProjected(quad, domain, clip, EffectAppearance(uv, color, revision, alpha),
                reliable = reliable && "DISSOLVE" !in type.pipeline().shaderDefines.flags())
        }
    }

    fun close() { surfaces.clear(); frame = -1L }
}
