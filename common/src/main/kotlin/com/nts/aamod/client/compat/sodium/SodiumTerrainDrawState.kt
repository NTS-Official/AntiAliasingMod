package com.nts.aamod.client.compat.sodium

import com.mojang.blaze3d.opengl.GlRenderPipeline
import com.mojang.blaze3d.opengl.GlProgram
import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline
import com.nts.aamod.client.antialiasing.taa.composition.FrozenDrawExtension
import com.nts.aamod.client.antialiasing.taa.effects.EffectDomain
import com.nts.aamod.client.antialiasing.taa.effects.EffectRejection
import com.nts.aamod.client.antialiasing.taa.effects.TemporalEffects
import net.caffeinemc.mods.sodium.mixin.core.RenderPassAccessor
import net.caffeinemc.mods.sodium.mixin.core.VulkanRenderPassAccessor
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL30C
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK13
import java.util.WeakHashMap

class SodiumTerrainDrawState(
    private val x: Float, private val y: Float, private val z: Float,
    private val time: Int, private val region: Int
) : FrozenDrawExtension {
    override fun apply(pass: RenderPass, pipeline: RenderPipeline) {
        when (val compiled = RenderSystem.getDevice().precompilePipeline(pipeline)) {
            is GlRenderPipeline -> {
                val shader = compiled.program()
                val program = shader.programId
                val uniforms = locations.getOrPut(shader) {
                    Uniforms(GL20C.glGetUniformLocation(program, "u_RegionOffset"),
                        GL20C.glGetUniformLocation(program, "u_CurrentTime"), GL20C.glGetUniformLocation(program, "u_RegionID"))
                }
                GlStateManager._glUseProgram(program)
                GL20C.glUniform3f(uniforms.offset, x, y, z)
                GL20C.glUniform1i(uniforms.time, time)
                GL30C.glUniform1ui(uniforms.region, region)
            }
            is VulkanRenderPipeline -> {
                val backend = (pass as RenderPassAccessor).backend as VulkanRenderPassAccessor
                MemoryStack.stackPush().use { stack ->
                    val data = stack.malloc(20)
                    data.putFloat(x).putFloat(y).putFloat(z).putInt(time).putInt(region).flip()
                    VK13.nvkCmdPushConstants(backend.`sodium$getCommandBuffer`(), compiled.pipelineLayout(),
                        VK13.VK_SHADER_STAGE_ALL, 0, 20, MemoryUtil.memAddress(data))
                }
            }
            else -> TemporalEffects.rejectDomain(EffectDomain.TRANSLUCENT, EffectRejection.UNRELIABLE_SOURCE)
        }
    }

    private data class Uniforms(val offset: Int, val time: Int, val region: Int)

    companion object {
        private val locations = WeakHashMap<GlProgram, Uniforms>()
    }
}
