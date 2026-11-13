package com.nts.aamod.client.compat.sodium

import com.nts.aamod.client.antialiasing.taa.effects.TerrainGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SodiumTerrainGeometry private constructor(
    val geometry: TerrainGeometry, private val biases: ByteArray, private val directions: IntArray
) {
    val bytes: Long get() = geometry.bytes + biases.size + directions.size.toLong() * 4
    fun direction(face: Int): Int = directions[face]

    fun uv(face: Int, shrinkU: Float, shrinkV: Float): FloatArray = geometry.uv(face).also { uv ->
        repeat(8) { component -> uv[component] += biases[face * 8 + component] * if (component % 2 == 0) shrinkU else shrinkV }
    }

    companion object {
        const val STRIDE = 20

        fun read(source: ByteBuffer, segments: IntArray, section: Int): SodiumTerrainGeometry? {
            if (source.remaining() == 0 || source.remaining() % (STRIDE * 4) != 0 || segments.size % 2 != 0) return null
            val count = source.remaining() / STRIDE
            if (count > TerrainGeometry.MAX_VERTICES || section !in 0..255) return null
            val directions = IntArray(count / 4)
            var face = 0
            for (index in segments.indices step 2) {
                val vertices = segments[index]
                if (vertices == 0) continue
                val direction = segments[index + 1]
                if (vertices < 0 || vertices % 4 != 0 || direction !in 0..6 || vertices / 4 > directions.size - face) return null
                repeat(vertices / 4) { directions[face++] = direction }
            }
            if (face != directions.size) return null
            val input = source.duplicate().order(ByteOrder.nativeOrder())
            val converted = ByteBuffer.allocate(count * TerrainGeometry.STRIDE).order(ByteOrder.nativeOrder())
            val biases = ByteArray(count * 2)
            repeat(count) { vertex ->
                val offset = input.position() + vertex * STRIDE
                if (input.get(offset + 19).toInt() and 255 != section) return null
                val hi = input.getInt(offset)
                val lo = input.getInt(offset + 4)
                repeat(3) { component ->
                    val packed = ((hi ushr (component * 10) and 1023) shl 10) or (lo ushr (component * 10) and 1023)
                    converted.putFloat(packed * (32f / 1048576f) - 8f)
                }
                repeat(4) { converted.put(input.get(offset + 8 + it)) }
                repeat(2) { component ->
                    val packed = input.getShort(offset + 12 + component * 2).toInt() and 65535
                    converted.putFloat((packed and 32767) / 32768f)
                    biases[vertex * 2 + component] = if (packed and 32768 == 0) -1 else 1
                }
                converted.putInt(0)
            }
            converted.flip()
            val geometry = TerrainGeometry.read(converted, TerrainGeometry.STRIDE) ?: return null
            return SodiumTerrainGeometry(geometry, biases, directions)
        }
    }
}
