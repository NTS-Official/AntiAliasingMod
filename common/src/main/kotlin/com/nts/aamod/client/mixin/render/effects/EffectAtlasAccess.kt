package com.nts.aamod.client.mixin.render.effects

import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(TextureAtlas::class)
interface EffectAtlasAccess {
    @Accessor("sprites")
    fun `aamod$getSprites`(): List<TextureAtlasSprite>
}
