package com.nts.aamod.client.mixin.render

import com.nts.aamod.client.antialiasing.AntiAliasingController
import net.minecraft.client.Camera
import org.joml.Matrix4f
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Camera::class)
abstract class CameraMixin {
    @Inject(method = ["createProjectionMatrixForCulling"], at = [At("RETURN")], cancellable = true)
    private fun `aamod$cullingMargin`(callback: CallbackInfoReturnable<Matrix4f>) {
        callback.returnValue = AntiAliasingController.cullingProjection(callback.returnValue)
    }
}
