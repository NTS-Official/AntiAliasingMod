package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.llamalad7.mixinextras.sugar.Local
import com.mojang.blaze3d.systems.RenderPass
import com.nts.aamod.client.antialiasing.taa.effects.TerrainEffects
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.SectionMesh
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.core.BlockPos
import net.minecraft.util.Util
import org.joml.Matrix4fc
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(value = [LevelRenderer::class], priority = 1100)
abstract class TerrainDrawEffectMixin {
    @ModifyExpressionValue(method = ["prepareChunkRenders"], at = [At(value = "NEW", target = "com/mojang/blaze3d/systems/RenderPass\$Draw")], require = 0)
    private fun `aamod$terrainDraw`(
        draw: RenderPass.Draw<*>, view: Matrix4fc, @Local mesh: SectionMesh, @Local layer: ChunkSectionLayer,
        @Local origin: BlockPos, @Local section: SectionRenderDispatcher.RenderSection
    ): RenderPass.Draw<*> = draw.also {
        TerrainEffects.register(it, mesh, layer, origin, view, section.getVisibility(Util.getMillis()))
    }
}
