package com.nts.aamod.client.antialiasing

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.framegraph.FrameGraphBuilder
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.nts.aamod.AAMod
import com.nts.aamod.client.antialiasing.fxaa.FxaaMethod
import com.nts.aamod.client.antialiasing.ssaa.SsaaMethod
import com.nts.aamod.client.antialiasing.smaa.SmaaMethod
import com.nts.aamod.client.antialiasing.taa.TaaMethod
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import com.nts.aamod.client.option.VideoAntiAliasingSettings
import com.nts.aamod.client.compat.iris.IrisCompatibility
import net.minecraft.client.Minecraft
import net.minecraft.client.CameraType
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.entity.Entity
import org.joml.Matrix4f
import java.util.EnumSet
import java.util.Locale

object AntiAliasingController {
    private val methods: Map<Int, AntiAliasingMethod> = mapOf(
        VideoAntiAliasingSettings.FXAA to FxaaMethod(),
        VideoAntiAliasingSettings.SSAA to SsaaMethod(),
        VideoAntiAliasingSettings.SMAA to SmaaMethod(),
        VideoAntiAliasingSettings.TAA to TaaMethod()
    )
    private var provider: RenderEnvironmentProvider? = null
    private var frameProvider: RenderEnvironmentProvider = VanillaRenderEnvironment
    private var providerIdentity: Any = VanillaRenderEnvironment.identity
    private var frameSupport = RenderSupport(true)
    private var frame: FrameContext? = null
    private var frameId = 0L
    private var previousLevel: ClientLevel? = null
    private var previousCamera: Entity? = null
    private var previousCameraType = CameraType.FIRST_PERSON
    private var previousMode = VideoAntiAliasingSettings.OFF
    private var previousWidth = 0
    private var previousHeight = 0
    private val previousTargets = mutableMapOf<RenderStage, TargetState>()
    private var renderedWorld = false
    private var framePrepared = false
    private var failedMode: Int? = null
    private val completedStages = EnumSet.noneOf(RenderStage::class.java)

    @Volatile
    private var status = AntiAliasingStatus(VideoAntiAliasingSettings.OFF, RunState.OFF)

    @JvmStatic
    fun getStatus(): AntiAliasingStatus = status

    fun temporalDiagnostics(): List<String> {
        val method = methods.getValue(VideoAntiAliasingSettings.TAA) as TaaMethod
        val jitter = method.jitter
        val state = status
        val selected = frame?.requestedMode == VideoAntiAliasingSettings.TAA
        return listOf(
            "TAA ${if (selected) state.state.name else "INACTIVE"} | epoch=${jitter.epoch}" +
                " | submitted=${method.resolve.historyFrames}/${method.sampleCount} | phaseFrame=${jitter.frameIndex}" +
                " | sharpen=${String.format(Locale.ROOT, "%.2f", method.sharpening)}",
            "TAA last reset: ${jitter.resetReason}" + (state.reason?.let { " | status reason: $it" } ?: ""),
            "TAA objects: vertices=${MotionCapture.vertices} | matchedBatches=${MotionCapture.validBatches}" +
                " | rejectBatches=${MotionCapture.rejectedBatches} | budget=${if (MotionCapture.overflow) "EXCEEDED" else "OK"}"
        )
    }

    fun setEnvironmentProvider(replacement: RenderEnvironmentProvider) {
        RenderSystem.assertOnRenderThread()
        provider = replacement
    }

    fun planResolution(context: FrameContext, scale: Double, maximum: Int): RenderResolution =
        frameProvider.planResolution(context, scale, maximum)

    @JvmStatic
    fun beginWorldRender() {
        val method = currentMethod() ?: return
        if (RenderStage.FRAME_PREPARE in method.requirements && !framePrepared) return
        guarded(method) { method.beginWorldRender(checkNotNull(frame)) }
    }

    @JvmStatic
    fun endWorldRender() {
        methods.values.forEach { it.endWorldRender() }
    }

    @JvmStatic
    fun prepareFrame(advanceGameTime: Boolean) {
        val minecraft = Minecraft.getInstance()
        val mode = VideoAntiAliasingSettings.getCurrentAAMode()
        val cameraType = minecraft.options.cameraType
        if (minecraft.level !== previousLevel) invalidate(InvalidationReason.WORLD_CHANGED)
        if (minecraft.cameraEntity !== previousCamera || cameraType != previousCameraType) {
            invalidate(InvalidationReason.CAMERA_CHANGED)
        }
        if (mode != previousMode) invalidate(InvalidationReason.MODE_CHANGED)
        previousLevel = minecraft.level
        previousCamera = minecraft.cameraEntity
        previousCameraType = cameraType
        previousMode = mode
        val compatibleProvider = IrisCompatibility.beginFrame()
        val nextProvider = provider ?: compatibleProvider
        val identity = nextProvider.identity
        if (providerIdentity != identity) invalidate(InvalidationReason.PIPELINE_CHANGED)
        providerIdentity = identity
        frameProvider = nextProvider
        renderedWorld = false
        framePrepared = false
        completedStages.clear()
        val context = FrameContext(
            ++frameId,
            mode,
            minecraft.window.width,
            minecraft.window.height,
            minecraft.isGameLoadFinished && advanceGameTime && minecraft.level != null,
            minecraft.level?.dimension()?.identifier(),
            cameraType
        )
        if (context.width != previousWidth || context.height != previousHeight) {
            invalidate(InvalidationReason.SIZE_CHANGED)
        }
        previousWidth = context.width
        previousHeight = context.height
        frame = context
        status = when {
            mode == VideoAntiAliasingSettings.OFF -> AntiAliasingStatus(mode, RunState.OFF)
            !methods.containsKey(mode) -> AntiAliasingStatus(mode, RunState.NOT_IMPLEMENTED)
            failedMode == mode -> AntiAliasingStatus(mode, RunState.FAILED, status.reason)
            !context.hasWorld -> AntiAliasingStatus(mode, RunState.NO_WORLD)
            else -> AntiAliasingStatus(mode, RunState.READY)
        }
        frameSupport = RenderSupport(true)
        methods[mode]?.let { method -> guarded(method) { frameSupport = frameProvider.support(mode) } }
        val method = currentMethod() ?: return
        if (RenderStage.FRAME_PREPARE in method.requirements) {
            guarded(method) {
                if (canPrepare(method, context)) {
                    method.prepareFrame(context)
                    framePrepared = true
                }
            }
        }
    }

    @JvmStatic
    fun cameraExtracted(cameraState: CameraRenderState) {
        var context = frame ?: return
        val camera = if (cameraState.initialized) {
            CameraSnapshot.capture(cameraState.projectionMatrix, cameraState.viewRotationMatrix, cameraState.pos)
        } else null
        context = context.copy(camera = camera)
        val method = currentMethod()
        if (method != null && camera != null && RenderStage.FRAME_PREPARE in method.requirements) {
            guarded(method) {
                if (canPrepare(method, context)) method.prepareProjection(context, cameraState.projectionMatrix)
            }
        }
        frame = context
        completedStages.add(RenderStage.FRAME_PREPARE)
    }

    @JvmStatic
    fun cullingProjection(projection: Matrix4f): Matrix4f {
        val context = frame ?: return projection
        if (!framePrepared || currentMethod()?.mode != VideoAntiAliasingSettings.TAA || context.width <= 0 || context.height <= 0) {
            return projection
        }
        return Matrix4f().scaling(
            context.width / (context.width + 2f), context.height / (context.height + 2f), 1f
        ).mul(projection)
    }

    @JvmStatic
    fun worldProjection(projection: Matrix4f): Matrix4f {
        val context = frame ?: return projection
        val camera = context.camera ?: return projection
        if (!CameraSnapshot.isUsable(projection)) {
            frame = context.copy(camera = null)
            if (RenderInput.CAMERA_MATRICES in currentMethod()?.requirements?.get(RenderStage.WORLD_RESOLVE).orEmpty()) {
                status = AntiAliasingStatus(context.requestedMode, RunState.MISSING_INPUT, "WORLD_PROJECTION")
            }
            return projection
        }
        var actual = projection
        val method = currentMethod()
        if (method != null && framePrepared) guarded(method) {
            actual = method.prepareWorldProjection(context, projection)
        }
        frame = context.copy(camera = CameraSnapshot(actual, camera.view(Matrix4f()), camera.position))
        return actual
    }

    @JvmStatic
    fun addWorldResolve(frameGraph: FrameGraphBuilder, targets: LevelTargetBundle) {
        val method = currentMethod() ?: return
        if (RenderStage.WORLD_RESOLVE !in method.requirements) return
        val pass = frameGraph.addPass("aamod_world_resolve")
        val target = pass.readsAndWrites(targets.main)
        targets.main = target
        pass.executes { dispatch(RenderStage.WORLD_RESOLVE, target.get()) }
    }

    @JvmStatic
    fun worldRendered() {
        renderedWorld = frame?.hasWorld == true
    }

    @JvmStatic
    fun finalColor(target: RenderTarget) {
        try {
            if (renderedWorld) dispatch(RenderStage.FINAL_COLOR, target)
        } finally {
            endWorldRender()
        }
    }

    @JvmStatic
    fun invalidate(reason: InvalidationReason) {
        RenderSystem.assertOnRenderThread()
        methods.values.forEach { it.invalidate(reason) }
        previousTargets.clear()
        if (reason == InvalidationReason.RESOURCES_RELOADED || reason == InvalidationReason.MODE_CHANGED || reason == InvalidationReason.PIPELINE_CHANGED) {
            failedMode = null
        }
    }

    @JvmStatic
    fun close() {
        invalidate(InvalidationReason.SHUTDOWN)
        frame = null
        previousLevel = null
        previousCamera = null
        renderedWorld = false
    }

    private fun currentMethod(): AntiAliasingMethod? {
        val context = frame ?: return null
        if (!context.hasWorld || failedMode == context.requestedMode) return null
        val method = methods[context.requestedMode] ?: return null
        val support = frameSupport
        if (!support.supported) {
            status = AntiAliasingStatus(method.mode, RunState.COMPATIBILITY_PAUSED, support.reason)
            return null
        }
        return method
    }

    private fun canPrepare(method: AntiAliasingMethod, context: FrameContext): Boolean {
        val requirements = method.requirements[RenderStage.FRAME_PREPARE] ?: return false
        if (frameProvider.preparationInputs(context).containsAll(requirements)) return true
        status = AntiAliasingStatus(method.mode, RunState.MISSING_INPUT, RenderStage.FRAME_PREPARE.name)
        return false
    }

    private fun dispatch(stage: RenderStage, target: RenderTarget) {
        val context = frame ?: return
        val method = currentMethod() ?: return
        if (RenderStage.FRAME_PREPARE in method.requirements && !framePrepared) return
        val requirements = method.requirements[stage] ?: return
        if (!completedStages.add(stage)) return
        guarded(method) {
            val environment = frameProvider.capture(stage, target, context)
            if (environment.unavailableReason != null) {
                status = AntiAliasingStatus(method.mode, RunState.COMPATIBILITY_PAUSED, environment.unavailableReason)
                return@guarded
            }
            if (!environment.inputs.containsAll(requirements) || environment.width <= 0 || environment.height <= 0) {
                status = AntiAliasingStatus(method.mode, RunState.MISSING_INPUT, stage.name)
                return@guarded
            }
            val targetState = TargetState(environment.width, environment.height, environment.format)
            val previousTarget = previousTargets.put(stage, targetState)
            if (method.mode != VideoAntiAliasingSettings.SSAA && previousTarget != null && (environment.width != previousTarget.width || environment.height != previousTarget.height)) {
                invalidate(InvalidationReason.SIZE_CHANGED)
            }
            if (previousTarget != null && environment.format != previousTarget.format) invalidate(InvalidationReason.FORMAT_CHANGED)
            method.render(context, environment)
            status = AntiAliasingStatus(method.mode, RunState.ACTIVE)
        }
    }

    private inline fun guarded(method: AntiAliasingMethod, action: () -> Unit) {
        try {
            action()
        } catch (exception: Exception) {
            failedMode = method.mode
            status = AntiAliasingStatus(method.mode, RunState.FAILED, exception.message)
            runCatching { method.close() }.onFailure { exception.addSuppressed(it) }
            AAMod.LOGGER.error("Anti-aliasing mode {} failed; disabled until resource reload or mode change", method.mode, exception)
        }
    }

    private data class TargetState(val width: Int, val height: Int, val format: GpuFormat?)
}
