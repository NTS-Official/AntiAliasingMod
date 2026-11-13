package com.nts.aamod.client.mixin.compat

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.nts.aamod.client.antialiasing.taa.composition.FrozenRenderPassAccess
import com.nts.aamod.client.antialiasing.taa.effects.TemporalEffects
import com.nts.aamod.client.compat.sodium.SodiumTerrainDrawState
import com.nts.aamod.client.compat.sodium.SodiumTerrainEffects
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Pseudo
@Mixin(targets = [
    "net.caffeinemc.mods.sodium.client.gpu.device.context.GLDrawContext",
    "net.caffeinemc.mods.sodium.client.gpu.device.context.VKDrawContext"
], remap = false)
abstract class SodiumTerrainContextMixin {
    @Unique private var aamodTerrainTime = 0L
    @Unique private var aamodTerrainPass: RenderPass? = null
    @Unique private var aamodTerrainPipeline: RenderPipeline? = null

    @Inject(method = ["setContext"], at = [At("TAIL")])
    private fun `aamod$terrainPipeline`(pass: RenderPass, pipeline: RenderPipeline, callback: CallbackInfo) {
        aamodTerrainPass = pass
        aamodTerrainPipeline = pipeline
    }

    @WrapOperation(method = ["updateData"], at = [At(value = "INVOKE", target = "Ljava/lang/System;currentTimeMillis()J")])
    private fun `aamod$terrainTime`(original: Operation<Long>): Long = original.call().also { aamodTerrainTime = it }

    @Inject(method = ["updateData"], at = [At("TAIL")])
    private fun `aamod$terrainDrawState`(region: RenderRegion, camera: CameraTransform, callback: CallbackInfo) {
        if (!TemporalEffects.active) return
        val pass = aamodTerrainPass ?: return
        val time = Math.toIntExact(aamodTerrainTime - region.creationTime)
        val state = SodiumTerrainDrawState(
            (region.originX - camera.intX).toFloat() - camera.fracX,
            (region.originY - camera.intY).toFloat() - camera.fracY,
            (region.originZ - camera.intZ).toFloat() - camera.fracZ,
            time, region.id
        )
        (pass as FrozenRenderPassAccess).aamodFrozenPass?.extension(state)
        aamodTerrainPipeline?.let { SodiumTerrainEffects.capture(region, camera, state, it, time) }
    }
}
