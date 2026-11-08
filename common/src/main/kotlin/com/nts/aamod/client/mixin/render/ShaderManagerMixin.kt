package com.nts.aamod.client.mixin.render

import com.nts.aamod.client.antialiasing.AntiAliasingController.invalidate
import com.nts.aamod.client.antialiasing.InvalidationReason
import net.minecraft.client.renderer.ShaderManager
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ShaderManager::class)
abstract class ShaderManagerMixin {
    @Inject(
        method = ["apply(Lnet/minecraft/client/renderer/ShaderManager\$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V"],
        at = [At("TAIL")]
    )
    private fun `aamod$resourcesReloaded`(callback: CallbackInfo?) {
        invalidate(InvalidationReason.RESOURCES_RELOADED)
    }
}
