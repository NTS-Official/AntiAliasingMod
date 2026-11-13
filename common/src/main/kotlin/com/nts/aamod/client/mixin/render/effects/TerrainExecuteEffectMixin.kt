package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.systems.RenderPass
import com.nts.aamod.client.antialiasing.taa.effects.TerrainEffects
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(ChunkSectionsToRender::class)
abstract class TerrainExecuteEffectMixin {
    @WrapOperation(method = ["renderGroup"], at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V")])
    private fun `aamod$terrainGeometry`(
        pass: RenderPass, draws: Collection<RenderPass.Draw<Any>>, indices: GpuBuffer?, indexType: IndexType?,
        dynamicUniforms: Collection<String>, argument: Any, original: Operation<Void>
    ) {
        draws.forEach { TerrainEffects.prepare(it, indices, indexType) }
        original.call(pass, draws, indices, indexType, dynamicUniforms, argument)
    }
}
