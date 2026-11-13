package com.nts.aamod.client.antialiasing.taa.effects

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
import com.nts.aamod.AAMod
import com.nts.aamod.client.antialiasing.taa.JitterPoint
import com.nts.aamod.client.antialiasing.taa.composition.ContributionIntervalKey
import org.joml.Vector4f
import org.lwjgl.system.MemoryStack
import java.util.Optional
import kotlin.math.min

internal class EffectHistorySlot(name: String, width: Int, height: Int) : AutoCloseable {
    val color = object : RenderTarget("$name color", true, GpuFormat.RGBA16_FLOAT) {}
    val raw = object : RenderTarget("$name raw", false, GpuFormat.RGBA8_UNORM) {}
    val identity = object : RenderTarget("$name identity", false, GpuFormat.RGBA8_UNORM) {}

    init {
        try {
            for (target in listOf(color, raw, identity)) target.createBuffers(width, height)
            val encoder = RenderSystem.getDevice().createCommandEncoder()
            encoder.clearColorAndDepthTextures(checkNotNull(color.colorTexture), Vector4f(0f), checkNotNull(color.depthTexture), 0.0)
            encoder.clearColorTexture(checkNotNull(raw.colorTexture), Vector4f(0f))
            encoder.clearColorTexture(checkNotNull(identity.colorTexture), Vector4f(0f))
        } catch (exception: Exception) {
            close()
            throw exception
        }
    }

    override fun close() {
        color.destroyBuffers()
        raw.destroyBuffers()
        identity.destroyBuffers()
    }
}

internal class EffectLayerHistory(name: String, val width: Int, val height: Int) : AutoCloseable {
    val slots = mutableListOf<EffectHistorySlot>()
    var writeIndex = 0
    var frames = 0
    var lastUsed = -1L
    var pending = false
    val bytes: Long get() = width.toLong() * height * 40

    init {
        try { repeat(2) { slots.add(EffectHistorySlot("$name $it", width, height)) } }
        catch (exception: Exception) { close(); throw exception }
    }

    override fun close() {
        slots.forEach { it.close() }
        slots.clear()
        frames = 0
        pending = false
    }
}

data class EffectHistoryKey(val domain: EffectDomain, val interval: ContributionIntervalKey? = null)

class EffectLayerHistoryPool : AutoCloseable {
    private val layers = linkedMapOf<EffectHistoryKey, EffectLayerHistory>()
    private val requested = linkedSetOf<EffectHistoryKey>()
    private val budgetRejected = linkedSetOf<EffectHistoryKey>()
    private var reserved = emptySet<EffectHistoryKey>()
    val telemetry = EffectHistoryTelemetry()
    private val uniforms = TemporalUploadArena("aamod effect history", GpuBuffer.USAGE_UNIFORM, 4096)
    private var frame = -1L
    private var sampleCount = 16
    val allocatedBytes: Long get() = layers.values.sumOf { it.bytes } + uniforms.allocatedBytes + telemetry.allocatedBytes
    private var availableBytes = 0L
    private var width = 0
    private var height = 0
    val residentDomains: Set<EffectDomain> get() = layers.keys.map { it.domain }.toSet()
    val historicalDomains: Set<EffectDomain> get() = layers.filterValues { it.frames > 0 }.keys.map { it.domain }.toSet()
    val budgetRejectedDomains: Set<EffectDomain> get() = budgetRejected.map { it.domain }.toSet()

    fun rejectBudget(key: EffectHistoryKey) {
        requested.add(key)
        budgetRejected.add(key)
        resetHistory(key)
    }

    fun beginFrame(frameId: Long, width: Int, height: Int, availableBytes: Long) {
        require(frameId >= 0 && width > 0 && height > 0 && availableBytes >= 0)
        check(layers.values.none { it.pending }) { "Previous effects transaction is still pending" }
        budgetRejected.clear()
        frame = frameId
        telemetry.poll()
        uniforms.beginFrame()
        this.width = width
        this.height = height
        this.availableBytes = availableBytes
        layers.filterValues { it.width != width || it.height != height || frameId - it.lastUsed > 16 }
            .keys.toList().forEach { layers.remove(it)?.close() }
        val layerBytes = width.toLong() * height * 40
        val bufferBytes = uniforms.additionalBytes(48, RenderSystem.getDevice().deviceInfo.limits().minUniformOffsetAlignment())
        val capacity = ((availableBytes - uniforms.allocatedBytes - telemetry.allocatedBytes - bufferBytes).coerceAtLeast(0) / layerBytes)
            .coerceAtMost(128).toInt()
        reserved = EffectHistoryPriority.select(requested + layers.keys, layers.keys, capacity)
        requested.clear()
        layers.keys.filter { it !in reserved }.forEach { layers.remove(it)?.close() }
        telemetry.beginFrame(layers.filterValues { it.lastUsed == frameId - 1 }.keys)
    }

    private fun acquire(key: EffectHistoryKey): EffectLayerHistory? {
        requested.add(key)
        layers[key]?.let { return it }
        val required = Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), 40L)
        val bufferBytes = uniforms.additionalBytes(48, RenderSystem.getDevice().deviceInfo.limits().minUniformOffsetAlignment())
        val reservations = reserved.count { it !in layers && it != key }.toLong() * required
        if (layers.size >= 128 || required > availableBytes - allocatedBytes - bufferBytes - reservations) {
            budgetRejected.add(key)
            return null
        }
        val device = RenderSystem.getDevice()
        check(device.precompilePipeline(RESOLVE).isValid) { "Could not compile effect history resolve" }
        check(device.precompilePipeline(HISTORY_IDENTITY).isValid) { "Could not compile effect history validity" }
        return EffectLayerHistory("aamod $key history", width, height).also { layers[key] = it }
    }

    fun reserveHistory(key: EffectHistoryKey, availableBytes: Long): Boolean {
        require(availableBytes >= 0)
        val layerBytes = width.toLong() * height * 40
        val bufferBytes = uniforms.additionalBytes(48, RenderSystem.getDevice().deviceInfo.limits().minUniformOffsetAlignment())
        val capacity = ((availableBytes - uniforms.allocatedBytes - telemetry.allocatedBytes - bufferBytes).coerceAtLeast(0) / layerBytes)
            .coerceAtMost(128).toInt()
        val selected = EffectHistoryPriority.select(requested + reserved + layers.keys + key, layers.keys, capacity)
        val retired = layers.keys.filter { it !in selected }
        if (key !in selected || retired.any { layers.getValue(it).pending }) {
            rejectBudget(key)
            return false
        }
        retired.forEach { layers.remove(it)?.close() }
        this.availableBytes = availableBytes
        reserved = selected
        return acquire(key) != null
    }

    fun limitBudget(availableBytes: Long) {
        require(availableBytes >= 0)
        this.availableBytes = availableBytes
        EffectHistoryPriority.select(layers.keys, layers.keys, layers.size).toList().asReversed().forEach { key ->
            if (allocatedBytes > availableBytes && layers[key]?.pending == false) layers.remove(key)?.close()
        }
        val layerBytes = width.toLong() * height * 40
        val bufferBytes = uniforms.additionalBytes(48, RenderSystem.getDevice().deviceInfo.limits().minUniformOffsetAlignment())
        val missingCapacity = ((availableBytes - allocatedBytes - bufferBytes).coerceAtLeast(0) / layerBytes).coerceAtMost(128).toInt()
        reserved = layers.keys + EffectHistoryPriority.select(reserved - layers.keys, emptySet(), missingCapacity)
    }

    fun prepare(
        key: EffectHistoryKey, source: RenderTarget, motion: RenderTarget, identity: RenderTarget, conflict: RenderTarget,
        current: JitterPoint, previous: JitterPoint, samples: Int
    ): RenderTarget? {
        val layer = acquire(key) ?: return null
        check(!layer.pending) { "Effect interval prepared twice: $key" }
        check(source.width == layer.width && source.height == layer.height && source.colorTexture?.format == GpuFormat.RGBA8_UNORM)
        sampleCount = samples
        val output = layer.slots[layer.writeIndex]
        val prior = layer.slots[1 - layer.writeIndex]
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val consecutive = layer.lastUsed == frame - 1
        val config = MemoryStack.stackPush().use { stack ->
            val data = stack.malloc(48)
            data.putFloat((current.x / source.width).toFloat()).putFloat((current.y / source.height).toFloat())
                .putFloat((previous.x / source.width).toFloat()).putFloat((previous.y / source.height).toFloat())
            val weight = if (!consecutive || layer.frames == 0) 0f else min(0.94f, 1f - 1f / min(layer.frames + 1, samples))
            data.putFloat(weight).putFloat(0f).putFloat(0f).putFloat(0f)
            data.putFloat(1f / source.width).putFloat(1f / source.height).putFloat(source.width.toFloat()).putFloat(source.height.toFloat())
            data.flip()
            uniforms.upload(data, RenderSystem.getDevice().deviceInfo.limits().minUniformOffsetAlignment())
        }
        val nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        val linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        fun drawResolve(destination: RenderTarget, pipeline: RenderPipeline) {
            encoder.createRenderPass({ "aamod ${key.domain.name.lowercase()} history resolve" }, checkNotNull(destination.colorTextureView), Optional.empty()).use { pass ->
                pass.setPipeline(pipeline)
                pass.setUniform("EffectConfig", config)
                pass.bindTexture("CurrentSampler", checkNotNull(source.colorTextureView), linear)
                pass.bindTexture("DepthSampler", checkNotNull(motion.depthTextureView), nearest)
                pass.bindTexture("MotionSampler", checkNotNull(motion.colorTextureView), nearest)
                pass.bindTexture("IdentitySampler", checkNotNull(identity.colorTextureView), nearest)
                pass.bindTexture("ConflictSampler", checkNotNull(conflict.colorTextureView), nearest)
                if (pipeline === RESOLVE) pass.bindTexture("HistorySampler", checkNotNull(prior.color.colorTextureView), nearest)
                pass.bindTexture("HistoryDepthSampler", checkNotNull(prior.color.depthTextureView), nearest)
                pass.bindTexture("HistoryIdentitySampler", checkNotNull(prior.identity.colorTextureView), nearest)
                pass.bindTexture("PreviousSampler", checkNotNull(prior.raw.colorTextureView), nearest)
                pass.draw(3, 1, 0, 0)
            }
        }
        drawResolve(output.color, RESOLVE)
        val reservations = reserved.count { it !in layers }.toLong() * layer.bytes
        telemetry.sample(frame, key, source.width, source.height, (availableBytes - allocatedBytes - reservations).coerceAtLeast(0)) { target ->
            check(RenderSystem.getDevice().precompilePipeline(WEIGHTS).isValid) { "Could not compile effect weights telemetry" }
            drawResolve(target, WEIGHTS)
        }
        output.color.copyDepthFrom(motion)
        encoder.copyTextureToTexture(checkNotNull(source.colorTexture), checkNotNull(output.raw.colorTexture), 0, 0, 0, 0, 0, source.width, source.height)
        encoder.createRenderPass({ "aamod effect history validity" }, checkNotNull(output.identity.colorTextureView), Optional.empty()).use { pass ->
            pass.setPipeline(HISTORY_IDENTITY)
            pass.bindTexture("IdentitySampler", checkNotNull(identity.colorTextureView), nearest)
            pass.bindTexture("ConflictSampler", checkNotNull(conflict.colorTextureView), nearest)
            pass.draw(3, 1, 0, 0)
        }
        layer.pending = true
        return output.color
    }

    fun commit() {
        telemetry.commit(frame)
        for (layer in layers.values.filter { it.pending }) {
            layer.writeIndex = 1 - layer.writeIndex
            layer.frames = if (layer.lastUsed == frame - 1) min(layer.frames + 1, sampleCount) else 1
            layer.lastUsed = frame
            layer.pending = false
        }
    }

    fun abort() { layers.values.forEach { it.pending = false } }

    fun endFrame() = uniforms.endFrame()

    fun resetHistory(key: EffectHistoryKey) {
        layers[key]?.let { it.frames = 0; it.lastUsed = -1L; it.pending = false }
        telemetry.reject(key)
    }

    fun resetHistory() {
        abort()
        budgetRejected.clear()
        telemetry.resetHistory()
        layers.values.forEach { it.frames = 0; it.lastUsed = -1L }
    }

    override fun close() {
        layers.values.forEach { it.close() }
        layers.clear()
        requested.clear()
        budgetRejected.clear()
        reserved = emptySet()
        uniforms.close()
        telemetry.close()
        frame = -1L
        availableBytes = 0L
        width = 0
        height = 0
    }

    companion object {
        private val HISTORY_IDENTITY = RenderPipeline.builder().withLocation(AAMod.id("taa/effect_history_identity"))
            .withVertexShader(AAMod.id("post/taa_fullscreen")).withFragmentShader(AAMod.id("post/taa_effect_history_identity"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("IdentitySampler").withSampler("ConflictSampler").build())
            .build()

        private fun pipeline(weights: Boolean): RenderPipeline {
            val bindings = BindGroupLayout.builder()
                .withSampler("CurrentSampler").withSampler("DepthSampler").withSampler("MotionSampler")
                .withSampler("IdentitySampler").withSampler("ConflictSampler")
                .withSampler("HistoryDepthSampler").withSampler("HistoryIdentitySampler").withSampler("PreviousSampler")
                .withUniform("EffectConfig", UniformType.UNIFORM_BUFFER)
            if (!weights) bindings.withSampler("HistorySampler")
            val builder = RenderPipeline.builder()
            .withLocation(AAMod.id(if (weights) "taa/effect_weights" else "taa/effect_resolve"))
            .withVertexShader(AAMod.id("post/taa_fullscreen")).withFragmentShader(AAMod.id("post/taa_effect_resolve"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(ColorTargetState(Optional.empty(), if (weights) GpuFormat.RGBA8_UNORM else GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .withBindGroupLayout(bindings.build())
            if (weights) builder.withShaderDefine("EFFECT_WEIGHTS")
            return builder.build()
        }
        private val RESOLVE = pipeline(false)
        private val WEIGHTS = pipeline(true)
    }
}
