package com.nts.aamod.client.mixin.render

import com.nts.aamod.client.antialiasing.AntiAliasingController.cameraExtracted
import com.nts.aamod.client.antialiasing.AntiAliasingController.beginWorldRender
import com.nts.aamod.client.antialiasing.AntiAliasingController.endWorldRender
import com.nts.aamod.client.antialiasing.AntiAliasingController.close
import com.nts.aamod.client.antialiasing.AntiAliasingController.finalColor
import com.nts.aamod.client.antialiasing.AntiAliasingController.invalidate
import com.nts.aamod.client.antialiasing.AntiAliasingController.prepareFrame
import com.nts.aamod.client.antialiasing.AntiAliasingController.worldProjection
import com.nts.aamod.client.antialiasing.AntiAliasingController.worldRendered
import com.nts.aamod.client.antialiasing.InvalidationReason
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.TextureFilteringMethod
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.GlobalSettingsUniform
import net.minecraft.client.renderer.state.GameRenderState
import org.joml.Matrix4f
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.ModifyArg
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(GameRenderer::class)
abstract class GameRendererMixin {
    @Shadow
    @Final
    private val globalSettingsUniform: GlobalSettingsUniform? = null

    @Inject(method = ["render"], at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;", ordinal = 0)])
    private fun `aamod$beginWorldRender`(deltaTracker: DeltaTracker?, advanceGameTime: Boolean, callback: CallbackInfo?) {
        beginWorldRender()
    }

    @Inject(method = ["render"], at = [At("RETURN")])
    private fun `aamod$endWorldRender`(deltaTracker: DeltaTracker?, advanceGameTime: Boolean, callback: CallbackInfo?) {
        endWorldRender()
    }

    @Shadow
    @Final
    private val gameRenderState: GameRenderState? = null

    @Inject(method = ["extract"], at = [At("HEAD")])
    private fun `aamod$prepareFrame`(deltaTracker: DeltaTracker?, advanceGameTime: Boolean, callback: CallbackInfo?) {
        prepareFrame(advanceGameTime)
    }

    @Inject(method = ["extractCamera"], at = [At("TAIL")])
    private fun `aamod$extractCamera`(
        deltaTracker: DeltaTracker?,
        worldPartialTicks: Float,
        cameraPartialTicks: Float,
        callback: CallbackInfo?
    ) {
        cameraExtracted(this.gameRenderState!!.levelRenderState.cameraRenderState)
    }

    @ModifyArg(
        method = ["renderLevel"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
        )],
        index = 0
    )
    private fun `aamod$captureWorldProjection`(projection: Matrix4f): Matrix4f {
        return worldProjection(projection)
    }

    @Inject(
        method = ["render"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            shift = At.Shift.AFTER
        )]
    )
    private fun `aamod$worldRendered`(deltaTracker: DeltaTracker?, advanceGameTime: Boolean, callback: CallbackInfo?) {
        worldRendered()
    }

    @Inject(
        method = ["render"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V")]
    )
    private fun `aamod$finalColor`(deltaTracker: DeltaTracker?, advanceGameTime: Boolean, callback: CallbackInfo?) {
        val target = (this as Any as GameRenderer).mainRenderTarget()
        val worldWidth = target.width
        finalColor(target)
        if (worldWidth != target.width) {
            val state = checkNotNull(gameRenderState)
            globalSettingsUniform!!.update(
                target.width, target.height, state.optionsRenderState.glintStrength,
                Minecraft.getInstance().level?.gameTime ?: 0L, checkNotNull(deltaTracker),
                state.optionsRenderState.menuBackgroundBlurriness, state.levelRenderState.cameraRenderState.pos,
                state.optionsRenderState.textureFiltering == TextureFilteringMethod.RGSS
            )
        }
    }

    @Inject(method = ["resize"], at = [At("HEAD")])
    private fun `aamod$resize`(width: Int, height: Int, callback: CallbackInfo?) {
        invalidate(InvalidationReason.SIZE_CHANGED)
    }

    @Inject(method = ["resetData"], at = [At("HEAD")])
    private fun `aamod$resetWorld`(callback: CallbackInfo?) {
        invalidate(InvalidationReason.WORLD_CHANGED)
    }

    @Inject(method = ["close"], at = [At("HEAD")])
    private fun `aamod$close`(callback: CallbackInfo?) {
        close()
    }
}
