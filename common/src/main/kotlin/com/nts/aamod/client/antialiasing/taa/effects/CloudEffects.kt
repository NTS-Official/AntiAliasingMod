package com.nts.aamod.client.antialiasing.taa.effects

import net.minecraft.client.CloudStatus
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite
import net.minecraft.world.phys.Vec3
import kotlin.math.floor
import java.nio.ByteBuffer

data class CloudFace(val x: Int, val z: Int, val direction: Int, val flags: Int)

class CloudEffects {
    private val faces = mutableListOf<CloudFace>()
    private var revision = 0L
    private var meshRejection: EffectRejection? = null
    fun beginMesh() { faces.clear(); meshRejection = null }
    fun readMesh(buffer: ByteBuffer, start: Int) {
        beginMesh()
        if (start < 0 || start > buffer.position() || (buffer.position() - start) % 3 != 0) {
            meshRejection = EffectRejection.UNRELIABLE_SOURCE
            return
        }
        var offset = start
        while (offset < buffer.position() && faces.size < 65536) {
            val flags = buffer.get(offset + 2).toInt() and 255
            val x = (buffer.get(offset).toInt() shl 1) or (flags ushr 7 and 1)
            val z = (buffer.get(offset + 1).toInt() shl 1) or (flags ushr 6 and 1)
            val direction = flags and 7
            if (direction in FACE_VERTICES.indices) faces.add(CloudFace(x, z, direction, flags and 48))
            else meshRejection = EffectRejection.UNRELIABLE_SOURCE
            offset += 3
        }
        if (offset < buffer.position()) meshRejection = EffectRejection.CAPTURE_BUDGET
    }
    fun invalidate() { revision++; faces.clear() }

    fun capture(color: Int, status: CloudStatus, bottomY: Float, camera: Vec3, time: Long, partialTick: Float, textureWidth: Int) {
        if (!TemporalEffects.active || textureWidth <= 0) return
        meshRejection?.let { TemporalEffects.rejectDomain(EffectDomain.CLOUDS, it); return }
        val wrapped = (time % (textureWidth * 400L)).toFloat() + partialTick
        val drift = wrapped * 0.030000001f
        val actualX = camera.x + drift
        val actualZ = camera.z + 3.96f
        val centerX = floor(actualX / 12.0).toLong()
        val centerZ = floor(actualZ / 12.0).toLong()
        val xInCell = (actualX - centerX * 12.0).toFloat()
        val zInCell = (actualZ - centerZ * 12.0).toFloat()
        val pipeline = if (status == CloudStatus.FANCY) RenderPipelines.CLOUDS else RenderPipelines.FLAT_CLOUDS
        val layer = SingleQuadParticle.Layer(true, MissingTextureAtlasSprite.getLocation(), pipeline)
        val phase = time / (textureWidth * 400L)
        val stableCenterX = centerX + phase * textureWidth
        for (face in faces) {
            val corners = FACE_VERTICES[face.direction]
            val positions = FloatArray(12)
            repeat(4) { corner ->
                val source = if (face.flags and 16 != 0) 3 - corner else corner
                positions[corner * 3] = (corners[source * 3] + face.x) * 12f - xInCell
                positions[corner * 3 + 1] = corners[source * 3 + 1] * 4f + (bottomY - camera.y).toFloat()
                positions[corner * 3 + 2] = (corners[source * 3 + 2] + face.z) * 12f - zInCell
            }
            val owner = ((stableCenterX + face.x) shl 32) xor ((centerZ + face.z) and 0xffffffffL)
            val key = EffectSurfaceKey(EffectKind.CLOUD, owner, face.direction * 2 + if (face.flags and 16 != 0) 1 else 0,
                "${pipeline.location}|$revision")
            val uv = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
            TemporalEffects.capture(EffectQuad(key, layer, positions, uv, color), EffectDomain.CLOUDS,
                EffectAppearance(uv, color, revision))
        }
    }

    companion object {
        private val FACE_VERTICES = arrayOf(
            floatArrayOf(1f,0f,0f, 1f,0f,1f, 0f,0f,1f, 0f,0f,0f),
            floatArrayOf(0f,1f,0f, 0f,1f,1f, 1f,1f,1f, 1f,1f,0f),
            floatArrayOf(0f,0f,0f, 0f,1f,0f, 1f,1f,0f, 1f,0f,0f),
            floatArrayOf(1f,0f,1f, 1f,1f,1f, 0f,1f,1f, 0f,0f,1f),
            floatArrayOf(0f,0f,1f, 0f,1f,1f, 0f,1f,0f, 0f,0f,0f),
            floatArrayOf(1f,0f,0f, 1f,1f,0f, 1f,1f,1f, 1f,0f,1f)
        )
    }
}
