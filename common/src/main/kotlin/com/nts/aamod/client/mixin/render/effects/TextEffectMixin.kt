package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.nts.aamod.client.antialiasing.taa.effects.TextEffects
import net.minecraft.client.renderer.feature.FeatureFrameContext
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer
import net.minecraft.client.renderer.feature.TextFeatureRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(TextFeatureRenderer::class, NameTagFeatureRenderer::class)
abstract class TextEffectMixin {
    @WrapOperation(method = ["buildGroup"], at = [At(value = "INVOKE", target = "Ljava/util/Iterator;next()Ljava/lang/Object;")])
    private fun `aamod$textOwner`(iterator: Iterator<*>, original: Operation<Any>): Any = original.call(iterator).also { TextEffects.begin(it) }

    @WrapMethod(method = ["buildGroup"])
    private fun `aamod$textScope`(context: FeatureFrameContext, submits: List<*>, original: Operation<Void>) {
        try { original.call(context, submits) } finally { TextEffects.end() }
    }
}
