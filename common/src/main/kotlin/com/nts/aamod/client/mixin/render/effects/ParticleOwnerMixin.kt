package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.nts.aamod.client.antialiasing.taa.effects.TemporalEffects
import net.minecraft.client.Camera
import net.minecraft.client.particle.QuadParticleGroup
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(QuadParticleGroup::class)
abstract class ParticleOwnerMixin {
    @WrapOperation(
        method = ["extractRenderState"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/particle/SingleQuadParticle;extract(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lnet/minecraft/client/Camera;F)V")]
    )
    private fun `aamod$particleOwner`(
        particle: SingleQuadParticle, state: QuadParticleRenderState, camera: Camera, partialTick: Float, original: Operation<Void>
    ) {
        TemporalEffects.withParticle(particle) { original.call(particle, state, camera, partialTick) }
    }
}
