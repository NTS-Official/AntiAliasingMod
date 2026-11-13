package com.nts.aamod.client.mixin.render.effects

import com.nts.aamod.client.antialiasing.taa.effects.CloudEffects
import com.llamalad7.mixinextras.sugar.Local
import net.minecraft.client.CloudStatus
import net.minecraft.client.renderer.CloudRenderer
import net.minecraft.world.phys.Vec3
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.nio.ByteBuffer

@Mixin(value = [CloudRenderer::class], priority = 1100)
abstract class CloudEffectMixin {
    @Shadow private var texture: CloudRenderer.TextureData? = null
    @Unique private val aamodClouds = CloudEffects()
    @Unique private var aamodCloudStart = 0

    @Inject(method = ["buildMesh"], at = [At("HEAD")])
    private fun `aamod$beginCloudMesh`(callback: CallbackInfo, @Local(argsOnly = true) buffer: ByteBuffer) {
        aamodCloudStart = buffer.position()
        aamodClouds.beginMesh()
    }

    @Inject(method = ["buildMesh"], at = [At("TAIL")])
    private fun `aamod$cloudFaces`(callback: CallbackInfo, @Local(argsOnly = true) buffer: ByteBuffer) {
        aamodClouds.readMesh(buffer, aamodCloudStart)
    }

    @Inject(method = ["render"], at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;")])
    private fun `aamod$cloudGeometry`(color: Int, status: CloudStatus, bottomY: Float, range: Int, camera: Vec3, time: Long, partialTick: Float, callback: CallbackInfo) {
        aamodClouds.capture(color, status, bottomY, camera, time, partialTick, texture?.width() ?: 0)
    }

    @Inject(method = ["apply(Ljava/util/Optional;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V"], at = [At("HEAD")])
    private fun `aamod$cloudResourceRevision`(callback: CallbackInfo) { aamodClouds.invalidate() }
}
