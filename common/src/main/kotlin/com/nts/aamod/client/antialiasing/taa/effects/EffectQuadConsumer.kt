package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.vertex.VertexConsumer
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture

class EffectQuadConsumer(
    private val delegate: VertexConsumer,
    private val consume: (Int, FloatArray, FloatArray, IntArray) -> Unit
) : VertexConsumer {
    init { MotionCapture.markEffectsCaptured(delegate) }

    private var position = FloatArray(12)
    private var uv = FloatArray(8)
    private var colors = IntArray(4)
    private var vertex = -1
    private var quad = 0

    fun finish() {
        if (vertex == 3) consume(quad++, position, uv, colors)
        position = FloatArray(12)
        uv = FloatArray(8)
        colors = IntArray(4)
        vertex = -1
    }

    override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
        if (vertex == 3) {
            finish()
        }
        vertex++
        position[vertex * 3] = x
        position[vertex * 3 + 1] = y
        position[vertex * 3 + 2] = z
        colors[vertex] = -1
        delegate.addVertex(x, y, z)
        return this
    }

    override fun setUv(u: Float, v: Float): VertexConsumer {
        uv[vertex * 2] = u
        uv[vertex * 2 + 1] = v
        delegate.setUv(u, v)
        return this
    }

    override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
        colors[vertex] = ((alpha and 255) shl 24) or ((red and 255) shl 16) or ((green and 255) shl 8) or (blue and 255)
        delegate.setColor(red, green, blue, alpha)
        return this
    }

    override fun setColor(color: Int): VertexConsumer {
        colors[vertex] = color
        delegate.setColor(color)
        return this
    }

    override fun setUv1(u: Int, v: Int): VertexConsumer { delegate.setUv1(u, v); return this }
    override fun setUv2(u: Int, v: Int): VertexConsumer { delegate.setUv2(u, v); return this }
    override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer { delegate.setNormal(x, y, z); return this }
    override fun setLineWidth(width: Float): VertexConsumer { delegate.setLineWidth(width); return this }
}
