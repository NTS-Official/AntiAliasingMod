package com.nts.aamod.client.mixin.render.effects

import net.minecraft.client.particle.ItemPickupParticle
import net.minecraft.client.renderer.entity.state.EntityRenderState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(ItemPickupParticle::class)
interface PickupParticleAccess {
    @Accessor("itemRenderState")
    fun `aamod$getItemRenderState`(): EntityRenderState
}
