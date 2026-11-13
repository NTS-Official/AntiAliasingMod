package com.nts.aamod.client.mixin.render.effects

import com.nts.aamod.client.antialiasing.taa.effects.TemporalEffects
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(value = [QuadParticleRenderState::class], priority = 1100)
abstract class ParticleStateMixin {
    @Inject(method = ["add"], at = [At("TAIL")])
    private fun `aamod$quadSnapshot`(
        layer: SingleQuadParticle.Layer, x: Float, y: Float, z: Float,
        xRot: Float, yRot: Float, zRot: Float, wRot: Float, scale: Float,
        u0: Float, u1: Float, v0: Float, v1: Float, color: Int, light: Int, callback: CallbackInfo
    ) {
        TemporalEffects.addQuad(this as Any as QuadParticleRenderState, layer, x, y, z,
            xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color)
    }

    @Inject(method = ["clear"], at = [At("HEAD")])
    private fun `aamod$clearSnapshots`(callback: CallbackInfo) {
        TemporalEffects.clear(this as Any as QuadParticleRenderState)
    }
}
