package com.nts.aamod.client.antialiasing.taa.effects

import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector4f
import kotlin.math.abs

object EffectGeometry {
    fun outsideView(clip: FloatArray): Boolean {
        var outside = 31
        for (offset in clip.indices step 4) {
            val horizontal = clip[offset]
            val vertical = clip[offset + 1]
            val distance = clip[offset + 3]
            var planes = 0
            if (horizontal < -distance) planes = planes or 1
            if (horizontal > distance) planes = planes or 2
            if (vertical < -distance) planes = planes or 4
            if (vertical > distance) planes = planes or 8
            if (distance <= 0f) planes = planes or 16
            outside = outside and planes
            if (outside == 0) return false
        }
        return outside != 0
    }

    fun quad(x: Float, y: Float, z: Float, rotation: Quaternionf, scale: Float): FloatArray {
        val result = FloatArray(12)
        val local = Vector3f()
        val corners = floatArrayOf(1f, -1f, 1f, 1f, -1f, 1f, -1f, -1f)
        repeat(4) { corner ->
            local.set(corners[corner * 2], corners[corner * 2 + 1], 0f).rotate(rotation).mul(scale).add(x, y, z)
            result[corner * 3] = local.x
            result[corner * 3 + 1] = local.y
            result[corner * 3 + 2] = local.z
        }
        return result
    }

    fun project(vertices: FloatArray, matrix: Matrix4f): FloatArray {
        require(vertices.size % 3 == 0 && matrix.isFinite)
        val result = FloatArray(vertices.size / 3 * 4)
        val clip = Vector4f()
        repeat(vertices.size / 3) { vertex ->
            matrix.transform(clip.set(vertices[vertex * 3], vertices[vertex * 3 + 1], vertices[vertex * 3 + 2], 1f))
            result[vertex * 4] = clip.x
            result[vertex * 4 + 1] = clip.y
            result[vertex * 4 + 2] = clip.z
            result[vertex * 4 + 3] = clip.w
        }
        return result
    }

    fun patternCoordinates(currentUv: DoubleArray, previousUv: DoubleArray): FloatArray? {
        require(currentUv.size == 8 && previousUv.size == 8)
        if (currentUv.any { !it.isFinite() } || previousUv.any { !it.isFinite() }) return null
        val axisU = previousUv[2] - previousUv[0]
        val axisV = previousUv[3] - previousUv[1]
        val edgeU = previousUv[6] - previousUv[0]
        val edgeV = previousUv[7] - previousUv[1]
        val determinant = axisU * edgeV - axisV * edgeU
        if (abs(determinant) < 1e-12) return null
        if (abs(previousUv[4] - previousUv[2] - previousUv[6] + previousUv[0]) > 1e-6 ||
            abs(previousUv[5] - previousUv[3] - previousUv[7] + previousUv[1]) > 1e-6
        ) return null
        return FloatArray(8).also { result ->
            repeat(4) { corner ->
                val deltaU = currentUv[corner * 2] - previousUv[0]
                val deltaV = currentUv[corner * 2 + 1] - previousUv[1]
                result[corner * 2] = ((deltaU * edgeV - deltaV * edgeU) / determinant).toFloat()
                result[corner * 2 + 1] = ((axisU * deltaV - axisV * deltaU) / determinant).toFloat()
            }
        }.takeIf { result -> result.all { it.isFinite() } }
    }

    fun mapPattern(previousClip: FloatArray, coordinates: FloatArray): FloatArray {
        require(previousClip.size == 16 && coordinates.size == 8)
        return FloatArray(16) { index ->
            val corner = index / 4
            val component = index % 4
            previousClip[component] + coordinates[corner * 2] * (previousClip[4 + component] - previousClip[component]) +
                coordinates[corner * 2 + 1] * (previousClip[12 + component] - previousClip[component])
        }
    }
}
