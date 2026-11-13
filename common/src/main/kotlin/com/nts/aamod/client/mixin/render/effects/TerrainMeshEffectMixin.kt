package com.nts.aamod.client.mixin.render.effects

import com.nts.aamod.client.antialiasing.taa.effects.TerrainEffects
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.CompiledSectionMesh
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.nio.ByteBuffer

@Mixin(SectionRenderDispatcher.RenderSection::class)
abstract class TerrainMeshEffectMixin {
    @Inject(method = ["addSectionBuffersToUberBuffer"], at = [At("HEAD")])
    private fun `aamod$terrainUpload`(
        layer: ChunkSectionLayer, mesh: CompiledSectionMesh, vertices: ByteBuffer?, indices: ByteBuffer?,
        callback: CallbackInfoReturnable<Boolean>
    ) { TerrainEffects.uploaded(mesh, layer, vertices) }
}
