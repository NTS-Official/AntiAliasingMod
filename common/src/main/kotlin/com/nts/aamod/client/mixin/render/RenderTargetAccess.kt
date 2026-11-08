package com.nts.aamod.client.mixin.render

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(RenderTarget::class)
interface RenderTargetAccess {
    @Accessor("colorTexture")
    fun `aamod$setColorTexture`(texture: GpuTexture?)
    @Accessor("colorTextureView")
    fun `aamod$setColorTextureView`(view: GpuTextureView?)
    @Accessor("depthTexture")
    fun `aamod$setDepthTexture`(texture: GpuTexture?)
    @Accessor("depthTextureView")
    fun `aamod$setDepthTextureView`(view: GpuTextureView?)
}
