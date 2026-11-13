package com.nts.aamod.client.mixin.render.effects

import net.minecraft.resources.Identifier
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(targets = ["net.minecraft.client.renderer.rendertype.RenderSetup\$TextureBinding"], remap = false)
interface EffectTextureBindingAccess {
    @Accessor("location")
    fun `aamod$getLocation`(): Identifier
}
