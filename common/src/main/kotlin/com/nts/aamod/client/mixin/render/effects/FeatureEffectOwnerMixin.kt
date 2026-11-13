package com.nts.aamod.client.mixin.render.effects

import com.nts.aamod.client.antialiasing.taa.effects.FeatureEffectOwner
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import com.nts.aamod.client.antialiasing.taa.motion.MotionOwner
import net.minecraft.client.renderer.feature.LeashFeatureRenderer
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer
import net.minecraft.client.renderer.feature.TextFeatureRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LeashFeatureRenderer.Submit::class, TextFeatureRenderer.Submit::class, NameTagFeatureRenderer.Submit::class)
abstract class FeatureEffectOwnerMixin : FeatureEffectOwner {
    @Unique
    final override var aamodEffectOwner: MotionOwner? = null

    @Inject(method = ["<init>"], at = [At("RETURN")])
    private fun `aamod$featureOwner`(callback: CallbackInfo) {
        aamodEffectOwner = MotionCapture.currentOwner
    }
}
