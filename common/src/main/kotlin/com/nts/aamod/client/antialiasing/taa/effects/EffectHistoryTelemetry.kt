package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.nts.aamod.AAMod
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class EffectHistoryTelemetry : AutoCloseable {
    private var target: RenderTarget? = null
    private var readback: GpuBuffer? = null
    private var pixels = ByteArray(0)
    private var requestedAt = 0L
    private var completed = AtomicBoolean()
    private var pending = false
    private var pendingFrame = -1L
    private var accepted = false
    private var lastAttempt = 0L
    private var sampledAt = 0L
    private var pendingKey: EffectHistoryKey? = null
    private var sampledKey: EffectHistoryKey? = null
    private var disabled = false
    private var selected: EffectHistoryKey? = null
    private val attempts = mutableMapOf<EffectHistoryKey, Long>()
    var coveredPixels = 0L
        private set
    var consumedPixels = 0L
        private set
    /** Covered pixels rejected at the resolve's overlap/conflicting-motion check in this GPU sample. */
    var mixedMotionPixels = 0L
        private set
    var motionConflictPixels = 0L
        private set
    var identityConflictPixels = 0L
        private set
    var depthConflictPixels = 0L
        private set
    var averageWeight = 0.0
        private set
    val allocatedBytes: Long get() = (target?.let { it.width.toLong() * it.height * 4 } ?: 0L) + (readback?.size() ?: 0L)
    fun sampleDiagnostic(): String {
        requestedAt = System.nanoTime()
        return when {
            disabled -> "unavailable"
            sampledAt == 0L -> "pending"
            else -> "${sampledKey?.domain} | consumed=$consumedPixels/$coveredPixels" +
                " | mixed_motion=$mixedMotionPixels" +
                " | meanWeight=${String.format(Locale.ROOT, "%.3f", averageWeight)}" +
                " | age=${(System.nanoTime() - sampledAt) / 1000000}ms"
        }
    }

    fun poll() {
        if (!pending || !completed.get()) return
        if (!accepted) { pending = false; return }
        try {
            var covered = 0L
            var consumed = 0L
            var weight = 0L
            var mixed = 0L
            var motionConflict = 0L
            var identityConflict = 0L
            var depthConflict = 0L
            checkNotNull(readback).map(true, false).use { view ->
                val data = view.data()
                if (pixels.size != data.remaining()) pixels = ByteArray(data.remaining())
                data.get(pixels)
            }
            var offset = 0
            while (offset < pixels.size) {
                if (pixels[offset].toInt() and 255 > 0) covered++
                if (pixels[offset + 1].toInt() and 255 > 0) consumed++
                weight += pixels[offset + 2].toInt() and 255
                val reasons = pixels[offset + 3].toInt() and 255
                if (reasons != 0) mixed++
                if (reasons and 1 != 0) motionConflict++
                if (reasons and 2 != 0) identityConflict++
                if (reasons and 4 != 0) depthConflict++
                offset += 4
            }
            coveredPixels = covered
            consumedPixels = consumed
            mixedMotionPixels = mixed
            motionConflictPixels = motionConflict
            identityConflictPixels = identityConflict
            depthConflictPixels = depthConflict
            averageWeight = if (covered == 0L) 0.0 else weight / (255.0 * covered)
            sampledKey = pendingKey
            sampledAt = System.nanoTime()
            pending = false
        } catch (exception: Exception) { disable(exception) }
    }

    fun beginFrame(keys: Set<EffectHistoryKey>) {
        attempts.keys.retainAll(keys)
        selected = keys.minByOrNull { attempts[it] ?: 0L }
    }

    fun sample(frame: Long, key: EffectHistoryKey, width: Int, height: Int, availableBytes: Long, draw: (RenderTarget) -> Unit) {
        val now = System.nanoTime()
        if (disabled || pending || requestedAt == 0L || now - requestedAt > 2000000000L ||
            now - lastAttempt < 1000000000L || selected?.let { it != key } == true) return
        lastAttempt = now
        attempts[key] = now
        val required = width.toLong() * height * 8
        if (required > availableBytes + allocatedBytes) return
        try {
            if (target?.let { it.width == width && it.height == height } != true) {
                release()
                target = object : RenderTarget("aamod effect history weights", false, GpuFormat.RGBA8_UNORM) {}
                checkNotNull(target).createBuffers(width, height)
                readback = RenderSystem.getDevice().createBuffer({ "aamod effect history weights readback" },
                    GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_MAP_READ, required / 2)
            }
            val output = checkNotNull(target)
            draw(output)
            completed = AtomicBoolean()
            val notification = completed
            pendingKey = key
            pendingFrame = frame
            accepted = false
            pending = true
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(checkNotNull(output.colorTexture),
                checkNotNull(readback), 0L, { notification.set(true) }, 0)
        } catch (exception: Exception) { disable(exception) }
    }

    fun commit(frame: Long) { if (pendingFrame == frame) accepted = true }

    fun reject(key: EffectHistoryKey) {
        if (pendingKey == key) {
            accepted = false
            pendingFrame = -1L
        }
        if (sampledKey == key) {
            sampledAt = 0L
            sampledKey = null
            coveredPixels = 0L
            consumedPixels = 0L
            mixedMotionPixels = 0L
            motionConflictPixels = 0L
            identityConflictPixels = 0L
            depthConflictPixels = 0L
            averageWeight = 0.0
        }
    }

    fun resetHistory() {
        accepted = false
        sampledAt = 0L
        sampledKey = null
        coveredPixels = 0L
        consumedPixels = 0L
        mixedMotionPixels = 0L
        motionConflictPixels = 0L
        identityConflictPixels = 0L
        depthConflictPixels = 0L
        averageWeight = 0.0
        selected = null
        attempts.clear()
    }

    private fun disable(exception: Exception) {
        release()
        disabled = true
        AAMod.LOGGER.warn("Effect history GPU telemetry is unavailable", exception)
    }

    private fun release() {
        target?.destroyBuffers()
        target = null
        readback?.close()
        readback = null
        pixels = ByteArray(0)
        pending = false
        pendingFrame = -1L
        accepted = false
        completed = AtomicBoolean()
    }

    override fun close() {
        release()
        sampledAt = 0L
        sampledKey = null
        pendingKey = null
        lastAttempt = 0L
        requestedAt = 0L
        coveredPixels = 0L
        consumedPixels = 0L
        mixedMotionPixels = 0L
        motionConflictPixels = 0L
        identityConflictPixels = 0L
        depthConflictPixels = 0L
        averageWeight = 0.0
        disabled = false
        selected = null
        attempts.clear()
    }
}
