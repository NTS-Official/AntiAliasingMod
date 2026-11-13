package com.nts.aamod.client.antialiasing.taa.composition

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.nts.aamod.AAMod

class TemporalSceneBridge : AutoCloseable {
    private var opaque: RenderTarget? = null
    private var frame = -1L
    private var capturedFrame = -1L
    private var budgetBytes = 0L
    val opaqueInput: RenderTarget? get() = opaque.takeIf { capturedFrame == frame && frame >= 0 }
    val allocatedBytes: Long get() = opaque?.let { it.width.toLong() * it.height * 8 } ?: 0L
    var failure: String? = null
        private set

    fun beginFrame(frameId: Long, budgetBytes: Long) {
        require(frameId >= 0 && budgetBytes >= 0)
        endFrame()
        frame = frameId
        this.budgetBytes = budgetBytes
        failure = null
    }

    fun captureOpaque(frameId: Long, source: RenderTarget) {
        if (frame != frameId || capturedFrame == frameId || failure != null) return
        if (source.colorTexture?.format != GpuFormat.RGBA8_UNORM || source.depthTexture?.format != GpuFormat.D32_FLOAT) {
            failure = "opaque_input_format_unsupported"
            return
        }
        val bytes = source.width.toLong() * source.height * 8
        if (bytes > budgetBytes) { releaseOpaque(); failure = "history_budget"; return }
        try {
            TemporalCommandCapture.suppress {
                if (opaque?.let { it.width == source.width && it.height == source.height } != true) {
                    releaseOpaque()
                    val created = object : RenderTarget("aamod clean opaque world", true, GpuFormat.RGBA8_UNORM) {}
                    opaque = created
                    created.createBuffers(source.width, source.height)
                }
                val snapshot = checkNotNull(opaque)
                val encoder = RenderSystem.getDevice().createCommandEncoder()
                encoder.copyTextureToTexture(checkNotNull(source.colorTexture), checkNotNull(snapshot.colorTexture),
                    0, 0, 0, 0, 0, source.width, source.height)
                snapshot.copyDepthFrom(source)
            }
            capturedFrame = frameId
        } catch (exception: Exception) {
            failure = "opaque_capture_failed"
            releaseOpaque()
            AAMod.LOGGER.error("Could not preserve clean opaque input for temporal effects", exception)
        }
    }

    fun endFrame() {
        frame = -1L
        capturedFrame = -1L
    }

    private fun releaseOpaque() { opaque?.destroyBuffers(); opaque = null }

    override fun close() {
        endFrame()
        releaseOpaque()
        budgetBytes = 0L
        failure = null
    }
}
