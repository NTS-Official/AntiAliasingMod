package com.nts.aamod.client.mixin.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.nts.aamod.client.antialiasing.fxaa.FxaaPipelines.reuse
import net.minecraft.client.renderer.PostChain
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyVariable

@Mixin(PostChain::class)
abstract class PostChainMixin {
    private companion object {
        @ModifyVariable(method = ["createPass"], at = [At("STORE")], ordinal = 0)
        @JvmStatic
        private fun `aamod$reuseFxaaPipeline`(pipeline: RenderPipeline): RenderPipeline {
            return reuse(pipeline)
        }
    }
}
