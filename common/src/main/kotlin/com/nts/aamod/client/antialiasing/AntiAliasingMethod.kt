package com.nts.aamod.client.antialiasing

import org.joml.Matrix4f

enum class RenderStage {
    FRAME_PREPARE,
    WORLD_RESOLVE,
    FINAL_COLOR
}

enum class RenderInput {
    WORLD_COLOR,
    WORLD_DEPTH,
    FINAL_COLOR,
    CAMERA_MATRICES,
    PROJECTION_CONTROL,
    RESOLUTION_CONTROL,
    HISTORY,
    MOTION_VECTORS
}

enum class InvalidationReason {
    MODE_CHANGED,
    SIZE_CHANGED,
    FORMAT_CHANGED,
    RESOURCES_RELOADED,
    WORLD_CHANGED,
    CAMERA_CHANGED,
    PIPELINE_CHANGED,
    SHUTDOWN
}

enum class RunState {
    OFF,
    READY,
    ACTIVE,
    NO_WORLD,
    NOT_IMPLEMENTED,
    MISSING_INPUT,
    COMPATIBILITY_PAUSED,
    FAILED
}

data class AntiAliasingStatus(
    val requestedMode: Int,
    val state: RunState,
    val reason: String? = null
)

interface AntiAliasingMethod : AutoCloseable {
    val mode: Int
    val requirements: Map<RenderStage, Set<RenderInput>>

    fun prepareFrame(context: FrameContext) {}

    fun prepareProjection(context: FrameContext, projection: Matrix4f) {}

    fun prepareWorldProjection(context: FrameContext, projection: Matrix4f): Matrix4f = projection

    fun beginWorldRender(context: FrameContext) {}

    fun endWorldRender() {}

    fun render(context: FrameContext, environment: RenderEnvironment)

    fun invalidate(reason: InvalidationReason)
}
