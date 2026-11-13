package com.nts.aamod.client.antialiasing.taa.effects

import com.nts.aamod.client.mixin.render.effects.EffectAtlasAccess
import net.minecraft.client.renderer.texture.SpriteContents
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import java.util.WeakHashMap

interface EffectSpriteAnimation {
    var aamodAnimationContents: SpriteContents?
}

object AtlasAnimationRevision {
    private class AtlasSprites(val source: List<TextureAtlasSprite>) {
        val animated = source.filter { it.isAnimated }
    }

    private val revisions = WeakHashMap<SpriteContents, Long>()
    private val atlases = WeakHashMap<TextureAtlas, AtlasSprites>()

    fun changed(contents: SpriteContents) { revisions[contents] = (revisions[contents] ?: 0L) + 1L }

    fun affectedSprites(atlas: TextureAtlas, uv: FloatArray): List<SpriteContents> {
        val source = (atlas as EffectAtlasAccess).`aamod$getSprites`()
        val sprites = atlases[atlas]?.takeIf { it.source === source }
            ?: AtlasSprites(source).also { atlases[atlas] = it }
        if (sprites.animated.isEmpty()) return emptyList()
        var minimumU = uv[0]
        var maximumU = uv[0]
        var minimumV = uv[1]
        var maximumV = uv[1]
        for (coordinate in 2 until uv.size step 2) {
            minimumU = minOf(minimumU, uv[coordinate])
            maximumU = maxOf(maximumU, uv[coordinate])
            minimumV = minOf(minimumV, uv[coordinate + 1])
            maximumV = maxOf(maximumV, uv[coordinate + 1])
        }
        return sprites.animated.filter { sprite ->
            minimumU < sprite.u1 && maximumU > sprite.u0 && minimumV < sprite.v1 && maximumV > sprite.v0
        }.map { it.contents() }
    }

    fun revision(contents: List<SpriteContents>): Long = contents.sumOf { revisions[it] ?: 0L }

    fun revision(atlas: TextureAtlas, uv: FloatArray): Long = revision(affectedSprites(atlas, uv))
}
