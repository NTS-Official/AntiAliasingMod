package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
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
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.vertex.VertexFormat
import com.nts.aamod.AAMod
import net.minecraft.client.Minecraft
import org.joml.Vector4f
import org.lwjgl.system.MemoryUtil
import java.util.Optional

class EffectMotionPass : AutoCloseable {
    private var motionTarget: RenderTarget? = null
    private var identityTarget: RenderTarget? = null
    private var coherenceTarget: RenderTarget? = null
    private var conflictTarget: RenderTarget? = null
    private val vertices = TemporalUploadArena("aamod effect vertices", GpuBuffer.USAGE_VERTEX, 262144)
    private val uniforms = TemporalUploadArena("aamod effect motion uniforms", GpuBuffer.USAGE_UNIFORM, 4096)
    private var vertexValues = FloatArray(0)
    private var indices: GpuBuffer? = null
    val motion: RenderTarget get() = checkNotNull(motionTarget)
    val identity: RenderTarget get() = checkNotNull(identityTarget)
    val conflict: RenderTarget get() = checkNotNull(conflictTarget)
    val allocatedBytes: Long get() = (motionTarget?.let { it.width.toLong() * it.height * 36 } ?: 0L) +
        vertices.allocatedBytes + uniforms.allocatedBytes + (indices?.size() ?: 0L)

    private fun reserve(width: Int, height: Int) {
        val device = RenderSystem.getDevice()
        if (motionTarget?.let { it.width != width || it.height != height } != false) {
            releaseTargets()
            try {
                for (pipeline in listOf(MOTION, CONFLICT).flatMap { it.toList() }) {
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
        return vertices.additionalBytes(Math.multiplyExact(drawCount, 288), 4) +
            uniforms.additionalBytes(Math.multiplyExact(groupCount, stride), alignment) +
            if (indices == null) INDEX_BYTES.toLong() else 0L
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
        if (worldMotion == null) reserve(source.width, source.height)
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
        val valueCount = draws.size * 4 * 18
        if (vertexValues.size < valueCount) vertexValues = FloatArray(Integer.highestOneBit(valueCount - 1) shl 1)
        val vertexData = MemoryUtil.memAlloc(valueCount * 4)
        val uniformData = MemoryUtil.memCalloc(groups.size * stride)
        // IDs are collision-free within this current pass; no group identity enters history.
        val coherenceGroups = mutableMapOf<Long, Int>()
        try {
            var value = 0
            for ((index, group) in groups.withIndex()) {
                for (draw in group.value) {
                    val correspondence = draw.correspondence
                    val current = correspondence.current.clip()
                    val previous = correspondence.previousClip()
                    val previousCoordinates = correspondence.previousCoordinates() ?: QUAD_COORDINATES
                    val token = correspondence.current.token
                    val coherence = draw.quad.coherenceOwner?.let { owner ->
                        coherenceGroups.getOrPut(owner) { coherenceGroups.size + 1 }
                    } ?: 0
                    for (corner in 0 until 4) {
                        current.copyInto(vertexValues, value, corner * 4, corner * 4 + 4)
                        value += 4
                        for (component in 0 until 4) vertexValues[value++] = previous?.get(corner * 4 + component) ?: 0f
                        vertexValues[value++] = draw.quad.uv[corner * 2]
                        vertexValues[value++] = draw.quad.uv[corner * 2 + 1]
                        vertexValues[value++] = draw.quad.alphas?.get(corner) ?: ((draw.quad.color ushr 24) / 255f)
                        vertexValues[value++] = correspondence.confidence
                        vertexValues[value++] = (token and 255) / 255f
                        vertexValues[value++] = (token ushr 8 and 255) / 255f
                        vertexValues[value++] = (token ushr 16 and 255) / 255f
                        vertexValues[value++] = coherence.toFloat()
                        vertexValues[value++] = previousCoordinates[corner * 2]
                        vertexValues[value++] = previousCoordinates[corner * 2 + 1]
                    }
                }
                val pipeline = group.key.pipeline()
                val shader = pipeline.fragmentShader.takeIf { it.namespace == "minecraft" }?.path
                val cutoff = when (shader) {
                    "core/particle", "core/text", "core/text_background", "core/glint", "core/rendertype_crumbling" -> 0.1f
                    else -> pipeline.shaderDefines.values()["ALPHA_CUTOUT"]?.removeSuffix("F")?.removeSuffix("f")?.toFloatOrNull() ?: 0f
                }
                val coverageChannel = when {
                    shader == "core/text_background" -> 2f
                    shader == "core/text" && "IS_GRAYSCALE" in pipeline.shaderDefines.flags() -> 1f
                    else -> 0f
                }
                val textureOnlyCutout = shader == "core/entity" || shader == "core/item"
                uniformData.position(index * stride)
                uniformData.putFloat(1f / source.width).putFloat(1f / source.height).putFloat(source.width.toFloat()).putFloat(source.height.toFloat())
                uniformData.putFloat(cutoff).putFloat(if (RenderSystem.getDevice().deviceInfo.isZZeroToOne) 1f else 0f)
                    .putFloat(coverageChannel).putFloat(if (textureOnlyCutout) 1f else 0f)
            }
            vertexData.asFloatBuffer().put(vertexValues, 0, valueCount)
            uniformData.position(0).limit(groups.size * stride)
            val vertexSlice = vertices.upload(vertexData, 4)
            val indexBuffer = indices ?: run {
                val data = MemoryUtil.memAlloc(INDEX_BYTES)
                try {
                    val values = data.asIntBuffer()
                    repeat(65536) { face ->
                        for (corner in QUAD_INDICES) values.put(face * 4 + corner)
                    }
                    RenderSystem.getDevice().createBuffer({ "aamod effect quad indices" }, GpuBuffer.USAGE_INDEX, data)
                        .also { indices = it }
                } finally { MemoryUtil.memFree(data) }
            }
            val uniformSlice = uniforms.upload(uniformData, alignment)
            val nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            val outputs = if (worldMotion == null) listOf(motion to MOTION, conflict to CONFLICT)
                else listOf(worldMotion to WORLD_MOTION)
            for ((output, pipelines) in outputs) {
                val descriptor = RenderPassDescriptor.create { "aamod effect ${pipelines[0].location.path}" }
                    .withColorAttachment(checkNotNull(output.colorTextureView))
                    .withRenderArea(RenderPass.RenderArea(0, 0, source.width, source.height))
                if (pipelines === MOTION) {
                    descriptor.withColorAttachment(checkNotNull(identity.colorTextureView))
                        .withColorAttachment(checkNotNull(coherenceTarget?.colorTextureView))
                }
                output.depthTextureView?.let { descriptor.withDepthAttachment(it) }
                encoder.createRenderPass(descriptor).use { pass ->
                    pass.setVertexBuffer(0, vertexSlice)
                    pass.setIndexBuffer(indexBuffer, IndexType.INT)
                    var firstVertex = 0
                    for ((index, group) in groups.withIndex()) {
                        pass.setPipeline(pipelines[if (group.key.pipeline().isCull) 1 else 0])
                        if (pipelines === CONFLICT) pass.bindTexture("LayerDepth", checkNotNull(motion.depthTextureView), nearest)
                        pass.bindTexture("EntryDepth", checkNotNull(entryDepth.depthTextureView), nearest)
                        if (pipelines === CONFLICT) {
                            pass.bindTexture("ReferenceMotion", checkNotNull(motion.colorTextureView), nearest)
                            pass.bindTexture("ReferenceIdentity", checkNotNull(identity.colorTextureView), nearest)
                            pass.bindTexture("ReferenceCoherence", checkNotNull(coherenceTarget?.colorTextureView), nearest)
                        }
                        val texture = Minecraft.getInstance().textureManager.getTexture(group.key.textureAtlasLocation())
                        pass.bindTexture("Sampler0", texture.textureView, texture.sampler)
                        pass.setUniform("EffectMotionConfig", uniformSlice.slice((index * stride).toLong(), 32))
                        pass.drawIndexed(group.value.size * 6, 1, 0, firstVertex, 0)
                        firstVertex += group.value.size * 4
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
        vertexValues = FloatArray(0)
        indices?.close()
        indices = null
    }

    companion object {
        private const val INDEX_BYTES = 65536 * 6 * 4
        private val QUAD_INDICES = intArrayOf(0, 1, 2, 2, 3, 0)
        private val QUAD_COORDINATES = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        private val FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGBA32_FLOAT).addAttribute("PreviousClip", GpuFormat.RGBA32_FLOAT)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT).addAttribute("EffectState", GpuFormat.RG32_FLOAT)
            .addAttribute("SurfaceIdentity", GpuFormat.RGBA32_FLOAT).addAttribute("PreviousLocal", GpuFormat.RG32_FLOAT).build()

        private fun pipeline(name: String, format: GpuFormat, additive: Boolean, cull: Boolean): RenderPipeline {
            val bindings = BindGroupLayout.builder().withSampler("Sampler0").withSampler("EntryDepth")
            if (name == "conflict") bindings.withSampler("LayerDepth")
            if (name == "conflict") bindings.withSampler("ReferenceMotion").withSampler("ReferenceIdentity").withSampler("ReferenceCoherence")
            val builder = RenderPipeline.builder()
            .withLocation(AAMod.id("taa/effect_${name}_${if (cull) "culled" else "two_sided"}"))
            .withVertexShader(AAMod.id("post/taa_effect_motion")).withFragmentShader(AAMod.id("post/taa_effect_motion"))
            .withShaderDefine("EFFECT_OUTPUT", if (name == "conflict") 2 else 0)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withVertexBinding(0, FORMAT).withCull(cull)
            .withDepthStencilState(when {
                name == "motion" -> Optional.of(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
                else -> Optional.empty()
            })
            .withColorTargetState(ColorTargetState(if (additive) Optional.of(BlendFunction.ADDITIVE) else Optional.empty(), format, ColorTargetState.WRITE_ALL))
            .withBindGroupLayout(bindings.withUniform("EffectMotionConfig", UniformType.UNIFORM_BUFFER).build())
            if (name == "motion") {
                builder.withColorTargetState(1, ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .withColorTargetState(2, ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            }
            if (name == "world") builder.withShaderDefine("EFFECT_WORLD")
            return builder.build()
        }

        private fun variants(name: String, format: GpuFormat, additive: Boolean) =
            arrayOf(pipeline(name, format, additive, false), pipeline(name, format, additive, true))
        private val MOTION = variants("motion", GpuFormat.RGBA32_FLOAT, false)
        private val CONFLICT = variants("conflict", GpuFormat.RGBA16_FLOAT, true)
        private val WORLD_MOTION = variants("world", GpuFormat.RGBA32_FLOAT, false)
    }
}
