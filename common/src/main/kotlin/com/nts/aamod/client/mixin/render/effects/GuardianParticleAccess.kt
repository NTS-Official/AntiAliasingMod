package com.nts.aamod.client.mixin.render.effects

import net.minecraft.client.model.monster.guardian.GuardianParticleModel
import net.minecraft.client.particle.ElderGuardianParticle
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(ElderGuardianParticle::class)
interface GuardianParticleAccess {
    @Accessor("model")
    fun `aamod$getModel`(): GuardianParticleModel
}
