package com.nts.aamod.client.antialiasing.smaa

import com.mojang.blaze3d.GpuFormat
import com.nts.aamod.client.antialiasing.AntiAliasingMethod
import com.nts.aamod.client.antialiasing.ColorEncoding
import com.nts.aamod.client.antialiasing.FrameContext
import com.nts.aamod.client.antialiasing.InvalidationReason
import com.nts.aamod.client.antialiasing.RenderEnvironment
import com.nts.aamod.client.antialiasing.RenderInput
import com.nts.aamod.client.antialiasing.RenderStage
import com.nts.aamod.client.antialiasing.SpatialPostChain
import com.nts.aamod.client.option.SpatialSettings
import com.nts.aamod.client.option.VideoAntiAliasingSettings
import net.minecraft.client.renderer.PostChain

class SmaaMethod : AntiAliasingMethod {
    override val mode = VideoAntiAliasingSettings.SMAA
    override val requirements = mapOf(RenderStage.FINAL_COLOR to setOf(RenderInput.FINAL_COLOR))
    private var quality = -1
    private var chain: SpatialPostChain? = null

    override fun render(context: FrameContext, environment: RenderEnvironment) {
        check(environment.format == GpuFormat.RGBA8_UNORM && environment.colorEncoding == ColorEncoding.DISPLAY) {
            "SMAA requires RGBA8 display color"
        }
        val selectedQuality = SpatialSettings.smaaQuality.get()
        if (quality != selectedQuality) close()
        val active = chain ?: SpatialPostChain("smaa/${listOf("low", "medium", "high", "ultra")[selectedQuality]}").also {
            chain = it
            quality = selectedQuality
        }
        active.execute(environment.width, environment.height, mapOf(PostChain.MAIN_TARGET_ID to environment.target))
    }

    override fun invalidate(reason: InvalidationReason) = close()

    override fun close() {
        chain?.close()
        chain = null
        quality = -1
    }
}
