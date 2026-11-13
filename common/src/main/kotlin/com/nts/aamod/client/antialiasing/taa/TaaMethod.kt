package com.nts.aamod.client.antialiasing.taa

import com.nts.aamod.client.antialiasing.AntiAliasingMethod
import com.nts.aamod.client.antialiasing.CameraSnapshot
import com.nts.aamod.client.antialiasing.FrameContext
import com.nts.aamod.client.antialiasing.InvalidationReason
import com.nts.aamod.client.antialiasing.RenderEnvironment
import com.nts.aamod.client.antialiasing.RenderInput
import com.nts.aamod.client.antialiasing.RenderStage
import com.nts.aamod.client.option.TemporalSettings
import com.nts.aamod.client.option.VideoAntiAliasingSettings
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import com.nts.aamod.client.antialiasing.taa.effects.TemporalEffects
import com.nts.aamod.client.antialiasing.taa.composition.TemporalSceneBridge
import com.nts.aamod.client.antialiasing.taa.composition.TemporalSceneComposition
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import org.joml.Matrix4f

class TaaMethod : AntiAliasingMethod {
    override val mode = VideoAntiAliasingSettings.TAA
    override val requirements = mapOf(
        RenderStage.FRAME_PREPARE to setOf(RenderInput.PROJECTION_CONTROL),
        RenderStage.WORLD_RESOLVE to setOf(RenderInput.WORLD_COLOR, RenderInput.WORLD_DEPTH, RenderInput.CAMERA_MATRICES)
    )
    val jitter = TemporalJitterController()
    val resolve = TaaResolve()
    val scene = TemporalSceneBridge()
    val composition = TemporalSceneComposition()
    private var sequence = TemporalJitterSequence.HALTON_23
    private var samples = TemporalSettings.DEFAULT_SAMPLES
    private var sharpness = TemporalSettings.DEFAULT_SHARPNESS.toFloat()
    private var effectsEnabled = TemporalSettings.DEFAULT_EFFECTS
    private var effectBudgetMib = TemporalSettings.DEFAULT_EFFECT_BUDGET_MIB
    private var previousCamera: CameraSnapshot? = null
    private var baseProjection: Matrix4f? = null
    private var preparedFrame = -1L
    private var resolvedFrame = -1L
    private var projectionFrame = -1L
    private var lateProjection: ProjectionMatrixBuffer? = null
    private val lateProjectionBytes: Long
        get() = if (lateProjection == null) 0L else RenderSystem.PROJECTION_MATRIX_UBO_SIZE.toLong()
    val worldAllocatedBytes: Long
        get() = resolve.allocatedBytes + MotionCapture.allocatedBytes + lateProjectionBytes
    val effectAllocatedBytes: Long
        get() = scene.allocatedBytes + composition.allocatedBytes
    val allocatedBytes: Long
        get() = worldAllocatedBytes + effectAllocatedBytes
    val sampleCount: Int
        get() = samples
    val sharpening: Float
        get() = sharpness
    var sampleCpuNanos = 0L
        private set

    override fun prepareFrame(context: FrameContext) {
        val nextSequence = TemporalJitterSequence.entries[TemporalSettings.sequence.get()]
        val nextSamples = TemporalSettings.samples.get()
        val nextEffects = TemporalSettings.effects.get()
        val nextBudget = TemporalSettings.effectBudget.get()
        if (sequence != nextSequence || samples != nextSamples) resetHistory("sampling_changed")
        if (effectsEnabled != nextEffects || effectBudgetMib != nextBudget) resetHistory("effects_settings_changed")
        sequence = nextSequence
        samples = nextSamples
        sharpness = TemporalSettings.sharpness.get().toFloat()
        effectsEnabled = nextEffects
        effectBudgetMib = nextBudget
        MotionCapture.beginFrame(context.frameId)
        if (effectsEnabled) {
            scene.beginFrame(context.frameId, effectBudgetMib.toLong() * 1024 * 1024)
            composition.beginFrame(context.frameId, effectBudgetMib.toLong() * 1024 * 1024) {
                scene.allocatedBytes
            }
            if (composition.failure == null) TemporalEffects.beginFrame(context.frameId)
            else {
                TemporalEffects.close()
                scene.close()
            }
        } else {
            TemporalEffects.close()
            scene.close()
            composition.close()
        }
    }

    fun captureOpaque(context: FrameContext, source: RenderTarget) {
        if (!effectsEnabled || projectionFrame != context.frameId || scene.opaqueInput != null) return
        if (composition.failure != null) {
            TemporalEffects.endFrame()
            return
        }
        scene.captureOpaque(context.frameId, source)
        val clean = scene.opaqueInput ?: return
        try {
            val motion = MotionCapture.render(clean)
            val opaqueCaptureComplete = composition.renderOpaqueMotion(clean, motion)
            if (MotionCapture.overflow || !opaqueCaptureComplete) resolve.resetHistory()
            val stable = resolve.prepare(clean, reprojection(context), jitter.current, jitter.previous, samples, sharpness, motion)
            composition.start(source, clean, stable, jitter.current, jitter.previous, samples)
        } catch (exception: Exception) {
            resolve.abort()
            composition.reject("opaque_resolve_failed", exception)
        }
    }

    override fun prepareProjection(context: FrameContext, projection: Matrix4f) {
        if (preparedFrame == context.frameId) return
        val camera = checkNotNull(context.camera)
        val previous = previousCamera
        val changedProjection = baseProjection?.let { !it.equals(projection, 0.00001f) } == true
        val cameraCut = previous != null && (
            previous.position.distanceToSqr(camera.position) > 64.0 ||
                !previous.view(Matrix4f()).equals(camera.view(Matrix4f()), 0.5f)
            )
        when {
            changedProjection -> resetHistory("projection_changed")
            cameraCut -> resetHistory("camera_cut")
            resolvedFrame >= 0 && resolvedFrame != context.frameId - 1 -> resetHistory("frame_gap")
        }
        baseProjection = Matrix4f(projection)
        val started = System.nanoTime()
        jitter.advance(sequence, samples)
        sampleCpuNanos = System.nanoTime() - started
        preparedFrame = context.frameId
    }

    override fun prepareWorldProjection(context: FrameContext, projection: Matrix4f): Matrix4f {
        if (preparedFrame != context.frameId) {
            prepareProjection(context, checkNotNull(context.camera).projection(Matrix4f()))
        }
        projectionFrame = context.frameId
        return Matrix4f().translation(
            (2.0 * jitter.current.x / context.width).toFloat(),
            (2.0 * jitter.current.y / context.height).toFloat(), 0f
        ).mul(projection).also {
            MotionCapture.setProjection(it)
            TemporalEffects.setProjection(it)
        }
    }

    override fun render(context: FrameContext, environment: RenderEnvironment) {
        check(projectionFrame == context.frameId) { "TAA requires the actual jittered world projection" }
        val camera = checkNotNull(context.camera)
        TemporalEffects.failure?.let { composition.reject(it) }
        composition.finishCapture()
        val output = composition.output
        if (effectsEnabled && output != null) {
            resolve.present(environment.target, output)
            resolve.commit()
            composition.commit()
        } else {
            resolve.abort()
            if (effectsEnabled) {
                if (composition.failure == null) composition.reject(scene.failure ?: "composition_input_missing")
                resolve.resetHistory()
                composition.resetHistory()
            }
            val motion = MotionCapture.render(environment.target)
            if (MotionCapture.overflow) resolve.resetHistory()
            if (effectsEnabled) {
                try {
                    val fallback = resolve.prepare(environment.target, reprojection(context), jitter.current, jitter.previous, samples, sharpness, motion)
                    resolve.present(environment.target, fallback)
                } finally { resolve.abort() }
            } else {
                resolve.render(environment.target, reprojection(context), jitter.current, jitter.previous, samples, sharpness, motion)
            }
        }
        if (!effectsEnabled || output != null) {
            MotionCapture.commit()
            TemporalEffects.commit()
            previousCamera = camera
            resolvedFrame = context.frameId
        } else {
            MotionCapture.resetHistory()
            previousCamera = null
            resolvedFrame = -1L
        }
    }

    fun renderAlwaysOnTop(context: FrameContext, draw: () -> Unit) {
        if (projectionFrame != context.frameId) return draw()
        val camera = context.camera ?: return draw()
        val previous = RenderSystem.getProjectionMatrixBuffer() ?: return draw()
        val projectionType = RenderSystem.getProjectionType()
        val projection = Matrix4f().translation(
            (-2.0 * jitter.current.x / context.width).toFloat(),
            (-2.0 * jitter.current.y / context.height).toFloat(), 0f
        ).mul(camera.projection(Matrix4f()))
        val buffer = lateProjection ?: ProjectionMatrixBuffer("aamod unjittered late world projection").also { lateProjection = it }
        RenderSystem.setProjectionMatrix(buffer.getBuffer(projection), projectionType)
        try { draw() } finally { RenderSystem.setProjectionMatrix(previous, projectionType) }
    }

    private fun reprojection(context: FrameContext): Matrix4f {
        val camera = checkNotNull(context.camera)
        val previous = previousCamera ?: camera
        val inverseCurrent = camera.projection(Matrix4f()).mul(camera.view(Matrix4f())).invert()
        val offset = camera.position.subtract(previous.position)
        val reproject = previous.projection(Matrix4f()).mul(previous.view(Matrix4f()))
            .translate(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()).mul(inverseCurrent)
        check(reproject.isFinite) { "Invalid TAA camera matrix" }
        return reproject
    }

    private fun resetHistory(reason: String) {
        jitter.reset(reason)
        resolve.resetHistory()
        MotionCapture.resetHistory()
        TemporalEffects.resetHistory()
        composition.resetHistory()
        composition.clearFailure()
        previousCamera = null
        resolvedFrame = -1L
    }

    override fun invalidate(reason: InvalidationReason) {
        resetHistory(reason.name.lowercase())
        resolve.close()
        MotionCapture.close()
        TemporalEffects.close()
        scene.close()
        composition.close()
        lateProjection?.close()
        lateProjection = null
        baseProjection = null
        preparedFrame = -1L
        projectionFrame = -1L
    }

    override fun close() = invalidate(InvalidationReason.SHUTDOWN)

    override fun endWorldRender() {
        MotionCapture.endFrame()
        TemporalEffects.endFrame()
        scene.endFrame()
        composition.endFrame()
    }
}
