package com.nts.aamod.client.antialiasing.taa.motion

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.vertex.VertexFormat
import com.nts.aamod.AAMod
import net.minecraft.client.renderer.MappableRingBuffer
import org.joml.Vector4f
import org.lwjgl.system.MemoryUtil
import java.util.Optional

internal class MotionVectorRenderer : AutoCloseable {
    private var target: RenderTarget? = null
    private var vertices: MappableRingBuffer? = null
    private var uniforms: MappableRingBuffer? = null
    val allocatedBytes: Long
        get() = target?.let { it.width.toLong() * it.height * 16 } ?: 0L

    fun render(world: RenderTarget, draws: List<MotionDraw>): RenderTarget {
        allocate(world.width, world.height)
        val output = checkNotNull(target)
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.clearColorTexture(checkNotNull(output.colorTexture), Vector4f(0f))
        if (draws.isEmpty() || MotionCapture.overflow) return output
        val stride = RenderSystem.getDevice().deviceInfo.limits().minUniformOffsetAlignment().let { ((96 + it - 1) / it) * it }
        val expandedCounts = draws.map { if (it.batch.material.type.primitiveTopology() == PrimitiveTopology.QUADS) it.batch.count / 4 * 6 else it.batch.count }
        val vertexBytes = expandedCounts.sum() * 48
        val uniformBytes = draws.size * stride
        ensureBuffers(vertexBytes, uniformBytes)
        val vertexData = MemoryUtil.memAlloc(vertexBytes)
        val uniformData = MemoryUtil.memCalloc(uniformBytes)
        try {
            draws.forEachIndexed { drawIndex, draw ->
                val batch = draw.batch
                val indices = if (batch.material.type.primitiveTopology() == PrimitiveTopology.QUADS) intArrayOf(0, 1, 2, 2, 3, 0) else intArrayOf(0, 1, 2)
                val primitive = if (indices.size == 6) 4 else 3
                repeat(batch.count / primitive) { face ->
                    for (corner in indices) {
                        val index = face * primitive + corner
                        repeat(4) { component -> vertexData.putFloat(draw.current[index * 4 + component]) }
                        repeat(4) { component -> vertexData.putFloat(if (draw.valid) checkNotNull(draw.previous)[index * 4 + component] else 0f) }
                        vertexData.putFloat(batch.value(index, 3)).putFloat(batch.value(index, 4))
                        vertexData.putFloat(batch.value(index, 5)).putFloat(if (draw.valid) 1f else -1f)
                    }
                }
                uniformData.position(drawIndex * stride)
                uniformData.putFloat(1f / world.width).putFloat(1f / world.height)
                    .putFloat(world.width.toFloat()).putFloat(world.height.toFloat())
                uniformData.putFloat(batch.material.cutoff).putFloat(if (batch.material.conservative) 1f else 0f)
                    .putFloat(if (RenderSystem.getDevice().deviceInfo.isZZeroToOne) 1f else 0f)
                    .putFloat(if (batch.material.prepared.textures().any { it.name() == "Sampler0" }) 1f else 0f)
                batch.material.textureMatrix.get(drawIndex * stride + 32, uniformData)
            }
            vertexData.flip()
            uniformData.position(0).limit(uniformBytes)
            val vertexRing = checkNotNull(vertices)
            val uniformRing = checkNotNull(uniforms)
            encoder.writeToBuffer(vertexRing.currentBuffer().slice(0, vertexBytes.toLong()), vertexData)
            encoder.writeToBuffer(uniformRing.currentBuffer().slice(0, uniformBytes.toLong()), uniformData)
            val nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            encoder.createRenderPass({ "aamod object motion vectors" }, checkNotNull(output.colorTextureView), Optional.empty()).use { pass ->
                pass.setVertexBuffer(0, vertexRing.currentBuffer().slice())
                var firstVertex = 0
                draws.forEachIndexed { index, draw ->
                    val material = draw.batch.material
                    pass.setPipeline(if (material.type.pipeline().isCull) CULLED else TWO_SIDED)
                    pass.setUniform("MotionConfig", uniformRing.currentBuffer().slice((index * stride).toLong(), 96))
                    val texture = material.prepared.textures().firstOrNull { it.name() == "Sampler0" }
                    pass.bindTexture("Sampler0", texture?.textureView() ?: checkNotNull(world.colorTextureView), texture?.sampler() ?: nearest)
                    pass.bindTexture("WorldDepth", checkNotNull(world.depthTextureView), nearest)
                    pass.draw(expandedCounts[index], 1, firstVertex, 0)
                    firstVertex += expandedCounts[index]
                }
            }
            vertexRing.rotate()
            uniformRing.rotate()
        } finally {
            MemoryUtil.memFree(vertexData)
            MemoryUtil.memFree(uniformData)
        }
        return output
    }

    private fun ensureBuffers(vertexBytes: Int, uniformBytes: Int) {
        if ((vertices?.currentBuffer()?.size() ?: 0) < vertexBytes) {
            vertices?.close()
            vertices = null
            vertices = MappableRingBuffer({ "aamod motion vertices" }, GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_MAP_WRITE, vertexBytes)
        }
        if ((uniforms?.currentBuffer()?.size() ?: 0) < uniformBytes) {
            uniforms?.close()
            uniforms = null
            uniforms = MappableRingBuffer({ "aamod motion uniforms" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_MAP_WRITE, uniformBytes)
        }
    }

    private fun allocate(width: Int, height: Int) {
        if (target?.let { it.width == width && it.height == height } == true) return
        close()
        val device = RenderSystem.getDevice()
        check(device.precompilePipeline(CULLED).isValid && device.precompilePipeline(TWO_SIDED).isValid) { "Could not compile object motion vectors" }
        target = object : RenderTarget("aamod object motion vectors", false, GpuFormat.RGBA32_FLOAT) {}
        try { checkNotNull(target).createBuffers(width, height) } catch (exception: Exception) { close(); throw exception }
    }

    override fun close() {
        target?.destroyBuffers()
        target = null
        vertices?.close()
        vertices = null
        uniforms?.close()
        uniforms = null
    }

    companion object {
        private val FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGBA32_FLOAT)
            .addAttribute("PreviousClip", GpuFormat.RGBA32_FLOAT)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("MotionState", GpuFormat.RG32_FLOAT).build()
        private fun pipeline(cull: Boolean) = RenderPipeline.builder()
            .withLocation(AAMod.id(if (cull) "taa/motion_culled" else "taa/motion_two_sided"))
            .withVertexShader(AAMod.id("post/taa_motion")).withFragmentShader(AAMod.id("post/taa_motion"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withVertexBinding(0, FORMAT).withCull(cull)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL))
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("Sampler0").withSampler("WorldDepth")
                .withUniform("MotionConfig", UniformType.UNIFORM_BUFFER).build()).build()
        private val CULLED = pipeline(true)
        private val TWO_SIDED = pipeline(false)
    }
}
