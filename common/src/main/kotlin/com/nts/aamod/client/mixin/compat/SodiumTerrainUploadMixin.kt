package com.nts.aamod.client.mixin.compat

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.sugar.Local
import com.nts.aamod.client.compat.sodium.SodiumTerrainEffects
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Pseudo
@Mixin(targets = ["net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager"], remap = false)
abstract class SodiumTerrainUploadMixin {
    @Inject(method = ["uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V"], at = [At("HEAD")])
    private fun `aamod$terrainMesh`(region: RenderRegion, results: Collection<*>, uniforms: UniformBufferManager, callback: CallbackInfo) {
        SodiumTerrainEffects.uploaded(region, results)
    }

    @WrapOperation(method = ["uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V"],
        at = [At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;writeMeshTimes(III)V")])
    private fun `aamod$terrainFade`(uniforms: UniformBufferManager, regionId: Int, section: Int, time: Int, original: Operation<Void>,
        @Local(argsOnly = true) region: RenderRegion) {
        original.call(uniforms, regionId, section, time)
        SodiumTerrainEffects.meshTime(region, section, time)
    }
}
