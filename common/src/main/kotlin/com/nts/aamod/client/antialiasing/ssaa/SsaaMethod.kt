package com.nts.aamod.client.antialiasing.ssaa

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.nts.aamod.AAMod
import com.nts.aamod.client.antialiasing.AntiAliasingController
import com.nts.aamod.client.antialiasing.AntiAliasingMethod
import com.nts.aamod.client.antialiasing.ColorEncoding
import com.nts.aamod.client.antialiasing.FrameContext
import com.nts.aamod.client.antialiasing.InvalidationReason
import com.nts.aamod.client.antialiasing.RenderEnvironment
import com.nts.aamod.client.antialiasing.RenderInput
import com.nts.aamod.client.antialiasing.RenderResolution
import com.nts.aamod.client.antialiasing.RenderStage
import com.nts.aamod.client.antialiasing.SpatialPostChain
import com.nts.aamod.client.mixin.render.RenderTargetAccess
import com.nts.aamod.client.option.SpatialSettings
import com.nts.aamod.client.option.VideoAntiAliasingSettings
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.PostChain

class SsaaMethod : AntiAliasingMethod {
    override val mode = VideoAntiAliasingSettings.SSAA
    override val requirements = mapOf(
        RenderStage.FRAME_PREPARE to setOf(RenderInput.RESOLUTION_CONTROL),
        RenderStage.FINAL_COLOR to setOf(RenderInput.FINAL_COLOR)
    )
    private val downsample = SpatialPostChain("ssaa")
    private var worldTarget: RenderTarget? = null
    private var outputTarget: RenderTarget? = null
    private var resolution: RenderResolution? = null

    override fun prepareFrame(context: FrameContext) {
        val maximum = RenderSystem.getDevice().deviceInfo.limits().maxTextureSize()
        val next = AntiAliasingController.planResolution(context, SpatialSettings.scale(), maximum)
        if (resolution == next && worldTarget != null) return
        close()
        val target = object : RenderTarget("aamod SSAA world", true, GpuFormat.RGBA8_UNORM) {}
        try {
            target.createBuffers(next.worldWidth, next.worldHeight)
        } catch (exception: Exception) {
            target.destroyBuffers()
            throw exception
        }
        worldTarget = target
        resolution = next
        Minecraft.getInstance().levelRenderer.resize(next.worldWidth, next.worldHeight)
    }

    override fun beginWorldRender(context: FrameContext) {
        prepareFrame(context)
        val world = checkNotNull(worldTarget)
        val size = checkNotNull(resolution)
        val renderer = Minecraft.getInstance().gameRenderer
        val output = renderer.mainRenderTarget()
        check(output.width == size.outputWidth && output.height == size.outputHeight && output.colorTexture?.format == GpuFormat.RGBA8_UNORM)
        outputTarget = output
        swapAttachments(output, world)
        renderer.gameRenderState().windowRenderState.apply {
            width = size.worldWidth
            height = size.worldHeight
        }
    }

    override fun endWorldRender() {
        val output = outputTarget ?: return
        val renderer = Minecraft.getInstance().gameRenderer
        swapAttachments(output, checkNotNull(worldTarget))
        renderer.gameRenderState().windowRenderState.apply {
            width = output.width
            height = output.height
        }
        outputTarget = null
    }

    override fun render(context: FrameContext, environment: RenderEnvironment) {
        val output = checkNotNull(worldTarget)
        check(environment.target === outputTarget && environment.colorEncoding == ColorEncoding.DISPLAY)
        downsample.execute(output.width, output.height, mapOf(
            AAMod.id("ssaa_source") to environment.target,
            PostChain.MAIN_TARGET_ID to output
        ))
    }

    override fun invalidate(reason: InvalidationReason) = close()

    override fun close() {
        endWorldRender()
        downsample.close()
        if (worldTarget != null) {
            val output = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            Minecraft.getInstance().levelRenderer.resize(output.width, output.height)
        }
        worldTarget?.destroyBuffers()
        worldTarget = null
        resolution = null
    }

    private fun swapAttachments(first: RenderTarget, second: RenderTarget) {
        val firstColor = first.colorTexture
        val firstColorView = first.colorTextureView
        val firstDepth = first.depthTexture
        val firstDepthView = first.depthTextureView
        val firstWidth = first.width
        val firstHeight = first.height
        (first as RenderTargetAccess).apply {
            `aamod$setColorTexture`(second.colorTexture)
            `aamod$setColorTextureView`(second.colorTextureView)
            `aamod$setDepthTexture`(second.depthTexture)
            `aamod$setDepthTextureView`(second.depthTextureView)
        }
        (second as RenderTargetAccess).apply {
            `aamod$setColorTexture`(firstColor)
            `aamod$setColorTextureView`(firstColorView)
            `aamod$setDepthTexture`(firstDepth)
            `aamod$setDepthTextureView`(firstDepthView)
        }
        first.width = second.width
        first.height = second.height
        second.width = firstWidth
        second.height = firstHeight
    }
}
