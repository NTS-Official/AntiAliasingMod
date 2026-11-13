package com.nts.aamod.client.mixin.render.effects

import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.systems.RenderPass
import com.nts.aamod.client.antialiasing.taa.effects.AtlasAnimationRevision
import com.nts.aamod.client.antialiasing.taa.effects.EffectSpriteAnimation
import net.minecraft.client.renderer.texture.SpriteContents
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(SpriteContents.AnimationState::class)
abstract class AtlasAnimationMixin : EffectSpriteAnimation {
    @Unique
    final override var aamodAnimationContents: SpriteContents? = null

    @Inject(method = ["drawToAtlas"], at = [At("TAIL")])
    private fun `aamod$animationRevision`(pass: RenderPass, uniform: GpuBufferSlice, callback: CallbackInfo) {
        aamodAnimationContents?.let { AtlasAnimationRevision.changed(it) }
    }
}
