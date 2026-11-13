package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.vertex.VertexConsumer
import com.nts.aamod.client.antialiasing.taa.effects.TemporalEffects
import com.nts.aamod.client.antialiasing.taa.effects.WeatherEffects
import net.minecraft.client.renderer.WeatherEffectRenderer
import net.minecraft.util.RandomSource
import net.minecraft.world.phys.Vec3
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(WeatherEffectRenderer::class)
abstract class WeatherEffectMixin {
    @Unique
    private var aamodRainSpeed = 3f

    @WrapOperation(method = ["createRainColumnInstance"], at = [At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextFloat()F")])
    private fun `aamod$rainPhaseSpeed`(random: RandomSource, original: Operation<Float>): Float = original.call(random).also {
        aamodRainSpeed = 3f + it
    }

    @Inject(method = ["createRainColumnInstance"], at = [At("RETURN")])
    private fun `aamod$rainPhase`(
        random: RandomSource, ticks: Long, x: Int, bottom: Int, top: Int, z: Int,
        light: Int, partialTick: Float, callback: CallbackInfoReturnable<WeatherEffectRenderer.ColumnInstance>
    ) {
        WeatherEffects.rain(callback.returnValue, ticks, partialTick, aamodRainSpeed)
    }

    @Inject(method = ["createSnowColumnInstance"], at = [At("RETURN")])
    private fun `aamod$snowPhase`(
        random: RandomSource, ticks: Long, x: Int, bottom: Int, top: Int, z: Int,
        light: Int, partialTick: Float, callback: CallbackInfoReturnable<WeatherEffectRenderer.ColumnInstance>
    ) {
        WeatherEffects.snow(callback.returnValue, ticks)
    }

    @WrapMethod(method = ["renderInstances"])
    private fun `aamod$weatherVertices`(
        builder: VertexConsumer, columns: List<WeatherEffectRenderer.ColumnInstance>, camera: Vec3,
        maxAlpha: Float, radius: Int, intensity: Float, original: Operation<Void>
    ) {
        if (!TemporalEffects.active) {
            original.call(builder, columns, camera, maxAlpha, radius, intensity)
            return
        }
        val capture = WeatherEffects.record(builder, columns, maxAlpha < 1f)
        original.call(capture, columns, camera, maxAlpha, radius, intensity)
        capture.finish()
    }
}
