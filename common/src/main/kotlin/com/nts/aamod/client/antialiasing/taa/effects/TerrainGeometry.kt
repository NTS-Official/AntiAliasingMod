package com.nts.aamod.client.antialiasing.taa.effects

import java.nio.ByteBuffer
import java.nio.ByteOrder

class TerrainGeometry private constructor(private val vertices: FloatArray, private val colors: IntArray) {
    val faces: Int get() = colors.size / 4
    val bytes: Long get() = vertices.size.toLong() * 4 + colors.size.toLong() * 4

    fun positions(face: Int): FloatArray = FloatArray(12) { component ->
        vertices[(face * 4 + component / 3) * 5 + component % 3]
    }

    fun uv(face: Int): FloatArray = FloatArray(8) { component ->
        vertices[(face * 4 + component / 2) * 5 + 3 + component % 2]
    }

    fun color(face: Int): Int = colors[face * 4]
    fun alphas(face: Int): FloatArray = FloatArray(4) { (colors[face * 4 + it] ushr 24) / 255f }

    companion object {
        const val STRIDE = 28
        const val MAX_VERTICES = 131072

        fun read(source: ByteBuffer, stride: Int): TerrainGeometry? {
            if (stride != STRIDE || source.remaining() == 0 || source.remaining() % (stride * 4) != 0) return null
            val count = source.remaining() / stride
            if (count > MAX_VERTICES) return null
            val input = source.duplicate().order(ByteOrder.nativeOrder())
            val base = input.position()
            val vertices = FloatArray(count * 5)
            val colors = IntArray(count)
            repeat(count) { vertex ->
                val offset = base + vertex * stride
                repeat(3) { component -> vertices[vertex * 5 + component] = input.getFloat(offset + component * 4) }
                vertices[vertex * 5 + 3] = input.getFloat(offset + 16)
                vertices[vertex * 5 + 4] = input.getFloat(offset + 20)
                val red = input.get(offset + 12).toInt() and 255
                val green = input.get(offset + 13).toInt() and 255
                val blue = input.get(offset + 14).toInt() and 255
                val alpha = input.get(offset + 15).toInt() and 255
                colors[vertex] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
            return if (vertices.all { it.isFinite() }) TerrainGeometry(vertices, colors) else null
        }
    }
}
