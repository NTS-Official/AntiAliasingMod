package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.vertex.VertexConsumer

class EffectStripConsumer(
    private val delegate: VertexConsumer,
    private val consume: (Int, FloatArray, FloatArray, IntArray) -> Unit
) : VertexConsumer {
    private data class Vertex(val position: FloatArray, val uv: FloatArray = FloatArray(2), var color: Int = -1)
    private val previous = ArrayDeque<Vertex>()
    private var current: Vertex? = null
    private var segment = 0

    private fun flush() {
        val vertex = current ?: return
        previous.addLast(vertex)
        if (previous.size == 4) {
            val strip = previous.toList()
            val corners = listOf(strip[0], strip[1], strip[3], strip[2])
            consume(segment++, FloatArray(12) { corners[it / 3].position[it % 3] },
                FloatArray(8) { corners[it / 2].uv[it % 2] }, IntArray(4) { corners[it].color })
            previous.removeFirst()
            previous.removeFirst()
        }
        current = null
    }

    fun finish() { flush(); previous.clear() }

    override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
        flush()
        current = Vertex(floatArrayOf(x, y, z))
        delegate.addVertex(x, y, z)
        return this
    }

    override fun setUv(u: Float, v: Float): VertexConsumer {
        checkNotNull(current).uv[0] = u
        checkNotNull(current).uv[1] = v
        delegate.setUv(u, v)
        return this
    }

    override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
        checkNotNull(current).color = ((alpha and 255) shl 24) or ((red and 255) shl 16) or ((green and 255) shl 8) or (blue and 255)
        delegate.setColor(red, green, blue, alpha)
        return this
    }

    override fun setColor(color: Int): VertexConsumer { checkNotNull(current).color = color; delegate.setColor(color); return this }
    override fun setUv1(u: Int, v: Int): VertexConsumer { delegate.setUv1(u, v); return this }
    override fun setUv2(u: Int, v: Int): VertexConsumer { delegate.setUv2(u, v); return this }
    override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer { delegate.setNormal(x, y, z); return this }
    override fun setLineWidth(width: Float): VertexConsumer { delegate.setLineWidth(width); return this }
}
