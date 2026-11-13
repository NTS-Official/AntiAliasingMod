package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.MappableRingBuffer
import java.nio.ByteBuffer

class TemporalUploadArena(private val name: String, private val usage: Int, private val pageSize: Int) : AutoCloseable {
    private val pages = mutableListOf<MappableRingBuffer>()
    private val used = linkedSetOf<Int>()
    private var page = 0
    private var offset = 0
    private var active = false
    val allocatedBytes: Long get() = pages.sumOf { it.size().toLong() * 3 }

    init { require(pageSize > 0) }

    fun beginFrame() {
        endFrame()
        page = 0
        offset = 0
        active = true
    }

    private fun location(size: Int, alignment: Int): Pair<Int, Int> {
        require(size > 0 && alignment > 0)
        var candidate = page
        var start = Math.multiplyExact((Math.addExact(offset, alignment - 1)) / alignment, alignment)
        while (candidate < pages.size && size > pages[candidate].size() - start) {
            if (candidate !in used) return candidate to 0
            candidate++
            start = 0
        }
        return candidate to start
    }

    fun additionalBytes(size: Int, alignment: Int): Long {
        val (candidate, _) = location(size, alignment)
        val existing = pages.getOrNull(candidate)?.size() ?: 0
        return if (existing >= size) 0L else (capacity(size).toLong() - existing) * 3
    }

    private fun capacity(size: Int): Int {
        var capacity = pageSize
        while (capacity < size && capacity <= 32 * 1024 * 1024) capacity = Math.multiplyExact(capacity, 2)
        return maxOf(capacity, size)
    }

    fun upload(data: ByteBuffer, alignment: Int): GpuBufferSlice {
        if (!active) beginFrame()
        val size = data.remaining()
        val (candidate, start) = location(size, alignment)
        val existing = pages.getOrNull(candidate)
        if (existing == null || existing.size() < size) {
            val capacity = capacity(size)
            val bytes = allocatedBytes + (capacity.toLong() - (existing?.size() ?: 0)) * 3
            check((existing != null || pages.size < 512) && bytes <= 192L * 1024 * 1024) { "Temporal upload budget exhausted" }
            val replacement = MappableRingBuffer({ "$name page $candidate" }, usage or GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_DST, capacity)
            if (existing == null) pages.add(replacement)
            else {
                pages[candidate] = replacement
                existing.close()
            }
        }
        page = candidate
        offset = Math.addExact(start, size)
        used.add(candidate)
        val slice = pages[candidate].currentBuffer().slice(start.toLong(), size.toLong())
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(slice, data)
        return slice
    }

    fun endFrame() {
        if (!active) return
        used.forEach { pages[it].rotate() }
        used.clear()
        active = false
    }

    override fun close() {
        used.clear()
        pages.forEach { it.close() }
        pages.clear()
        page = 0
        offset = 0
        active = false
    }
}
