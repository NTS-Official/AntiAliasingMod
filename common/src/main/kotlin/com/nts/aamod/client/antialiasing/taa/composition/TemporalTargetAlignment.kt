package com.nts.aamod.client.antialiasing.taa.composition

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.nts.aamod.AAMod
import com.nts.aamod.client.antialiasing.taa.JitterPoint
import com.nts.aamod.client.antialiasing.taa.effects.TemporalUploadArena
import org.lwjgl.system.MemoryStack
import java.util.Optional
import java.util.OptionalDouble

class TemporalTargetAlignment : AutoCloseable {
    private val uniforms = TemporalUploadArena("aamod temporal alignment", GpuBuffer.USAGE_UNIFORM, 4096)
    val allocatedBytes: Long get() = uniforms.allocatedBytes

    fun beginFrame() = uniforms.beginFrame()
    fun endFrame() = uniforms.endFrame()

    fun align(raw: RenderTarget, stable: RenderTarget?, destination: RenderTarget, current: JitterPoint) {
        check(raw.width == destination.width && raw.height == destination.height &&
            destination.colorTexture?.format == GpuFormat.RGBA8_UNORM && destination.depthTexture?.format == GpuFormat.D32_FLOAT)
        check(raw.colorTexture !== destination.colorTexture && raw.depthTexture !== destination.depthTexture &&
            stable?.colorTexture !== destination.colorTexture) { "Temporal target alignment cannot read its own output" }
        if (stable != null) check(stable.width == raw.width && stable.height == raw.height)
        val device = RenderSystem.getDevice()
        check(device.precompilePipeline(ALIGN).isValid) { "Could not compile current sorting depth alignment" }
        TemporalCommandCapture.suppress {
            val encoder = device.createCommandEncoder()
            val config = MemoryStack.stackPush().use { stack ->
                val data = stack.malloc(32)
                data.putFloat((current.x / raw.width).toFloat()).putFloat((current.y / raw.height).toFloat())
                    .putFloat(1f / raw.width).putFloat(1f / raw.height)
                data.putFloat(if (stable == null) 1f else 0f).putFloat(0f).putFloat(0f).putFloat(0f).flip()
                uniforms.upload(data, device.deviceInfo.limits().minUniformOffsetAlignment())
            }
            val nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            encoder.createRenderPass({ "aamod align current sorting depth" }, checkNotNull(destination.colorTextureView), Optional.empty(),
                checkNotNull(destination.depthTextureView), OptionalDouble.empty()).use { pass ->
                pass.setPipeline(ALIGN)
                pass.setUniform("AlignmentConfig", config)
                pass.bindTexture("RawColor", checkNotNull(raw.colorTextureView), nearest)
                pass.bindTexture("RawDepth", checkNotNull(raw.depthTextureView), nearest)
                pass.bindTexture("StableColor", checkNotNull((stable ?: raw).colorTextureView), nearest)
                pass.draw(3, 1, 0, 0)
            }
        }
    }

    override fun close() = uniforms.close()

    companion object {
        private val ALIGN = RenderPipeline.builder().withLocation(AAMod.id("taa/align_target"))
            .withVertexShader(AAMod.id("post/taa_fullscreen")).withFragmentShader(AAMod.id("post/taa_align_target"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
            .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, true))
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("RawColor").withSampler("RawDepth").withSampler("StableColor")
                .withUniform("AlignmentConfig", UniformType.UNIFORM_BUFFER).build()).build()
    }
}
