package com.nts.aamod.client.mixin.render.effects

import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.nts.aamod.client.antialiasing.taa.effects.EffectSpriteAnimation
import net.minecraft.client.renderer.texture.SpriteContents
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(SpriteContents::class)
abstract class SpriteAnimationBindingMixin {
    @Inject(method = ["createAnimationState"], at = [At("RETURN")])
    private fun `aamod$bindAnimationContents`(
        uniform: GpuBufferSlice, mipLevels: Int, callback: CallbackInfoReturnable<SpriteContents.AnimationState>
    ) {
        val state = callback.returnValue ?: return
        (state as EffectSpriteAnimation).aamodAnimationContents = this as Any as SpriteContents
    }
}
