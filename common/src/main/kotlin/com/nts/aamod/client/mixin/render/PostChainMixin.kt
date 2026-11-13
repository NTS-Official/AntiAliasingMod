package com.nts.aamod.client.mixin.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.nts.aamod.client.antialiasing.fxaa.FxaaPipelines.reuse
import com.nts.aamod.client.antialiasing.taa.composition.NativeTransparencyContract
import net.minecraft.client.renderer.PostChain
import net.minecraft.client.renderer.PostChainConfig
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.texture.TextureManager
import net.minecraft.resources.Identifier
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyVariable
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(PostChain::class)
abstract class PostChainMixin {
    private companion object {
        @Inject(method = ["load"], at = [At("RETURN")])
        @JvmStatic
        private fun `aamod$registerTransparencyContract`(
            config: PostChainConfig, textures: TextureManager, targets: Set<Identifier>, id: Identifier,
            projection: Projection, projectionBuffer: ProjectionMatrixBuffer, callback: CallbackInfoReturnable<PostChain>
        ) {
            NativeTransparencyContract.register(id, config, callback.returnValue)
        }

        @ModifyVariable(method = ["createPass"], at = [At("STORE")], ordinal = 0)
        @JvmStatic
        private fun `aamod$reuseFxaaPipeline`(pipeline: RenderPipeline): RenderPipeline {
            return reuse(pipeline)
        }
    }
}
