package com.nts.aamod.client.mixin.render;

import com.nts.aamod.client.antialiasing.AntiAliasingController;
import com.nts.aamod.client.antialiasing.InvalidationReason;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private GameRenderState gameRenderState;

    @Inject(method = "extract", at = @At("HEAD"))
    private void aamod$prepareFrame(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo callback) {
        AntiAliasingController.prepareFrame(advanceGameTime);
    }

    @Inject(method = "extractCamera", at = @At("TAIL"))
    private void aamod$extractCamera(DeltaTracker deltaTracker, float worldPartialTicks, float cameraPartialTicks, CallbackInfo callback) {
        AntiAliasingController.cameraExtracted(this.gameRenderState.levelRenderState.cameraRenderState);
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"), index = 0)
    private Matrix4f aamod$captureWorldProjection(Matrix4f projection) {
        return AntiAliasingController.worldProjection(projection);
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V", shift = At.Shift.AFTER))
    private void aamod$worldRendered(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo callback) {
        AntiAliasingController.worldRendered();
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void aamod$finalColor(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo callback) {
        AntiAliasingController.finalColor(((GameRenderer) (Object) this).mainRenderTarget());
    }

    @Inject(method = "resize", at = @At("HEAD"))
    private void aamod$resize(int width, int height, CallbackInfo callback) {
        AntiAliasingController.invalidate(InvalidationReason.SIZE_CHANGED);
    }

    @Inject(method = "resetData", at = @At("HEAD"))
    private void aamod$resetWorld(CallbackInfo callback) {
        AntiAliasingController.invalidate(InvalidationReason.WORLD_CHANGED);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void aamod$close(CallbackInfo callback) {
        AntiAliasingController.close();
    }
}
