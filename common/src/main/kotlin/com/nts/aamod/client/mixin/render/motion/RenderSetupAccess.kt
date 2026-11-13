package com.nts.aamod.client.mixin.render.motion

import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.TextureTransform
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(RenderSetup::class)
interface RenderSetupAccess {
    @Accessor("textures")
    fun `aamod$getTextures`(): Map<String, Any>

    @Accessor("textureTransform")
    fun `aamod$getTextureTransform`(): TextureTransform

    @Accessor("layeringTransform")
    fun `aamod$getLayeringTransform`(): LayeringTransform
}
