package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.vertex.VertexFormat
import com.nts.aamod.AAMod
import net.minecraft.client.Minecraft
import org.joml.Vector4f
import org.lwjgl.system.MemoryUtil
import java.util.Optional
import java.util.OptionalDouble

class EffectMotionPass : AutoCloseable {
    private var motionTarget: RenderTarget? = null
    private var identityTarget: RenderTarget? = null
    private var coherenceTarget: RenderTarget? = null
    private var conflictTarget: RenderTarget? = null
    private val vertices = TemporalUploadArena("aamod effect vertices", GpuBuffer.USAGE_VERTEX, 262144)
    private val uniforms = TemporalUploadArena("aamod effect motion uniforms", GpuBuffer.USAGE_UNIFORM, 4096)
    val motion: RenderTarget get() = checkNotNull(motionTarget)
    val identity: RenderTarget get() = checkNotNull(identityTarget)
    val conflict: RenderTarget get() = checkNotNull(conflictTarget)
    val allocatedBytes: Long get() = (motionTarget?.let { it.width.toLong() * it.height * 36 } ?: 0L) +
        vertices.allocatedBytes + uniforms.allocatedBytes

    fun reserve(width: Int, height: Int, vertexCount: Int, drawCount: Int) {
        require(vertexCount in 0..393216 && drawCount in 0..65536)
        val device = RenderSystem.getDevice()
        if (motionTarget?.let { it.width != width || it.height != height } != false) {
            releaseTargets()
            try {
                for (pipeline in listOf(MOTION, IDENTITY, COHERENCE, CONFLICT).flatMap { it.toList() }) {
                    check(device.precompilePipeline(pipeline).isValid) { "Could not compile effect motion pipeline" }
                }
                motionTarget = target("motion", GpuFormat.RGBA32_FLOAT, width, height, true)
                identityTarget = target("identity", GpuFormat.RGBA8_UNORM, width, height)
                coherenceTarget = target("coherence", GpuFormat.RGBA8_UNORM, width, height)
                conflictTarget = target("conflict", GpuFormat.RGBA16_FLOAT, width, height)
            } catch (exception: Exception) { close(); throw exception }
        }
    }

    fun beginFrame() { vertices.beginFrame(); uniforms.beginFrame() }
    fun endFrame() { vertices.endFrame(); uniforms.endFrame() }

    fun additionalUploadBytes(drawCount: Int, groupCount: Int): Long {
        if (drawCount == 0) return 0L
        val alignment = RenderSystem.getDevice().deviceInfo.limits().minUniformOffsetAlignment()
        val stride = (32 + alignment - 1) / alignment * alignment
        return vertices.additionalBytes(Math.multiplyExact(drawCount, 432), 4) +
            uniforms.additionalBytes(Math.multiplyExact(groupCount, stride), alignment)
    }

    fun additionalAttachmentBytes(width: Int, height: Int): Long =
        if (motionTarget?.let { it.width == width && it.height == height } == true) 0L else width.toLong() * height * 36

    fun render(source: RenderTarget, entryDepth: RenderTarget, draws: List<EffectDraw>) =
        render(source, entryDepth, draws, null)

    fun renderWorld(source: RenderTarget, destination: RenderTarget, draws: List<EffectDraw>) {
        require(destination.width == source.width && destination.height == source.height)
        render(source, source, draws, destination)
    }

    private fun render(source: RenderTarget, entryDepth: RenderTarget, draws: List<EffectDraw>, worldMotion: RenderTarget?) {
        require(draws.size <= 65536)
        val groups = draws.groupBy { it.quad.layer }.entries.toList()
        if (worldMotion == null) reserve(source.width, source.height, draws.size * 6, groups.size)
        else for (pipeline in WORLD_MOTION) {
            check(RenderSystem.getDevice().precompilePipeline(pipeline).isValid) { "Could not compile opaque effect motion pipeline" }
        }
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        if (worldMotion == null) {
            for (target in listOf(motion, identity, checkNotNull(coherenceTarget), conflict)) {
                encoder.clearColorTexture(checkNotNull(target.colorTexture), Vector4f(0f))
            }
            motion.copyDepthFrom(entryDepth)
        }
        if (draws.isEmpty()) return
        val alignment = RenderSystem.getDevice().deviceInfo.limits().minUniformOffsetAlignment()
        val stride = (32 + alignment - 1) / alignment * alignment
        val vertexData = MemoryUtil.memAlloc(draws.size * 6 * 72)
        val uniformData = MemoryUtil.memCalloc(groups.size * stride)
        // IDs are collision-free within this current pass; no group identity enters history.
        val coherenceGroups = mutableMapOf<Long, Int>()
        try {
            for ((index, group) in groups.withIndex()) {
                for (draw in group.value) {
                    val correspondence = draw.correspondence
                    val current = correspondence.current.clip()
                    val previous = correspondence.previousClip()
                    val previousCoordinates = correspondence.previousCoordinates() ?: floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
                    val token = correspondence.current.token
                    val coherence = draw.quad.coherenceOwner?.let { owner ->
                        coherenceGroups.getOrPut(owner) { coherenceGroups.size + 1 }
                    } ?: 0
                    for (corner in intArrayOf(0, 1, 2, 2, 3, 0)) {
                        repeat(4) { component -> vertexData.putFloat(current[corner * 4 + component]) }
                        repeat(4) { component -> vertexData.putFloat(previous?.get(corner * 4 + component) ?: 0f) }
                        vertexData.putFloat(draw.quad.uv[corner * 2]).putFloat(draw.quad.uv[corner * 2 + 1])
                        vertexData.putFloat(draw.quad.alphas?.get(corner) ?: ((draw.quad.color ushr 24) / 255f)).putFloat(correspondence.confidence)
                        vertexData.putFloat((token and 255) / 255f).putFloat((token ushr 8 and 255) / 255f)
                            .putFloat((token ushr 16 and 255) / 255f).putFloat(coherence.toFloat())
                        vertexData.putFloat(previousCoordinates[corner * 2]).putFloat(previousCoordinates[corner * 2 + 1])
                    }
                }
                val cutoff = group.key.pipeline().shaderDefines.values()["ALPHA_CUTOUT"]?.removeSuffix("F")?.removeSuffix("f")?.toFloatOrNull() ?: 0f
                uniformData.position(index * stride)
                uniformData.putFloat(1f / source.width).putFloat(1f / source.height).putFloat(source.width.toFloat()).putFloat(source.height.toFloat())
                uniformData.putFloat(cutoff).putFloat(if (RenderSystem.getDevice().deviceInfo.isZZeroToOne) 1f else 0f).putFloat(0f).putFloat(0f)
            }
            vertexData.flip()
            uniformData.position(0).limit(groups.size * stride)
            val vertexSlice = vertices.upload(vertexData, 4)
            val uniformSlice = uniforms.upload(uniformData, alignment)
            val nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            val outputs = if (worldMotion == null) listOf(motion to MOTION, identity to IDENTITY,
                checkNotNull(coherenceTarget) to COHERENCE, conflict to CONFLICT)
                else listOf(worldMotion to WORLD_MOTION)
            for ((output, pipelines) in outputs) {
                val representative = pipelines === IDENTITY || pipelines === COHERENCE
                encoder.createRenderPass({ "aamod effect ${pipelines[0].location.path}" }, checkNotNull(output.colorTextureView), Optional.empty(),
                    if (representative) motion.depthTextureView else output.depthTextureView, OptionalDouble.empty()).use { pass ->
                    pass.setVertexBuffer(0, vertexSlice)
                    var firstVertex = 0
                    for ((index, group) in groups.withIndex()) {
                        pass.setPipeline(pipelines[if (group.key.pipeline().isCull) 1 else 0])
                        if (pipelines === CONFLICT) pass.bindTexture("LayerDepth", checkNotNull(motion.depthTextureView), nearest)
                        if (!representative) pass.bindTexture("EntryDepth", checkNotNull(entryDepth.depthTextureView), nearest)
                        if (pipelines === CONFLICT) {
                            pass.bindTexture("ReferenceMotion", checkNotNull(motion.colorTextureView), nearest)
                            pass.bindTexture("ReferenceIdentity", checkNotNull(identity.colorTextureView), nearest)
                            pass.bindTexture("ReferenceCoherence", checkNotNull(coherenceTarget?.colorTextureView), nearest)
                        }
                        val texture = Minecraft.getInstance().textureManager.getTexture(group.key.textureAtlasLocation())
                        pass.bindTexture("Sampler0", texture.textureView, texture.sampler)
                        pass.setUniform("EffectMotionConfig", uniformSlice.slice((index * stride).toLong(), 32))
                        pass.draw(group.value.size * 6, 1, firstVertex, 0)
                        firstVertex += group.value.size * 6
                    }
                }
            }
        } finally {
            MemoryUtil.memFree(vertexData)
            MemoryUtil.memFree(uniformData)
        }
    }

    private fun target(name: String, format: GpuFormat, width: Int, height: Int, depth: Boolean = false): RenderTarget {
        val result = object : RenderTarget("aamod effect $name", depth, format) {}
        try { result.createBuffers(width, height) } catch (exception: Exception) { result.destroyBuffers(); throw exception }
        return result
    }

    private fun releaseTargets() {
        motionTarget?.destroyBuffers()
        identityTarget?.destroyBuffers()
        coherenceTarget?.destroyBuffers()
        conflictTarget?.destroyBuffers()
        motionTarget = null
        identityTarget = null
        coherenceTarget = null
        conflictTarget = null
    }

    override fun close() {
        releaseTargets()
        vertices.close()
        uniforms.close()
    }

    companion object {
        private val FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGBA32_FLOAT).addAttribute("PreviousClip", GpuFormat.RGBA32_FLOAT)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT).addAttribute("EffectState", GpuFormat.RG32_FLOAT)
            .addAttribute("SurfaceIdentity", GpuFormat.RGBA32_FLOAT).addAttribute("PreviousLocal", GpuFormat.RG32_FLOAT).build()

        private fun pipeline(name: String, format: GpuFormat, additive: Boolean, cull: Boolean): RenderPipeline {
            val bindings = BindGroupLayout.builder().withSampler("Sampler0")
            val representative = name == "identity" || name == "coherence"
            if (name == "conflict") bindings.withSampler("LayerDepth")
            if (!representative) bindings.withSampler("EntryDepth")
            if (name == "conflict") bindings.withSampler("ReferenceMotion").withSampler("ReferenceIdentity").withSampler("ReferenceCoherence")
            val builder = RenderPipeline.builder()
            .withLocation(AAMod.id("taa/effect_${name}_${if (cull) "culled" else "two_sided"}"))
            .withVertexShader(AAMod.id("post/taa_effect_motion")).withFragmentShader(AAMod.id("post/taa_effect_motion"))
            .withShaderDefine("EFFECT_OUTPUT", when (name) { "motion" -> 0; "identity" -> 1; "coherence" -> 3; else -> 2 })
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withVertexBinding(0, FORMAT).withCull(cull)
            .withDepthStencilState(when {
                name == "motion" -> Optional.of(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
                representative -> Optional.of(DepthStencilState(CompareOp.EQUAL, false))
                else -> Optional.empty()
            })
            .withColorTargetState(ColorTargetState(if (additive) Optional.of(BlendFunction.ADDITIVE) else Optional.empty(), format, ColorTargetState.WRITE_ALL))
            .withBindGroupLayout(bindings.withUniform("EffectMotionConfig", UniformType.UNIFORM_BUFFER).build())
            if (name == "world") builder.withShaderDefine("EFFECT_WORLD")
            return builder.build()
        }

        private fun variants(name: String, format: GpuFormat, additive: Boolean) =
            arrayOf(pipeline(name, format, additive, false), pipeline(name, format, additive, true))
        private val MOTION = variants("motion", GpuFormat.RGBA32_FLOAT, false)
        private val IDENTITY = variants("identity", GpuFormat.RGBA8_UNORM, false)
        private val COHERENCE = variants("coherence", GpuFormat.RGBA8_UNORM, false)
        private val CONFLICT = variants("conflict", GpuFormat.RGBA16_FLOAT, true)
        private val WORLD_MOTION = variants("world", GpuFormat.RGBA32_FLOAT, false)
    }
}
