package com.nts.aamod.client.mixin.render.motion

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.vertex.PoseStack
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import com.nts.aamod.client.antialiasing.taa.motion.MotionOwnerAccess
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.level.block.entity.BlockEntity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(BlockEntityRenderDispatcher::class)
abstract class BlockEntityMotionMixin {
    @Inject(method = ["tryExtractRenderState"], at = [At("RETURN")])
    private fun `aamod$extractOwner`(
        entity: BlockEntity, partialTicks: Float, overlay: ModelFeatureRenderer.CrumblingOverlay?, globallyRendered: Boolean,
        callback: CallbackInfoReturnable<BlockEntityRenderState?>
    ) {
        (callback.returnValue as? MotionOwnerAccess)?.aamodMotionOwner = MotionCapture.owner(entity)
    }

    @WrapMethod(method = ["submit"])
    private fun `aamod$submitOwner`(
        state: BlockEntityRenderState, pose: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState, original: Operation<Void>
    ) {
        MotionCapture.withOwner((state as MotionOwnerAccess).aamodMotionOwner) {
            original.call(state, pose, collector, camera)
        }
    }
}
