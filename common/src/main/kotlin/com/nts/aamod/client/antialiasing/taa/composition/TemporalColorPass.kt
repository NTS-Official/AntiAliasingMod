package com.nts.aamod.client.antialiasing.taa.composition

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.nts.aamod.AAMod
import com.nts.aamod.client.antialiasing.taa.JitterPoint
import com.nts.aamod.client.antialiasing.taa.effects.TemporalUploadArena
import org.lwjgl.system.MemoryStack
import java.util.Optional

class TemporalColorPass : AutoCloseable {
    private val uniforms = TemporalUploadArena("aamod temporal color", GpuBuffer.USAGE_UNIFORM, 4096)
    val allocatedBytes: Long get() = uniforms.allocatedBytes

    fun beginFrame() = uniforms.beginFrame()
    fun endFrame() = uniforms.endFrame()

    fun draw(source: RenderTarget, destination: RenderTarget, blend: ContributionBlend? = null, jitter: JitterPoint = JitterPoint(0.0, 0.0)) {
        check(source.colorTexture !== destination.colorTexture) { "Temporal color pass cannot read its own output" }
        val pipeline = when (blend) {
            null -> COPY
            ContributionBlend.ALPHA_OVER -> OVER
            ContributionBlend.ADDITIVE -> ADD
            else -> error("Unsupported temporal color blend: $blend")
        }
        val device = RenderSystem.getDevice()
        check(device.precompilePipeline(pipeline).isValid)
        TemporalCommandCapture.suppress {
            val config = config(source, jitter)
            drawPrepared(source, destination, pipeline, config)
        }
    }

    fun prepareNativeRecovery(source: RenderTarget, destination: RenderTarget, jitter: JitterPoint): () -> Unit {
        check(RenderSystem.getDevice().precompilePipeline(COPY).isValid)
        val config = config(source, jitter)
        return { TemporalCommandCapture.suppress { drawPrepared(source, destination, COPY, config) } }
    }

    private fun drawPrepared(source: RenderTarget, destination: RenderTarget, pipeline: RenderPipeline, config: GpuBufferSlice) {
        RenderSystem.getDevice().createCommandEncoder().createRenderPass({ "aamod temporal color" },
            checkNotNull(destination.colorTextureView), Optional.empty()).use { pass ->
            pass.setPipeline(pipeline)
            pass.setUniform("ColorConfig", config)
            pass.bindTexture("InSampler", checkNotNull(source.colorTextureView), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
            pass.draw(3, 1, 0, 0)
        }
    }

    private fun config(source: RenderTarget, jitter: JitterPoint): GpuBufferSlice = MemoryStack.stackPush().use { stack ->
        val data = stack.malloc(16)
        data.putFloat((jitter.x / source.width).toFloat()).putFloat((jitter.y / source.height).toFloat())
            .putFloat(0.5f / source.width).putFloat(0.5f / source.height).flip()
        uniforms.upload(data, RenderSystem.getDevice().deviceInfo.limits().minUniformOffsetAlignment())
    }

    override fun close() = uniforms.close()

    companion object {
        private fun pipeline(name: String, blend: BlendFunction?): RenderPipeline {
            val bindings = BindGroupLayout.builder().withSampler("InSampler").withUniform("ColorConfig", UniformType.UNIFORM_BUFFER)
            val builder = RenderPipeline.builder()
            .withLocation(AAMod.id("taa/color_$name"))
            .withVertexShader(AAMod.id("post/taa_fullscreen")).withFragmentShader(AAMod.id("post/taa_color"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
            .withColorTargetState(if (blend == null) ColorTargetState.DEFAULT else ColorTargetState(blend))
            .withBindGroupLayout(bindings.build())
            return builder.build()
        }
        private val COPY = pipeline("copy", null)
        private val OVER = pipeline("over", BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
        private val ADD = pipeline("add", BlendFunction.ADDITIVE)
    }
}
