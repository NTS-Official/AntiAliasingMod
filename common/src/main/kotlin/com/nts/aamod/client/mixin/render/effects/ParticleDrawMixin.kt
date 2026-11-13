package com.nts.aamod.client.mixin.render.effects

import com.nts.aamod.client.antialiasing.taa.effects.TemporalEffects
import net.minecraft.client.renderer.feature.FeatureFrameContext
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(QuadParticleFeatureRenderer::class)
abstract class ParticleDrawMixin {
    @Inject(
        method = ["executeGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;ILjava/util/List;Z)V"],
        at = [At("HEAD")]
    )
    private fun `aamod$projectParticleSnapshots`(
        context: FeatureFrameContext, groupIndex: Int, submits: List<QuadParticleFeatureRenderer.Submit>,
        strictlyOrdered: Boolean, callback: CallbackInfo
    ) {
        for (submit in submits) TemporalEffects.prepareParticles(submit.particles(), submit.translucent())
    }
}
