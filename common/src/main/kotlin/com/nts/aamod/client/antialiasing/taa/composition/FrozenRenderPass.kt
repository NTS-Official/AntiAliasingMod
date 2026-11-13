package com.nts.aamod.client.antialiasing.taa.composition

import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTextureView
import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryUtil
import java.nio.IntBuffer
import java.util.Collections
import java.util.function.BiConsumer

data class FrozenTexture(val view: GpuTextureView?, val sampler: GpuSampler?)

data class FrozenIndexBuffer(val buffer: GpuBuffer, val type: IndexType)

data class FrozenScissor(val left: Int, val top: Int, val width: Int, val height: Int)

fun interface FrozenDrawExtension {
    fun apply(pass: RenderPass, pipeline: RenderPipeline)
}

class FrozenDrawState internal constructor(
    val pipeline: RenderPipeline,
    vertices: Map<Int, GpuBufferSlice?>,
    uniforms: Map<String, GpuBufferSlice>,
    textures: Map<String, FrozenTexture>,
    val indices: FrozenIndexBuffer?,
    val scissor: FrozenScissor?,
    val extension: FrozenDrawExtension? = null
) {
    val vertices: Map<Int, GpuBufferSlice?> = Collections.unmodifiableMap(LinkedHashMap(vertices))
    val uniforms: Map<String, GpuBufferSlice> = Collections.unmodifiableMap(LinkedHashMap(uniforms))
    val textures: Map<String, FrozenTexture> = Collections.unmodifiableMap(LinkedHashMap(textures))

    fun apply(pass: RenderPass, pipelineOverride: RenderPipeline = pipeline) {
        pass.setPipeline(pipelineOverride)
        for (slot in 0 until RenderPass.MAX_VERTEX_BUFFERS) pass.setVertexBuffer(slot, vertices[slot])
        indices?.let { pass.setIndexBuffer(it.buffer, it.type) }
        uniforms.forEach { (name, value) -> pass.setUniform(name, value) }
        textures.forEach { (name, texture) -> pass.bindTexture(name, texture.view, texture.sampler) }
        scissor?.let { pass.enableScissor(it.left, it.top, it.width, it.height) } ?: pass.disableScissor()
        extension?.apply(pass, pipelineOverride)
    }
}

sealed interface FrozenDrawCommand {
    val parameterBytes: Long get() = 0L
    fun replay(pass: RenderPass)

    data class Direct(
        val indexed: Boolean, val count: Int, val instances: Int, val first: Int,
        val vertexOffset: Int, val firstInstance: Int
    ) : FrozenDrawCommand {
        override fun replay(pass: RenderPass) {
            if (indexed) pass.drawIndexed(count, instances, first, vertexOffset, firstInstance)
            else pass.draw(count, instances, first, firstInstance)
        }
    }

    class Interleaved(
        val indexed: Boolean, parameters: IntArray, val instances: Int, val firstInstance: Int, val count: Int
    ) : FrozenDrawCommand {
        private val data = parameters.copyOf()
        override val parameterBytes: Long get() = data.size.toLong() * 4
        init { require(count >= 0 && data.size == Math.multiplyExact(count, if (indexed) 3 else 2)) }
        fun parameters(): IntArray = data.copyOf()
        override fun replay(pass: RenderPass) {
            val parameters = MemoryUtil.memAllocInt(data.size)
            try {
                parameters.put(data).flip()
                if (indexed) pass.multiDrawIndexed(parameters, instances, firstInstance, count)
                else pass.multiDraw(parameters, instances, firstInstance, count)
            } finally { MemoryUtil.memFree(parameters) }
        }
    }

    class Separate(indexOffsets: LongArray?, counts: IntArray, vertices: IntArray) : FrozenDrawCommand {
        private val offsets = indexOffsets?.copyOf()
        private val sizes = counts.copyOf()
        private val starts = vertices.copyOf()
        val count: Int get() = sizes.size
        override val parameterBytes: Long get() = count.toLong() * if (offsets == null) 8 else 16
        init { require(sizes.size == starts.size && (offsets == null || offsets.size == sizes.size)) }
        fun indexOffsets(): LongArray? = offsets?.copyOf()
        fun counts(): IntArray = sizes.copyOf()
        fun vertices(): IntArray = starts.copyOf()
        override fun replay(pass: RenderPass) {
            val counts = MemoryUtil.memAllocInt(count)
            val vertices = MemoryUtil.memAllocInt(count)
            var pointers: PointerBuffer? = null
            try {
                counts.put(sizes).flip()
                vertices.put(starts).flip()
                val indices = offsets
                if (indices == null) pass.multiDraw(vertices, counts, count)
                else {
                    val offsetsBuffer = MemoryUtil.memAllocPointer(count)
                    pointers = offsetsBuffer
                    indices.forEach { offsetsBuffer.put(it) }
                    offsetsBuffer.flip()
                    pass.multiDrawIndexed(offsetsBuffer, counts, vertices, count)
                }
            } finally {
                MemoryUtil.memFree(counts)
                MemoryUtil.memFree(vertices)
                MemoryUtil.memFree(pointers)
            }
        }
    }

    data class Indirect(val indexed: Boolean, val commands: GpuBufferSlice, val count: Int) : FrozenDrawCommand {
        override fun replay(pass: RenderPass) {
            if (indexed) pass.drawIndexedIndirect(commands, count) else pass.drawIndirect(commands, count)
        }
    }
}

class FrozenDraw(val state: FrozenDrawState, val command: FrozenDrawCommand) {
    fun replay(pass: RenderPass, pipeline: RenderPipeline = state.pipeline) {
        state.apply(pass, pipeline)
        command.replay(pass)
    }
}

interface FrozenRenderPassAccess {
    var aamodFrozenPass: FrozenPassRecorder?
}

class FrozenPassRecorder @JvmOverloads constructor(private val maxDraws: Int = 65536, private val maxParameterBytes: Long = 16L * 1024 * 1024) {
    private var pipeline: RenderPipeline? = null
    private val vertices = linkedMapOf<Int, GpuBufferSlice?>()
    private val uniforms = linkedMapOf<String, GpuBufferSlice>()
    private val textures = linkedMapOf<String, FrozenTexture>()
    private var indices: FrozenIndexBuffer? = null
    private var scissor: FrozenScissor? = null
    private var extension: FrozenDrawExtension? = null
    private val captured = mutableListOf<FrozenDraw>()
    var failure: String? = null
        private set
    var onFinished: ((FrozenPassRecorder) -> Unit)? = null
    var finished = false
        private set
    val draws: List<FrozenDraw> get() = Collections.unmodifiableList(captured)
    var parameterBytes: Long = 0
        private set

    init { require(maxDraws > 0 && maxParameterBytes >= 0) }

    fun pipeline(value: RenderPipeline) { pipeline = value }
    fun vertex(slot: Int, value: GpuBufferSlice?) { vertices[slot] = value }
    fun uniform(name: String, value: GpuBufferSlice) { uniforms[name] = value }
    fun texture(name: String, view: GpuTextureView?, sampler: GpuSampler?) { textures[name] = FrozenTexture(view, sampler) }
    fun indices(buffer: GpuBuffer, type: IndexType) { indices = FrozenIndexBuffer(buffer, type) }
    fun scissor(value: FrozenScissor?) { scissor = value }
    fun extension(value: FrozenDrawExtension?) { extension = value }
    fun reject(reason: String) { if (failure == null) failure = reason }

    private fun snapshot(): FrozenDrawState? {
        val actualPipeline = pipeline ?: run { reject("draw_pipeline_missing"); return null }
        return FrozenDrawState(actualPipeline, vertices, uniforms, textures, indices, scissor, extension)
    }

    fun record(command: FrozenDrawCommand) {
        check(!finished) { "Cannot capture a finished render pass" }
        if (failure != null) return
        if (captured.size >= maxDraws) { reject("draw_capture_budget"); return }
        if (command.parameterBytes > maxParameterBytes - parameterBytes) { reject("draw_parameter_budget"); return }
        snapshot()?.let { captured.add(FrozenDraw(it, command)); parameterBytes += command.parameterBytes }
    }

    fun interleaved(indexed: Boolean, parameters: IntBuffer, instances: Int, firstInstance: Int, count: Int) {
        if (failure != null) return
        val size = count.toLong() * if (indexed) 3 else 2
        if (count < 0 || size > 1048576 || size > parameters.remaining()) { reject("draw_parameter_budget"); return }
        val data = IntArray(size.toInt())
        parameters.duplicate().get(data)
        record(FrozenDrawCommand.Interleaved(indexed, data, instances, firstInstance, count))
    }

    fun separate(indexOffsets: PointerBuffer?, counts: IntBuffer, vertices: IntBuffer, count: Int) {
        if (failure != null) return
        if (count !in 0..262144 || count > counts.remaining() || count > vertices.remaining() ||
            indexOffsets != null && count > indexOffsets.remaining()
        ) { reject("draw_parameter_budget"); return }
        val offsets = indexOffsets?.let { source -> LongArray(count) { source.get(source.position() + it) } }
        val sizes = IntArray(count)
        val starts = IntArray(count)
        counts.duplicate().get(sizes)
        vertices.duplicate().get(starts)
        record(FrozenDrawCommand.Separate(offsets, sizes, starts))
    }

    fun <Value : Any> captureMultiple(
        draws: Collection<RenderPass.Draw<Value>>, defaultIndices: GpuBuffer?, defaultType: IndexType?,
        dynamicUniforms: Collection<String>, nativeDraw: (Collection<RenderPass.Draw<Value>>) -> Unit
    ) {
        check(!finished) { "Cannot capture a finished render pass" }
        if (failure != null) { nativeDraw(draws); return }
        if (draws.size > maxDraws - captured.size) { reject("draw_capture_budget"); nativeDraw(draws); return }
        val base = snapshot()
        if (base == null) { nativeDraw(draws); return }
        val states = mutableListOf<FrozenDrawState>()
        val uploads = LinkedHashMap(base.uniforms)
        val buffers = LinkedHashMap(base.vertices)
        val originalDraws = draws.toList()
        val callbacks = IntArray(originalDraws.size)
        var observedCallbacks = 0
        val capturedStates = arrayOfNulls<FrozenDrawState>(originalDraws.size)
        val wrapped = originalDraws.mapIndexed { index, draw ->
            RenderPass.Draw<Value>(draw.slot(), draw.vertexBuffer(), draw.indexBuffer(), draw.indexType(),
                draw.firstIndex(), draw.indexCount(), draw.baseVertex(), BiConsumer<Value, RenderPass.UniformUploader> { argument, uploader ->
                    callbacks[index]++
                    if (observedCallbacks++ != index) reject("draw_callback_order_changed")
                    draw.uniformUploaderConsumer()?.accept(argument, RenderPass.UniformUploader { name, buffer ->
                        uploads[name] = buffer
                        uploader.upload(name, buffer)
                    })
                    buffers[draw.slot()] = draw.vertexBuffer().slice()
                    val indexBuffer = draw.indexBuffer() ?: defaultIndices
                    val indexType = draw.indexType() ?: defaultType ?: IndexType.SHORT
                    if (indexBuffer == null) reject("draw_index_buffer_missing")
                    else capturedStates[index] = FrozenDrawState(base.pipeline, buffers, uploads, base.textures,
                        FrozenIndexBuffer(indexBuffer, indexType), base.scissor, base.extension)
                })
        }
        nativeDraw(wrapped)
        if (callbacks.any { it != 1 }) { reject("draw_uniform_callback_not_observed"); return }
        for (index in originalDraws.indices) {
            val state = capturedStates[index] ?: return
            if (dynamicUniforms.any { it !in state.uniforms }) { reject("draw_dynamic_uniform_missing"); return }
            states.add(state)
        }
        if (failure != null) return
        for ((index, draw) in originalDraws.withIndex()) {
            captured.add(FrozenDraw(states[index], FrozenDrawCommand.Direct(true, draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0)))
        }
        states.lastOrNull()?.let { last ->
            vertices.clear()
            vertices.putAll(last.vertices)
            indices = last.indices
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        val callback = onFinished
        onFinished = null
        callback?.invoke(this)
    }
}
