package com.nts.aamod.client.mixin.render.motion

import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(RenderType::class)
interface RenderTypeAccess {
    @Accessor("state")
    fun `aamod$getState`(): RenderSetup
}
