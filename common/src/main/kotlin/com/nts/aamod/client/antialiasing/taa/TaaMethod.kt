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
import org.joml.Matrix4f

class TaaMethod : AntiAliasingMethod {
    override val mode = VideoAntiAliasingSettings.TAA
    override val requirements = mapOf(
        RenderStage.FRAME_PREPARE to setOf(RenderInput.PROJECTION_CONTROL),
        RenderStage.WORLD_RESOLVE to setOf(RenderInput.WORLD_COLOR, RenderInput.WORLD_DEPTH, RenderInput.CAMERA_MATRICES)
    )
    val jitter = TemporalJitterController()
    val resolve = TaaResolve()
    private var sequence = TemporalJitterSequence.HALTON_23
    private var samples = TemporalSettings.DEFAULT_SAMPLES
    private var sharpness = TemporalSettings.DEFAULT_SHARPNESS.toFloat()
    private var previousCamera: CameraSnapshot? = null
    private var baseProjection: Matrix4f? = null
    private var preparedFrame = -1L
    private var resolvedFrame = -1L
    private var projectionFrame = -1L
    val sampleCount: Int
        get() = samples
    val sharpening: Float
        get() = sharpness
    var sampleCpuNanos = 0L
        private set

    override fun prepareFrame(context: FrameContext) {
        val nextSequence = TemporalJitterSequence.entries[TemporalSettings.sequence.get()]
        val nextSamples = TemporalSettings.samples.get()
        if (sequence != nextSequence || samples != nextSamples) resetHistory("sampling_changed")
        sequence = nextSequence
        samples = nextSamples
        sharpness = TemporalSettings.sharpness.get().toFloat()
        MotionCapture.beginFrame(context.frameId)
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
        ).mul(projection).also { MotionCapture.setProjection(it) }
    }

    override fun render(context: FrameContext, environment: RenderEnvironment) {
        check(projectionFrame == context.frameId) { "TAA requires the actual jittered world projection" }
        val camera = checkNotNull(context.camera)
        val previous = previousCamera ?: camera
        val inverseCurrent = camera.projection(Matrix4f()).mul(camera.view(Matrix4f())).invert()
        val offset = camera.position.subtract(previous.position)
        val reproject = previous.projection(Matrix4f()).mul(previous.view(Matrix4f()))
            .translate(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()).mul(inverseCurrent)
        check(reproject.isFinite) { "Invalid TAA camera matrix" }
        val motion = MotionCapture.render(environment.target)
        if (MotionCapture.overflow) resolve.resetHistory()
        resolve.render(environment.target, reproject, jitter.current, jitter.previous, samples, sharpness, motion)
        MotionCapture.commit()
        previousCamera = camera
        resolvedFrame = context.frameId
    }

    private fun resetHistory(reason: String) {
        jitter.reset(reason)
        resolve.resetHistory()
        MotionCapture.resetHistory()
        previousCamera = null
        resolvedFrame = -1L
    }

    override fun invalidate(reason: InvalidationReason) {
        resetHistory(reason.name.lowercase())
        resolve.close()
        MotionCapture.close()
        baseProjection = null
        preparedFrame = -1L
        projectionFrame = -1L
    }

    override fun close() = invalidate(InvalidationReason.SHUTDOWN)

    override fun endWorldRender() = MotionCapture.endFrame()
}
