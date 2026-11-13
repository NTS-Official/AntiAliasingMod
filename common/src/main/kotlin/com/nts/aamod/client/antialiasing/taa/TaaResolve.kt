package com.nts.aamod.client.antialiasing.taa

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.systems.GpuQueryPool
import com.mojang.blaze3d.textures.FilterMode
import com.nts.aamod.AAMod
import net.minecraft.client.renderer.MappableRingBuffer
import org.joml.Matrix4f
import org.joml.Vector4f
import org.lwjgl.system.MemoryStack
import java.util.Optional
import kotlin.math.min

class TaaResolve : AutoCloseable {
    private val history = mutableListOf<RenderTarget>()
    private val rawColors = mutableListOf<RenderTarget>()
    private var uniforms: MappableRingBuffer? = null
    private var writeIndex = 0
    private var timestamps: GpuQueryPool? = null
    private val pendingQueries = BooleanArray(4)
    private var nextQuery = 0
    private var pending = false
    private var pendingSamples = 0
    private var pendingQuery = -1
    private var started = 0L
    var historyFrames = 0
        private set
    var resolveCpuNanos = 0L
        private set
    var resolveGpuNanos = -1L
        private set
    val allocatedBytes: Long
        get() = history.sumOf { it.width.toLong() * it.height * 12 } +
            rawColors.sumOf { it.width.toLong() * it.height * 4 } + (uniforms?.size()?.toLong() ?: 0L) * 3

    fun resetHistory() {
        abort()
        historyFrames = 0
    }

    fun render(target: RenderTarget, reproject: Matrix4f, current: JitterPoint, previous: JitterPoint, samples: Int, sharpness: Float, motion: RenderTarget) {
        try {
            val output = prepare(target, reproject, current, previous, samples, sharpness, motion)
            present(target, output)
            commit()
        } catch (exception: Exception) {
            abort()
            throw exception
        }
    }

    fun prepare(target: RenderTarget, reproject: Matrix4f, current: JitterPoint, previous: JitterPoint, samples: Int, sharpness: Float, motion: RenderTarget): RenderTarget {
        check(!pending) { "TAA history transaction is already pending" }
        started = System.nanoTime()
        check(target.colorTexture?.format == GpuFormat.RGBA8_UNORM) { "Unsupported TAA world color format" }
        allocate(target.width, target.height)
        val output = history[writeIndex]
        val prior = history[1 - writeIndex]
        val raw = rawColors[1 - writeIndex]
        val ring = checkNotNull(uniforms)
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val queries = timestamps
        val queryIndex = nextQuery
        if (queries != null && pendingQueries[queryIndex]) {
            val begin = queries.getValue(queryIndex * 2)
            val end = queries.getValue(queryIndex * 2 + 1)
            if (begin.isPresent && end.isPresent) {
                resolveGpuNanos = ((end.asLong - begin.asLong) * RenderSystem.getDevice().deviceInfo.timestampPeriod()).toLong()
                pendingQueries[queryIndex] = false
            }
        }
        val measure = queries != null && !pendingQueries[queryIndex]
        if (measure) encoder.writeTimestamp(queries, queryIndex * 2)
        pending = true
        pendingSamples = samples
        pendingQuery = if (measure) queryIndex else -1
        MemoryStack.stackPush().use { stack ->
            val data = stack.malloc(112)
            reproject.get(0, data)
            data.position(64)
            data.putFloat((current.x / target.width).toFloat()).putFloat((current.y / target.height).toFloat())
            data.putFloat((previous.x / target.width).toFloat()).putFloat((previous.y / target.height).toFloat())
            val weight = if (historyFrames == 0) 0f else min(0.94f, 1f - 1f / min(historyFrames + 1, samples))
            data.putFloat(weight).putFloat(if (RenderSystem.getDevice().deviceInfo.isZZeroToOne) 1f else 0f)
            data.putFloat(sharpness).putFloat(0f)
            data.putFloat(1f / target.width).putFloat(1f / target.height).putFloat(target.width.toFloat()).putFloat(target.height.toFloat())
            data.flip()
            encoder.writeToBuffer(ring.currentBuffer().slice(), data)
        }
        val linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        val nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        encoder.createRenderPass({ "aamod TAA resolve" }, checkNotNull(output.colorTextureView), Optional.empty()).use { pass ->
            pass.setPipeline(RESOLVE)
            pass.setUniform("TemporalConfig", ring.currentBuffer())
            pass.bindTexture("CurrentSampler", checkNotNull(target.colorTextureView), linear)
            pass.bindTexture("DepthSampler", checkNotNull(target.depthTextureView), nearest)
            pass.bindTexture("HistorySampler", checkNotNull(prior.colorTextureView), linear)
            pass.bindTexture("HistoryDepthSampler", checkNotNull(prior.depthTextureView), nearest)
            pass.bindTexture("PreviousSampler", checkNotNull(raw.colorTextureView), linear)
            pass.bindTexture("MotionSampler", checkNotNull(motion.colorTextureView), nearest)
            pass.draw(3, 1, 0, 0)
        }
        output.copyDepthFrom(target)
        encoder.copyTextureToTexture(checkNotNull(target.colorTexture), checkNotNull(rawColors[writeIndex].colorTexture), 0, 0, 0, 0, 0, target.width, target.height)
        return output
    }

    fun present(target: RenderTarget, source: RenderTarget) {
        check(pending) { "TAA output requires a prepared transaction" }
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val ring = checkNotNull(uniforms)
        val nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        encoder.createRenderPass({ "aamod TAA output" }, checkNotNull(target.colorTextureView), Optional.empty()).use { pass ->
            pass.setPipeline(OUTPUT)
            pass.setUniform("TemporalConfig", ring.currentBuffer())
            pass.bindTexture("InSampler", checkNotNull(source.colorTextureView), nearest)
            pass.draw(3, 1, 0, 0)
        }
    }

    fun commit() {
        check(pending) { "No TAA history transaction to commit" }
        if (pendingQuery >= 0) {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(checkNotNull(timestamps), pendingQuery * 2 + 1)
            pendingQueries[pendingQuery] = true
            nextQuery = (pendingQuery + 1) % pendingQueries.size
        }
        checkNotNull(uniforms).rotate()
        writeIndex = 1 - writeIndex
        historyFrames = min(historyFrames + 1, pendingSamples)
        resolveCpuNanos = System.nanoTime() - started
        pending = false
        pendingQuery = -1
    }

    fun abort() {
        if (pending) uniforms?.rotate()
        pending = false
        pendingQuery = -1
    }

    private fun allocate(width: Int, height: Int) {
        if (history.firstOrNull()?.let { it.width == width && it.height == height } == true) return
        close()
        val device = RenderSystem.getDevice()
        check(device.precompilePipeline(RESOLVE).isValid) { "Could not compile TAA resolve" }
        check(device.precompilePipeline(OUTPUT).isValid) { "Could not compile TAA output" }
        try {
            repeat(2) { index ->
                val target = object : RenderTarget("aamod TAA history $index", true, GpuFormat.RGBA16_FLOAT) {}
                history.add(target)
                target.createBuffers(width, height)
                device.createCommandEncoder().clearColorAndDepthTextures(checkNotNull(target.colorTexture), Vector4f(), checkNotNull(target.depthTexture), 0.0)
            }
            repeat(2) { index ->
                val raw = object : RenderTarget("aamod TAA raw color $index", false, GpuFormat.RGBA8_UNORM) {}
                rawColors.add(raw)
                raw.createBuffers(width, height)
                device.createCommandEncoder().clearColorTexture(checkNotNull(raw.colorTexture), Vector4f())
            }
            uniforms = MappableRingBuffer({ "aamod TAA uniforms" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_MAP_WRITE, 112)
            if (device.deviceInfo.timestampPeriod() > 0f) {
                timestamps = runCatching { device.createTimestampQueryPool(pendingQueries.size * 2) }.getOrNull()
            }
        } catch (exception: Exception) {
            close()
            throw exception
        }
    }

    override fun close() {
        abort()
        history.forEach { it.destroyBuffers() }
        history.clear()
        rawColors.forEach { it.destroyBuffers() }
        rawColors.clear()
        uniforms?.close()
        uniforms = null
        timestamps?.close()
        timestamps = null
        pendingQueries.fill(false)
        nextQuery = 0
        resolveGpuNanos = -1L
        writeIndex = 0
        historyFrames = 0
    }

    companion object {
        private val RESOLVE = RenderPipeline.builder()
            .withLocation(AAMod.id("taa/resolve"))
            .withVertexShader(AAMod.id("post/taa_fullscreen"))
            .withFragmentShader(AAMod.id("post/taa_resolve"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .withBindGroupLayout(BindGroupLayout.builder()
                .withSampler("CurrentSampler").withSampler("DepthSampler").withSampler("HistorySampler")
                .withSampler("HistoryDepthSampler").withSampler("PreviousSampler").withSampler("MotionSampler")
                .withUniform("TemporalConfig", UniformType.UNIFORM_BUFFER).build())
            .build()
        private val OUTPUT = RenderPipeline.builder()
            .withLocation(AAMod.id("taa/output"))
            .withVertexShader(AAMod.id("post/taa_fullscreen")).withFragmentShader(AAMod.id("post/taa_sharpen"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
            .withDepthStencilState(Optional.empty()).withColorTargetState(ColorTargetState.DEFAULT)
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("InSampler")
                .withUniform("TemporalConfig", UniformType.UNIFORM_BUFFER).build()).build()
    }
}
