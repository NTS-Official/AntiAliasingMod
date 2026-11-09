package com.nts.aamod.client.antialiasing.taa.motion

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.rendertype.RenderType

internal class MotionVertexConsumer(private val delegate: VertexConsumer, private val type: RenderType) : VertexConsumer {
    private val vertex = FloatArray(6)
    private var section: MotionSection? = null

    fun flush() {
        section?.let { MotionCapture.vertex(it, type, vertex) }
        section = null
    }

    override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
        flush()
        delegate.addVertex(x, y, z)
        vertex[0] = x
        vertex[1] = y
        vertex[2] = z
        vertex[3] = 0f
        vertex[4] = 0f
        vertex[5] = 1f
        section = MotionCapture.section(this, type)
        if (section != null) MotionCapture.touch(this)
        return this
    }

    override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
        delegate.setColor(red, green, blue, alpha)
        vertex[5] = alpha / 255f
        return this
    }

    override fun setColor(color: Int): VertexConsumer {
        delegate.setColor(color)
        vertex[5] = (color ushr 24) / 255f
        return this
    }

    override fun setUv(u: Float, v: Float): VertexConsumer {
        delegate.setUv(u, v)
        vertex[3] = u
        vertex[4] = v
        return this
    }

    override fun setUv1(u: Int, v: Int): VertexConsumer { delegate.setUv1(u, v); return this }
    override fun setUv2(u: Int, v: Int): VertexConsumer { delegate.setUv2(u, v); return this }
    override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer { delegate.setNormal(x, y, z); return this }
    override fun setLineWidth(width: Float): VertexConsumer { delegate.setLineWidth(width); return this }
}
