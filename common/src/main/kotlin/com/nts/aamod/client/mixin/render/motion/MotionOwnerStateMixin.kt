package com.nts.aamod.client.mixin.render.motion

import com.nts.aamod.client.antialiasing.taa.motion.MotionOwner
import com.nts.aamod.client.antialiasing.taa.motion.MotionOwnerAccess
import net.minecraft.client.model.monster.guardian.GuardianParticleModel
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.entity.state.EntityRenderState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique

@Mixin(EntityRenderState::class, BlockEntityRenderState::class, GuardianParticleModel::class)
abstract class MotionOwnerStateMixin : MotionOwnerAccess {
    @Unique
    override var aamodMotionOwner: MotionOwner? = null
}
