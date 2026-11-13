package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.injector.ModifyReturnValue
import com.mojang.blaze3d.vertex.VertexConsumer
import com.nts.aamod.client.antialiasing.taa.composition.NativeEffectDraws
import net.minecraft.client.renderer.StagedVertexBuffer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(StagedVertexBuffer::class)
abstract class EffectStagedDrawMixin {
    @ModifyReturnValue(method = ["getVertexBuilder"], at = [At("RETURN")])
    private fun `aamod$nativeBuilder`(builder: VertexConsumer, draw: StagedVertexBuffer.Draw): VertexConsumer =
        builder.also { NativeEffectDraws.bind(it, draw) }

    @ModifyReturnValue(method = ["getExecuteInfo"], at = [At("RETURN")])
    private fun `aamod$uploadedDraw`(execution: StagedVertexBuffer.ExecuteInfo?, draw: StagedVertexBuffer.Draw): StagedVertexBuffer.ExecuteInfo? =
        execution.also { NativeEffectDraws.uploaded(draw, it) }
}
