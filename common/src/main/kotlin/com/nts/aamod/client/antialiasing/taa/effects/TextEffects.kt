package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.systems.RenderSystem
import com.nts.aamod.client.mixin.render.effects.EffectTextureBindingAccess
import com.nts.aamod.client.mixin.render.motion.RenderSetupAccess
import com.nts.aamod.client.mixin.render.motion.RenderTypeAccess
import com.nts.aamod.client.antialiasing.taa.motion.StableCustomGeometry
import com.nts.aamod.client.antialiasing.taa.composition.NativeEffectDraws
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer
import net.minecraft.client.renderer.feature.TextFeatureRenderer
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.util.FormattedCharSequence
import org.joml.Matrix4f
import org.joml.Vector4f

object TextEffects {
    private class BackgroundGeometry(private val delegate: SubmitNodeCollector.CustomGeometryRenderer) : StableCustomGeometry {
        override fun render(pose: PoseStack.Pose, builder: VertexConsumer) { delegate.render(pose, builder) }
    }

    fun backgroundGeometry(renderer: SubmitNodeCollector.CustomGeometryRenderer, type: RenderType): SubmitNodeCollector.CustomGeometryRenderer =
        if (TemporalEffects.active && type === RenderTypes.textBackground()) BackgroundGeometry(renderer) else renderer

    private class Scope(val owner: Long, val occurrence: Int, val revision: Long) { var part = 0 }
    private var scope: Scope? = null

    fun begin(submit: Any) {
        scope = null
        if (!TemporalEffects.active) return
        val mode: Font.DisplayMode
        val text: FormattedCharSequence
        when (submit) {
            is TextFeatureRenderer.Submit -> { mode = submit.displayMode(); text = submit.string() }
            is NameTagFeatureRenderer.Submit -> { mode = submit.displayMode(); text = submit.text().visualOrderText }
            else -> return
        }
        val identity = (submit as? FeatureEffectOwner)?.aamodEffectOwner ?: return
        beginScope(identity, mode, text)
    }

    private fun beginScope(identity: Any, mode: Font.DisplayMode, text: FormattedCharSequence) {
        if (mode == Font.DisplayMode.SEE_THROUGH) return
        var revision = 0xcbf29ce484222325UL.toLong()
        text.accept { _, style, codepoint ->
            revision = (revision xor codepoint.toLong()) * 1099511628211L
            revision = (revision xor style.hashCode().toLong()) * 1099511628211L
            true
        }
        val owner = TemporalEffects.featureOwner(identity)
        scope = Scope(owner, TemporalEffects.featureOccurrence(EffectKind.TEXT, owner), revision)
    }

    fun end() { scope = null }

    fun capture(builder: VertexConsumer, type: RenderType): EffectQuadConsumer? {
        val current = scope ?: return null
        if (!TemporalEffects.active || type.isOutline()) return null
        val state = (type as RenderTypeAccess).`aamod$getState`() as Any as RenderSetupAccess
        val texture = (state.`aamod$getTextures`()["Sampler0"] as? EffectTextureBindingAccess)?.`aamod$getLocation`() ?: return null
        val layer = SingleQuadParticle.Layer(type.hasBlending(), texture, type.pipeline())
        val modelView = RenderSystem.getModelViewMatrixCopy()
        state.`aamod$getLayeringTransform`().modifier?.accept(modelView)
        val textureMatrix = if ("APPLY_TEXTURE_MATRIX" in type.pipeline().shaderDefines.flags()) {
            state.`aamod$getTextureTransform`().createMatrix()
        } else Matrix4f()
        val domain = when (type.outputTarget()) {
            OutputTarget.ITEM_ENTITY_TARGET -> EffectDomain.ITEM_ENTITY
            else -> if (type.hasBlending()) EffectDomain.TRANSLUCENT else EffectDomain.OPAQUE
        }
        return EffectQuadConsumer(builder) { _, position, uv, colors ->
            val key = EffectSurfaceKey(EffectKind.TEXT, current.owner, current.part++, "${type.pipeline().location}|$texture|${current.occurrence}")
            val transformedUv = FloatArray(8)
            val coordinate = Vector4f()
            repeat(4) { corner ->
                textureMatrix.transform(coordinate.set(uv[corner * 2], uv[corner * 2 + 1], 0f, 1f))
                transformedUv[corner * 2] = coordinate.x
                transformedUv[corner * 2 + 1] = coordinate.y
            }
            val alphas = FloatArray(4) { (colors[it] ushr 24) / 255f }
            TemporalEffects.capture(EffectQuad(key, layer, position, transformedUv, colors[0], alphas, NativeEffectDraws.source(builder)), domain,
                EffectAppearance(transformedUv, colors[0], current.revision, alphas), modelView = modelView)
        }
    }
}
