package com.nts.aamod.client.compat.sodium

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.nts.aamod.client.antialiasing.taa.effects.*
import net.caffeinemc.mods.sodium.client.SodiumClientMod
import net.caffeinemc.mods.sodium.client.gpu.GPULimits
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform
import net.caffeinemc.mods.sodium.mixin.core.render.texture.TextureAtlasAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.texture.SpriteContents
import net.minecraft.client.renderer.texture.TextureAtlas
import org.joml.Matrix4f
import java.util.WeakHashMap

object SodiumTerrainEffects {
    private class Mesh(val data: SodiumTerrainGeometry) {
        var time = -1
        var animations: Array<List<SpriteContents>>? = null
    }
    private val meshes = WeakHashMap<RenderRegion, MutableMap<Int, Mesh>>()
    private var matrices: ChunkRenderMatrices? = null
    private const val CACHE_BYTES = 64L * 1024 * 1024

    fun uploaded(region: RenderRegion, results: Collection<*>) {
        val entries = meshes.getOrPut(region) { mutableMapOf() }
        for (result in results) uploaded(entries, result)
        var bytes = meshes.values.sumOf { entries -> entries.values.sumOf { it.data.bytes } }
        val regions = meshes.entries.iterator()
        while (bytes > CACHE_BYTES && regions.hasNext()) {
            val entries = regions.next().value
            val sections = entries.entries.iterator()
            while (bytes > CACHE_BYTES && sections.hasNext()) {
                bytes -= sections.next().value.data.bytes
                sections.remove()
            }
            if (entries.isEmpty()) regions.remove()
        }
    }

    private fun uploaded(entries: MutableMap<Int, Mesh>, result: Any?) {
        if (result !is ChunkBuildOutput) return
        val section = result.section.sectionIndex
        val previous = entries.remove(section)
        val mesh = result.getMesh(DefaultTerrainRenderPasses.TRANSLUCENT) ?: return
        val source = mesh.vertexData.directBuffer
        val geometry = SodiumTerrainGeometry.read(source, mesh.vertexSegments, section)
        if (geometry == null) return
        entries[section] = Mesh(geometry).also { it.time = previous?.time ?: -1 }
    }

    fun meshTime(region: RenderRegion, section: Int, time: Int) { meshes[region]?.get(section)?.time = time }

    fun begin(matrices: ChunkRenderMatrices, pass: TerrainRenderPass) {
        this.matrices = if (TemporalEffects.active && pass.isTranslucent) matrices else null
    }

    fun end() { matrices = null }

    fun capture(region: RenderRegion, camera: CameraTransform, state: SodiumTerrainDrawState, pipeline: RenderPipeline, time: Int) {
        val matrices = matrices ?: return
        if (!TemporalEffects.active) return
        val storage = region.getStorage(DefaultTerrainRenderPasses.TRANSLUCENT) ?: return
        val iterator = region.renderList.sectionsWithGeometryIterator(true) ?: return
        val minecraft = Minecraft.getInstance()
        val atlas = minecraft.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS) as? TextureAtlas ?: return
        val dimensions = atlas as TextureAtlasAccessor
        val precision = (1 shl GPULimits.getSubTexelPrecisionBits()).toDouble()
        val shrinkU = (1.0 / 32768.0 - 1.0 / dimensions.`sodium$getWidth`() / precision).toFloat()
        val shrinkV = (1.0 / 32768.0 - 1.0 / dimensions.`sodium$getHeight`() / precision).toFloat()
        val layer = SingleQuadParticle.Layer(true, TextureAtlas.LOCATION_BLOCKS, pipeline)
        val fadeDuration = minecraft.options.chunkSectionFadeInTime().get() * 1000.0
        while (iterator.hasNext()) {
            val section = iterator.nextByteAsInt()
            val mesh = meshes[region]?.get(section)
            val chunkX = region.chunkX + LocalSectionIndex.unpackX(section)
            val chunkY = region.chunkY + LocalSectionIndex.unpackY(section)
            val chunkZ = region.chunkZ + LocalSectionIndex.unpackZ(section)
            val mask = (if (SodiumClientMod.options().performance.useBlockFaceCulling)
                DefaultChunkRenderer.getVisibleFaces(camera.intX, camera.intY, camera.intZ, chunkX, chunkY, chunkZ)
                else ModelQuadFacing.ALL) and SectionRenderDataUnsafe.getSliceMask(storage.getDataPointer(section))
            if (mask == 0) continue
            if (mesh == null) {
                TemporalEffects.rejectDomain(EffectDomain.TRANSLUCENT, EffectRejection.UNRELIABLE_SOURCE)
                continue
            }
            val model = Matrix4f(matrices.projection()).mul(matrices.modelView()).translate(
                (chunkX * 16 - camera.intX).toFloat() - camera.fracX,
                (chunkY * 16 - camera.intY).toFloat() - camera.fracY,
                (chunkZ * 16 - camera.intZ).toFloat() - camera.fracZ
            )
            val geometry = mesh.data.geometry
            val animations = mesh.animations ?: Array(geometry.faces) { face ->
                AtlasAnimationRevision.affectedSprites(atlas, geometry.uv(face))
            }.also { mesh.animations = it }
            val faded = mesh.time != Int.MIN_VALUE && (mesh.time < 0 || time.toLong() - mesh.time >= fadeDuration)
            val owner = TemporalEffects.featureOwner(mesh)
            repeat(geometry.faces) { face ->
                if (mask and (1 shl mesh.data.direction(face)) == 0) return@repeat
                val uv = mesh.data.uv(face, shrinkU, shrinkV)
                val key = EffectSurfaceKey(EffectKind.TRANSLUCENT, owner, face, "terrain|sodium|$chunkX,$chunkY,$chunkZ")
                val revision = AtlasAnimationRevision.revision(animations[face])
                val alphas = geometry.alphas(face)
                val quad = EffectQuad(key, layer, geometry.positions(face), uv, geometry.color(face), alphas, state)
                TemporalEffects.captureProjected(quad, EffectDomain.TRANSLUCENT, EffectGeometry.project(quad.position, model),
                    EffectAppearance(uv, geometry.color(face), revision, alphas), reliable = faded)
            }
        }
    }
}
