package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.systems.RenderSystem
import com.nts.aamod.AAMod
import com.nts.aamod.client.antialiasing.taa.composition.NativeEffectDraws
import net.minecraft.client.Minecraft
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import org.joml.Matrix4f
import org.joml.Quaternionf
import java.util.WeakHashMap

class EffectQuad @JvmOverloads constructor(
    val key: EffectSurfaceKey, val layer: SingleQuadParticle.Layer,
    val position: FloatArray, val uv: FloatArray, val color: Int, val alphas: FloatArray? = null,
    val source: Any? = null,
    /** Model lifetime shared by its surfaces; never replaces the individual history token. */
    val coherenceOwner: Long? = null
)

class EffectDraw(val correspondence: EffectCorrespondence, val quad: EffectQuad)

object TemporalEffects {
    private val owners = EffectOwnerRegistry()
    private val history = EffectHistory()
    private val extracted = WeakHashMap<QuadParticleRenderState, MutableList<EffectQuad>>()
    private val effectDraws = mutableListOf<EffectDraw>()
    private val rejections = linkedMapOf<EffectRejection, Int>()
    private val featureOccurrences = mutableMapOf<Pair<EffectKind, Long>, Int>()
    private val kinds = linkedMapOf<EffectKind, Int>()
    private var particleOwner: Long? = null
    private var particlePart = 0
    private var projection: Matrix4f? = null
    private var frame = -1L
    var active = false
        private set
    var capturedParticles = 0
        private set
    var correspondingParticles = 0
        private set
    var failure: String? = null
        private set
    val draws: List<EffectDraw> get() = effectDraws
    val captureRejections: Map<EffectRejection, Int> get() = rejections.toMap()
    val capturedKinds: Map<EffectKind, Int> get() = kinds.toMap()
    val rejectedDomains: Set<EffectDomain> get() = history.rejectedDomains
    val rejectedReasons: Map<EffectDomain, EffectRejection> get() = history.rejectedReasons

    fun rejectDomain(domain: EffectDomain, reason: EffectRejection) {
        if (active) history.rejectDomain(domain, reason)
    }

    fun featureOwner(instance: Any): Long = owners.owner(instance)

    fun featureOccurrence(kind: EffectKind, owner: Long): Int {
        val key = kind to owner
        val occurrence = featureOccurrences.getOrDefault(key, 0)
        featureOccurrences[key] = occurrence + 1
        return occurrence
    }

    fun beginFrame(frameId: Long) {
        endFrame()
        if (failure != null) return
        active = true
        frame = frameId
        owners.drain()
        history.begin(frameId)
        capturedParticles = 0
        correspondingParticles = 0
        rejections.clear()
        kinds.clear()
        ModelEffects.beginFrame(frameId)
        WeatherEffects.clear()
    }

    fun setProjection(matrix: Matrix4f) {
        if (active) projection = Matrix4f(matrix)
    }

    fun <Result> withoutCapture(action: () -> Result): Result {
        val previous = active
        active = false
        try { return action() } finally { active = previous }
    }

    fun <Result> withParticle(particle: Any, action: () -> Result): Result {
        if (!active) return action()
        val previousOwner = particleOwner
        val previousPart = particlePart
        particleOwner = owners.owner(particle)
        particlePart = 0
        try { return action() } finally {
            particleOwner = previousOwner
            particlePart = previousPart
        }
    }

    fun addQuad(
        state: QuadParticleRenderState, layer: SingleQuadParticle.Layer,
        x: Float, y: Float, z: Float, xRot: Float, yRot: Float, zRot: Float, wRot: Float,
        scale: Float, u0: Float, u1: Float, v0: Float, v1: Float, color: Int
    ) {
        if (!active) return
        val owner = particleOwner ?: return
        val key = EffectSurfaceKey(EffectKind.PARTICLE, owner, particlePart++, "${layer.pipeline().location}|${layer.textureAtlasLocation()}")
        val position = EffectGeometry.quad(x, y, z, Quaternionf(xRot, yRot, zRot, wRot), scale)
        val uv = floatArrayOf(u1, v1, u1, v0, u0, v0, u0, v1)
        extracted.getOrPut(state) { mutableListOf() }.add(EffectQuad(key, layer, position, uv, color))
    }

    fun clear(state: QuadParticleRenderState) { extracted.remove(state) }

    fun prepareParticles(state: QuadParticleRenderState, translucent: Boolean) {
        if (!active) return
        val actualProjection = projection ?: return
        captureSafely {
            val matrix = Matrix4f(actualProjection).mul(RenderSystem.getModelViewMatrixCopy())
            for (quad in extracted[state].orEmpty()) {
                if (quad.layer.translucent() != translucent) continue
                val clip = EffectGeometry.project(quad.position, matrix)
                if (clip.any { !it.isFinite() }) continue
                val atlas = Minecraft.getInstance().textureManager.getTexture(quad.layer.textureAtlasLocation()) as? TextureAtlas
                val revision = atlas?.let { AtlasAnimationRevision.revision(it, quad.uv) } ?: 0L
                val correspondence = history.capture(
                    quad.key, if (translucent) EffectDomain.PARTICLES else EffectDomain.OPAQUE,
                    clip, EffectAppearance(quad.uv, quad.color, revision, quad.alphas)
                )
                if (correspondence == null) {
                    rejections.merge(history.rejectedReasons[if (translucent) EffectDomain.PARTICLES else EffectDomain.OPAQUE]
                        ?: EffectRejection.CAPTURE_BUDGET, 1, Int::plus)
                    continue
                }
                capturedParticles++
                if (correspondence.confidence > 0f) correspondingParticles++
                else rejections.merge(correspondence.rejection, 1, Int::plus)
                effectDraws.add(EffectDraw(correspondence, quad))
                kinds.merge(quad.key.kind, 1, Int::plus)
            }
        }
    }

    fun capture(
        quad: EffectQuad, domain: EffectDomain, appearance: EffectAppearance, pattern: EffectPattern? = null,
        modelView: Matrix4f? = null
    ) {
        if (!active) return
        val actualProjection = projection ?: return
        captureSafely {
            val matrix = Matrix4f(actualProjection).mul(modelView ?: RenderSystem.getModelViewMatrixCopy())
            val clip = EffectGeometry.project(quad.position, matrix)
            captureProjected(quad, domain, clip, appearance, pattern)
        }
    }

    internal fun captureProjected(
        quad: EffectQuad, domain: EffectDomain, clip: FloatArray, appearance: EffectAppearance,
        pattern: EffectPattern? = null, reliable: Boolean = true
    ) {
        if (!active || clip.any { !it.isFinite() }) return
        captureSafely {
            val correspondence = history.capture(quad.key, domain, clip, appearance, pattern, reliable)
            if (correspondence == null) {
                rejections.merge(history.rejectedReasons[domain] ?: EffectRejection.CAPTURE_BUDGET, 1, Int::plus)
            }
            else {
                if (correspondence.confidence <= 0f) rejections.merge(correspondence.rejection, 1, Int::plus)
                effectDraws.add(EffectDraw(correspondence, quad))
                kinds.merge(quad.key.kind, 1, Int::plus)
            }
        }
    }

    private inline fun captureSafely(action: () -> Unit) {
        try { action() } catch (exception: Exception) {
            failure = "effect_capture_failed"
            endFrame()
            AAMod.LOGGER.error("Temporal effect capture failed; retaining the native current-frame path until reset", exception)
        }
    }

    fun commit() {
        if (active) history.commit(frame)
    }

    fun resetHistory() {
        history.reset()
        effectDraws.clear()
        if (active) history.begin(frame)
    }

    fun endFrame() {
        history.abort()
        active = false
        particleOwner = null
        particlePart = 0
        projection = null
        extracted.clear()
        effectDraws.clear()
        featureOccurrences.clear()
        NativeEffectDraws.clear()
        TerrainEffects.endFrame()
        TextEffects.end()
        WeatherEffects.clear()
    }

    fun close() {
        endFrame()
        history.reset()
        owners.clear()
        ModelEffects.close()
        capturedParticles = 0
        correspondingParticles = 0
        rejections.clear()
        kinds.clear()
        failure = null
    }
}
