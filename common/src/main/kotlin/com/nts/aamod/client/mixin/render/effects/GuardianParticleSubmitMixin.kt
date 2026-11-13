package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.vertex.PoseStack
import com.nts.aamod.client.antialiasing.taa.motion.MotionCapture
import com.nts.aamod.client.antialiasing.taa.motion.MotionOwnerAccess
import net.minecraft.client.model.Model
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(targets = ["net.minecraft.client.particle.ElderGuardianParticleGroup\$State"])
abstract class GuardianParticleSubmitMixin {
    @WrapOperation(method = ["submit"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer\$CrumblingOverlay;)V")])
    private fun <State : Any> `aamod$submitGuardian`(
        collector: SubmitNodeCollector, model: Model<State>, state: State, pose: PoseStack,
        type: RenderType, light: Int, overlay: Int, color: Int, sprite: TextureAtlasSprite?,
        outline: Int, crumbling: ModelFeatureRenderer.CrumblingOverlay?, original: Operation<Void>
    ) {
        MotionCapture.withOwner((model as MotionOwnerAccess).aamodMotionOwner) {
            original.call(collector, model, state, pose, type, light, overlay, color, sprite, outline, crumbling)
        }
    }
}
