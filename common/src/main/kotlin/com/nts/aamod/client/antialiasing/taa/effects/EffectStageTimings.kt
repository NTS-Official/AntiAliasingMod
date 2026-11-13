package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.systems.GpuQueryPool
import com.mojang.blaze3d.systems.RenderSystem
import java.util.Locale

enum class EffectStage { REPLAY, MOTION, RESOLVE, COMPOSE }

/** Measures only work submitted by the effects compositor, excluding native draws between intervals. */
class EffectStageTimings : AutoCloseable {
    private class Frame {
        val stages = arrayOfNulls<EffectStage>(MAX_SPANS)
        var count = 0
        var pending = false
        var complete = true
        var frame = -1L
    }

    private val frames = Array(FRAME_SLOTS) { Frame() }
    private val cpu = LongArray(EffectStage.entries.size)
    private val gpu = LongArray(EffectStage.entries.size)
    private var queries: GpuQueryPool? = null
    private var unavailable = false
    private var slot = -1
    private var nextSlot = 0
    private var frameId = -1L
    var sampledFrame = -1L
        private set
    var sampleComplete = false
        private set
    val allocatedBytes: Long get() = if (queries == null) 0L else QUERY_BYTES
    val cpuNanos: Map<EffectStage, Long> get() = EffectStage.entries.associateWith { cpu[it.ordinal] }
    val gpuNanos: Map<EffectStage, Long> get() = EffectStage.entries.associateWith { gpu[it.ordinal] }
    val diagnostic: String get() {
        val cpuText = cpu.sum() / 1e6
        if (sampledFrame < 0) return String.format(Locale.ROOT, "CPU submit=%.3f ms | GPU=%s", cpuText,
            if (unavailable) "UNAVAILABLE" else "PENDING")
        return String.format(Locale.ROOT,
            "CPU submit=%.3f ms | GPU own sum=%.3f ms [replay=%.3f motion=%.3f resolve=%.3f compose=%.3f] | frame=%d%s",
            cpuText, gpu.sum() / 1e6, gpu[0] / 1e6, gpu[1] / 1e6, gpu[2] / 1e6, gpu[3] / 1e6,
            sampledFrame, if (sampleComplete) "" else " | INCOMPLETE")
    }

    fun beginFrame(frame: Long) {
        endFrame()
        cpu.fill(0)
        frameId = frame
        poll()
    }

    fun reserve(availableBytes: Long) {
        if (queries != null || unavailable || availableBytes < QUERY_BYTES) return
        val device = RenderSystem.getDevice()
        if (device.deviceInfo.timestampPeriod() <= 0f) { unavailable = true; return }
        try { queries = device.createTimestampQueryPool(FRAME_SLOTS * MAX_SPANS * 2) }
        catch (_: Exception) { unavailable = true }
    }

    private fun poll() {
        val pool = queries ?: return
        try {
            for ((index, frame) in frames.withIndex()) {
                if (!frame.pending) continue
                val totals = LongArray(gpu.size)
                var ready = true
                for (span in 0 until frame.count) {
                    val query = (index * MAX_SPANS + span) * 2
                    val begin = pool.getValue(query)
                    val end = pool.getValue(query + 1)
                    if (begin.isEmpty || end.isEmpty) { ready = false; break }
                    val nanos = ((end.asLong - begin.asLong) * RenderSystem.getDevice().deviceInfo.timestampPeriod()).toLong()
                    if (nanos < 0) frame.complete = false
                    else totals[checkNotNull(frame.stages[span]).ordinal] += nanos
                }
                if (!ready) continue
                if (frame.frame > sampledFrame) {
                    totals.copyInto(gpu)
                    sampledFrame = frame.frame
                    sampleComplete = frame.complete
                }
                frame.pending = false
            }
        } catch (_: Exception) { disableGpu() }
    }

    fun <Result> measure(stage: EffectStage, action: () -> Result): Result {
        val started = System.nanoTime()
        val query = beginSpan(stage)
        try { return action() }
        finally {
            cpu[stage.ordinal] += System.nanoTime() - started
            if (query >= 0) try {
                queries?.let { RenderSystem.getDevice().createCommandEncoder().writeTimestamp(it, query + 1) }
            } catch (_: Exception) { disableGpu() }
        }
    }

    private fun beginSpan(stage: EffectStage): Int {
        if (frameId < 0 || frameId % SAMPLE_INTERVAL_FRAMES != 0L) return -1
        val pool = queries ?: return -1
        if (slot < 0) {
            val available = (0 until FRAME_SLOTS).map { (nextSlot + it) % FRAME_SLOTS }.firstOrNull { !frames[it].pending }
                ?: return -1
            slot = available
            frames[slot].apply { count = 0; complete = cpu.none { it > 0 }; frame = frameId }
        }
        val frame = frames[slot]
        if (frame.count >= MAX_SPANS) { frame.complete = false; return -1 }
        val span = frame.count
        val query = (slot * MAX_SPANS + span) * 2
        try { RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, query) }
        catch (_: Exception) { disableGpu(); return -1 }
        frame.stages[span] = stage
        frame.count++
        return query
    }

    fun endFrame() {
        if (slot >= 0) {
            frames[slot].pending = frames[slot].count > 0
            nextSlot = (slot + 1) % FRAME_SLOTS
            slot = -1
        }
    }

    private fun disableGpu() {
        queries?.close()
        queries = null
        unavailable = true
        slot = -1
        frames.forEach { it.pending = false }
        sampledFrame = -1L
        sampleComplete = false
        gpu.fill(0)
    }

    override fun close() {
        disableGpu()
        unavailable = false
        cpu.fill(0)
        nextSlot = 0
        frameId = -1L
    }

    companion object {
        private const val FRAME_SLOTS = 4
        private const val MAX_SPANS = 256
        const val SAMPLE_INTERVAL_FRAMES = 16L
        // Eight-byte query results; implementation/driver allocation overhead is outside the resource payload ledger.
        const val QUERY_BYTES = FRAME_SLOTS * MAX_SPANS * 2 * 8L
    }
}
