package com.nts.aamod.client.mixin.compat

import com.nts.aamod.client.compat.sodium.SodiumTerrainEffects
import com.llamalad7.mixinextras.sugar.Local
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Pseudo
@Mixin(targets = ["net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer"], remap = false)
abstract class SodiumTerrainRenderMixin {
    @Inject(method = ["render"], at = [At("HEAD")])
    private fun `aamod$beginTerrain`(callback: CallbackInfo, @Local(argsOnly = true) matrices: ChunkRenderMatrices,
        @Local(argsOnly = true) pass: TerrainRenderPass) {
        SodiumTerrainEffects.begin(matrices, pass)
    }

    @Inject(method = ["render"], at = [At("RETURN")])
    private fun `aamod$endTerrain`(callback: CallbackInfo) { SodiumTerrainEffects.end() }
}
