package com.nts.aamod.client.mixin.render.effects

import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import com.nts.aamod.client.antialiasing.taa.motion.MotionOwnerAccess
import net.minecraft.client.Camera
import net.minecraft.client.particle.ElderGuardianParticle
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(targets = ["net.minecraft.client.particle.ElderGuardianParticleGroup\$ElderGuardianParticleRenderState"])
abstract class GuardianParticleEffectMixin {
    private companion object {
        @JvmStatic
        @Inject(method = ["fromParticle"], at = [At("HEAD")])
        private fun `aamod$guardianOwner`(particle: ElderGuardianParticle, camera: Camera, partialTick: Float, callback: CallbackInfoReturnable<Any>) {
            // Each particle owns its model, but the weak identity key must remain the particle:
            // model submissions retain the model in their geometry history.
            val model = (particle as GuardianParticleAccess).`aamod$getModel`()
            (model as MotionOwnerAccess).aamodMotionOwner = MotionCapture.owner(particle)
        }
    }
}
