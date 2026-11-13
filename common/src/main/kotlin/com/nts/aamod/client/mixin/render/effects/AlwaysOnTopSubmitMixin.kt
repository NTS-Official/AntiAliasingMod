package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.sugar.Local
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import net.minecraft.client.renderer.SubmitNodeCollection
import net.minecraft.client.renderer.feature.GizmoFeatureRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(SubmitNodeCollection::class)
abstract class AlwaysOnTopSubmitMixin {
    @Inject(method = ["submitGizmoPrimitives"], at = [At(value = "FIELD", target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;alwaysOnTop:Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;")])
    private fun `aamod$lateWorldSubmit`(callback: CallbackInfo, @Local submit: GizmoFeatureRenderer.Submit) {
        MotionCapture.excludeFromWorldHistory(submit)
    }
}
