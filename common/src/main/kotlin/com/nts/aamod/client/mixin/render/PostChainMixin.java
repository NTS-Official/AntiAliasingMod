package com.nts.aamod.client.mixin.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.nts.aamod.client.antialiasing.fxaa.FxaaPipelines;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(PostChain.class)
public abstract class PostChainMixin {
    @ModifyVariable(method = "createPass", at = @At("STORE"), ordinal = 0)
    private static RenderPipeline aamod$reuseFxaaPipeline(RenderPipeline pipeline) {
        return FxaaPipelines.reuse(pipeline);
    }
}
