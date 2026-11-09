package com.nts.aamod.client.mixin.render.motion

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.vertex.PoseStack
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import com.nts.aamod.client.antialiasing.taa.motion.MotionOwnerAccess
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderDispatcher
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(EntityRenderDispatcher::class)
abstract class EntityMotionMixin {
    @Inject(method = ["extractEntity"], at = [At("RETURN")])
    private fun `aamod$extractOwner`(entity: Entity, partialTicks: Float, callback: CallbackInfoReturnable<EntityRenderState>) {
        (callback.returnValue as MotionOwnerAccess).aamodMotionOwner = MotionCapture.owner(entity)
    }

    @WrapMethod(method = ["submit"])
    private fun `aamod$submitOwner`(
        state: EntityRenderState, camera: CameraRenderState, x: Double, y: Double, z: Double,
        pose: PoseStack, collector: SubmitNodeCollector, original: Operation<Void>
    ) {
        MotionCapture.withOwner((state as MotionOwnerAccess).aamodMotionOwner) {
            original.call(state, camera, x, y, z, pose, collector)
        }
    }
}
