package com.nts.aamod.client.mixin.render.motion

import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import com.nts.aamod.client.antialiasing.taa.motion.MotionSubmission
import com.nts.aamod.client.antialiasing.taa.motion.MotionSubmissionAccess
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer
import net.minecraft.client.renderer.feature.CustomFeatureRenderer
import net.minecraft.client.renderer.feature.ItemFeatureRenderer
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ModelFeatureRenderer.Submit::class, ItemFeatureRenderer.Submit::class, BlockModelFeatureRenderer.Submit::class, CustomFeatureRenderer.Submit::class)
abstract class MotionSubmissionMixin : MotionSubmissionAccess {
    @Unique
    final override var aamodMotionSubmission: MotionSubmission? = null
        private set

    @Inject(method = ["<init>"], at = [At("RETURN")])
    private fun `aamod$captureOwner`(callback: CallbackInfo) {
        aamodMotionSubmission = MotionCapture.submission(this)
    }
}
