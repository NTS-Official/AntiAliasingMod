package com.nts.aamod.client.mixin.render.effects

import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import com.nts.aamod.client.antialiasing.taa.motion.MotionOwnerAccess
import net.minecraft.client.Camera
import net.minecraft.client.particle.ItemPickupParticle
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(targets = ["net.minecraft.client.particle.ItemPickupParticleGroup\$ParticleInstance"])
abstract class PickupParticleEffectMixin {
    private companion object {
        @JvmStatic
        @Inject(method = ["fromParticle"], at = [At("HEAD")])
        private fun `aamod$pickupOwner`(particle: ItemPickupParticle, camera: Camera, partialTick: Float, callback: CallbackInfoReturnable<Any>) {
            val state = (particle as PickupParticleAccess).`aamod$getItemRenderState`()
            (state as MotionOwnerAccess).aamodMotionOwner = MotionCapture.owner(particle)
        }
    }
}
