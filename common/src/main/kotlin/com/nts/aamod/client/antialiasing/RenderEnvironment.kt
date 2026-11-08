package com.nts.aamod.client.antialiasing

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget

enum class ColorEncoding {
    DISPLAY,
    WORLD,
    UNKNOWN
}

data class RenderSupport(val supported: Boolean, val reason: String? = null)

data class RenderEnvironment(
    val stage: RenderStage,
    val target: RenderTarget,
    val colorEncoding: ColorEncoding,
    val inputs: Set<RenderInput>
) {
    val width: Int = target.width
    val height: Int = target.height
    val format: GpuFormat? = target.colorTexture?.format
}

interface RenderEnvironmentProvider {
    val identity: Any

    fun support(mode: Int): RenderSupport

    fun preparationInputs(context: FrameContext): Set<RenderInput>

    fun capture(stage: RenderStage, target: RenderTarget, context: FrameContext): RenderEnvironment
}

object VanillaRenderEnvironment : RenderEnvironmentProvider {
    override val identity: Any = this

    override fun support(mode: Int) = RenderSupport(true)

    override fun preparationInputs(context: FrameContext): Set<RenderInput> = buildSet {
        add(RenderInput.PROJECTION_CONTROL)
        if (context.camera != null) add(RenderInput.CAMERA_MATRICES)
    }

    override fun capture(stage: RenderStage, target: RenderTarget, context: FrameContext): RenderEnvironment {
        val inputs = mutableSetOf<RenderInput>()
        if (context.camera != null) inputs.add(RenderInput.CAMERA_MATRICES)
        if (target.colorTextureView != null) {
            when (stage) {
                RenderStage.WORLD_RESOLVE -> inputs.add(RenderInput.WORLD_COLOR)
                RenderStage.FINAL_COLOR -> inputs.add(RenderInput.FINAL_COLOR)
                RenderStage.FRAME_PREPARE -> inputs.add(RenderInput.PROJECTION_CONTROL)
            }
        }
        if (stage == RenderStage.WORLD_RESOLVE && target.depthTextureView != null) {
            inputs.add(RenderInput.WORLD_DEPTH)
        }
        return RenderEnvironment(
            stage,
            target,
            if (stage == RenderStage.FINAL_COLOR) ColorEncoding.DISPLAY else ColorEncoding.WORLD,
            inputs.toSet()
        )
    }
}
