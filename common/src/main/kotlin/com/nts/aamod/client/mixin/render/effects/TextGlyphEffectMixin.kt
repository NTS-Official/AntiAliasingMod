package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.nts.aamod.client.antialiasing.taa.effects.TextEffects
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.font.TextRenderable
import org.joml.Matrix4fc
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At

@Mixin(targets = ["net.minecraft.client.renderer.feature.TextFeatureRenderer\$GlyphRenderer", "net.minecraft.client.renderer.feature.NameTagFeatureRenderer\$GlyphRenderer"], remap = false)
abstract class TextGlyphEffectMixin {
    @Shadow(remap = false) private var displayMode: Font.DisplayMode? = null

    @WrapOperation(method = ["acceptRenderable"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/gui/font/TextRenderable;render(Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZ)V")])
    private fun `aamod$textVertices`(renderable: TextRenderable, pose: Matrix4fc, builder: VertexConsumer, light: Int, shadow: Boolean, original: Operation<Void>) {
        val mode = checkNotNull(displayMode)
        val capture = if (mode == Font.DisplayMode.SEE_THROUGH) null else TextEffects.capture(builder, renderable.renderType(mode))
        original.call(renderable, pose, capture ?: builder, light, shadow)
        capture?.finish()
    }
}
