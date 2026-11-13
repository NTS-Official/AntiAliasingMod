package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.nts.aamod.client.antialiasing.taa.composition.NativeEffectDraws
import net.minecraft.client.Minecraft
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.SectionMesh
import net.minecraft.client.renderer.texture.SpriteContents
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.core.BlockPos
import org.joml.Matrix4f
import org.joml.Matrix4fc
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.WeakHashMap

object TerrainEffects {
    private class Mesh(val geometry: TerrainGeometry) { var animations: Array<List<SpriteContents>>? = null }
    private class Submission(val mesh: Mesh, val origin: BlockPos, val view: Matrix4f, val visibility: Float)
    private val meshes = WeakHashMap<SectionMesh, Mesh>()
    private val submissions = IdentityHashMap<Any, Submission>()
    private val captured = IdentityHashMap<Any, Boolean>()
    private const val CACHE_BYTES = 64L * 1024 * 1024

    fun uploaded(mesh: SectionMesh, layer: ChunkSectionLayer, vertices: ByteBuffer?) {
        if (layer != ChunkSectionLayer.TRANSLUCENT || vertices == null) return
        val geometry = TerrainGeometry.read(vertices, DefaultVertexFormat.BLOCK.vertexSize)
        synchronized(meshes) {
            meshes.remove(mesh)
            if (geometry != null) {
                val iterator = meshes.entries.iterator()
                var bytes = meshes.values.sumOf { it.geometry.bytes }
                while (bytes + geometry.bytes > CACHE_BYTES && iterator.hasNext()) {
                    bytes -= iterator.next().value.geometry.bytes
                    iterator.remove()
                }
                meshes[mesh] = Mesh(geometry)
            }
        }
    }

    fun register(draw: Any, mesh: SectionMesh, layer: ChunkSectionLayer, origin: BlockPos, view: Matrix4fc, visibility: Float) {
        if (!TemporalEffects.active || layer != ChunkSectionLayer.TRANSLUCENT) return
        if (submissions.size >= 65536) {
            TemporalEffects.rejectDomain(EffectDomain.TRANSLUCENT, EffectRejection.CAPTURE_BUDGET)
            return
        }
        val data = synchronized(meshes) { meshes[mesh] } ?: run {
            TemporalEffects.rejectDomain(EffectDomain.TRANSLUCENT, EffectRejection.UNRELIABLE_SOURCE)
            return
        }
        submissions[draw] = Submission(data, origin.immutable(), Matrix4f(view), visibility)
    }

    fun prepare(draw: RenderPass.Draw<*>, defaultIndices: GpuBuffer?, defaultType: IndexType?) {
        if (!TemporalEffects.active || captured.put(draw, true) != null) return
        val submission = submissions[draw] ?: return
        if (draw.slot() != 0 || draw.indexCount() != submission.mesh.geometry.faces * 6) {
            TemporalEffects.rejectDomain(EffectDomain.TRANSLUCENT, EffectRejection.UNRELIABLE_SOURCE)
            return
        }
        val indices = draw.indexBuffer() ?: defaultIndices ?: return
        val indexType = draw.indexType() ?: defaultType ?: IndexType.SHORT
        NativeEffectDraws.uploaded(draw, StagedVertexBuffer.ExecuteInfo(draw.vertexBuffer(), indices, indexType,
            draw.baseVertex(), draw.firstIndex(), draw.indexCount()))
        val minecraft = Minecraft.getInstance()
        val atlas = minecraft.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS) as? TextureAtlas ?: return
        val geometry = submission.mesh.geometry
        val animations = submission.mesh.animations ?: Array(geometry.faces) { face ->
            AtlasAnimationRevision.affectedSprites(atlas, geometry.uv(face))
        }.also { submission.mesh.animations = it }
        val camera = minecraft.gameRenderer.mainCamera().position()
        val view = Matrix4f(submission.view).translate(
            (submission.origin.x - camera.x).toFloat(), (submission.origin.y - camera.y).toFloat(),
            (submission.origin.z - camera.z).toFloat()
        )
        val owner = TemporalEffects.featureOwner(submission.mesh)
        val layer = SingleQuadParticle.Layer(true, TextureAtlas.LOCATION_BLOCKS, ChunkSectionLayer.TRANSLUCENT.pipeline())
        repeat(geometry.faces) { face ->
            val uv = geometry.uv(face)
            val key = EffectSurfaceKey(EffectKind.TRANSLUCENT, owner, face, "terrain|${submission.origin.asLong()}")
            val revision = AtlasAnimationRevision.revision(animations[face])
            val alphas = geometry.alphas(face)
            TemporalEffects.capture(EffectQuad(key, layer, geometry.positions(face), uv, geometry.color(face), alphas, draw),
                EffectDomain.TRANSLUCENT, EffectAppearance(uv, geometry.color(face), revision xor submission.visibility.toRawBits().toLong(), alphas),
                modelView = view)
        }
    }

    fun endFrame() { submissions.clear(); captured.clear() }
}
