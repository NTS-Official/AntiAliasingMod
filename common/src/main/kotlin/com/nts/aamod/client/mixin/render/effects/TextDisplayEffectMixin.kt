package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.vertex.PoseStack
import com.nts.aamod.client.antialiasing.taa.effects.TextEffects
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(targets = ["net.minecraft.client.renderer.entity.DisplayRenderer\$TextDisplayRenderer"])
abstract class TextDisplayEffectMixin {
    @WrapOperation(
        method = ["submitInner(Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector\$CustomGeometryRenderer;)V")]
    )
    private fun `aamod$textBackground`(
        collector: SubmitNodeCollector, pose: PoseStack, type: RenderType,
        renderer: SubmitNodeCollector.CustomGeometryRenderer, original: Operation<Void>
    ) {
        original.call(collector, pose, type, TextEffects.backgroundGeometry(renderer, type))
    }
}
