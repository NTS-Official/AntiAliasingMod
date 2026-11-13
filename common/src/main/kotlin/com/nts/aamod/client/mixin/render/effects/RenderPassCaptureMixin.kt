package com.nts.aamod.client.mixin.render.effects

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTextureView
import com.nts.aamod.client.antialiasing.taa.composition.FrozenDrawCommand
import com.nts.aamod.client.antialiasing.taa.composition.FrozenPassRecorder
import com.nts.aamod.client.antialiasing.taa.composition.FrozenRenderPassAccess
import com.nts.aamod.client.antialiasing.taa.composition.FrozenScissor
import org.lwjgl.PointerBuffer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.nio.IntBuffer

@Mixin(RenderPass::class)
abstract class RenderPassCaptureMixin : FrozenRenderPassAccess {
    @Unique
    override var aamodFrozenPass: FrozenPassRecorder? = null

    @Inject(method = ["setPipeline"], at = [At("TAIL")])
    private fun `aamod$capturePipeline`(pipeline: RenderPipeline, callback: CallbackInfo) {
        aamodFrozenPass?.pipeline(pipeline)
    }

    @Inject(method = ["setVertexBuffer"], at = [At("TAIL")])
    private fun `aamod$captureVertex`(slot: Int, buffer: GpuBufferSlice?, callback: CallbackInfo) {
        aamodFrozenPass?.vertex(slot, buffer)
    }

    @Inject(method = ["setIndexBuffer"], at = [At("TAIL")])
    private fun `aamod$captureIndices`(buffer: GpuBuffer, type: IndexType, callback: CallbackInfo) {
        aamodFrozenPass?.indices(buffer, type)
    }

    @Inject(method = ["setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V"], at = [At("TAIL")])
    private fun `aamod$captureUniformBuffer`(name: String, buffer: GpuBuffer, callback: CallbackInfo) {
        aamodFrozenPass?.uniform(name, buffer.slice())
    }

    @Inject(method = ["setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"], at = [At("TAIL")])
    private fun `aamod$captureUniformSlice`(name: String, buffer: GpuBufferSlice, callback: CallbackInfo) {
        aamodFrozenPass?.uniform(name, buffer)
    }

    @Inject(method = ["bindTexture"], at = [At("TAIL")])
    private fun `aamod$captureTexture`(name: String, texture: GpuTextureView?, sampler: GpuSampler?, callback: CallbackInfo) {
        aamodFrozenPass?.texture(name, texture, sampler)
    }

    @Inject(method = ["enableScissor"], at = [At("TAIL")])
    private fun `aamod$captureScissor`(left: Int, top: Int, width: Int, height: Int, callback: CallbackInfo) {
        aamodFrozenPass?.scissor(FrozenScissor(left, top, width, height))
    }

    @Inject(method = ["disableScissor"], at = [At("TAIL")])
    private fun `aamod$disableScissor`(callback: CallbackInfo) { aamodFrozenPass?.scissor(null) }

    @Inject(method = ["draw"], at = [At("TAIL")])
    private fun `aamod$captureDraw`(count: Int, instances: Int, first: Int, firstInstance: Int, callback: CallbackInfo) {
        aamodFrozenPass?.record(FrozenDrawCommand.Direct(false, count, instances, first, 0, firstInstance))
    }

    @Inject(method = ["drawIndexed"], at = [At("TAIL")])
    private fun `aamod$captureIndexed`(
        count: Int, instances: Int, first: Int, vertexOffset: Int, firstInstance: Int, callback: CallbackInfo
    ) {
        aamodFrozenPass?.record(FrozenDrawCommand.Direct(true, count, instances, first, vertexOffset, firstInstance))
    }

    @Inject(method = ["multiDraw(Ljava/nio/IntBuffer;III)V"], at = [At("TAIL")])
    private fun `aamod$captureMultiDraw`(parameters: IntBuffer, instances: Int, firstInstance: Int, count: Int, callback: CallbackInfo) {
        aamodFrozenPass?.interleaved(false, parameters, instances, firstInstance, count)
    }

    @Inject(method = ["multiDrawIndexed(Ljava/nio/IntBuffer;III)V"], at = [At("TAIL")])
    private fun `aamod$captureMultiIndexed`(parameters: IntBuffer, instances: Int, firstInstance: Int, count: Int, callback: CallbackInfo) {
        aamodFrozenPass?.interleaved(true, parameters, instances, firstInstance, count)
    }

    @Inject(method = ["multiDraw(Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V"], at = [At("TAIL")])
    private fun `aamod$captureSeparate`(vertices: IntBuffer, counts: IntBuffer, count: Int, callback: CallbackInfo) {
        aamodFrozenPass?.separate(null, counts, vertices, count)
    }

    @Inject(method = ["multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V"], at = [At("TAIL")])
    private fun `aamod$captureSeparateIndexed`(
        offsets: PointerBuffer, counts: IntBuffer, vertices: IntBuffer, count: Int, callback: CallbackInfo
    ) { aamodFrozenPass?.separate(offsets, counts, vertices, count) }

    @Inject(method = ["drawIndirect"], at = [At("TAIL")])
    private fun `aamod$captureIndirect`(commands: GpuBufferSlice, count: Int, callback: CallbackInfo) {
        aamodFrozenPass?.record(FrozenDrawCommand.Indirect(false, commands, count))
    }

    @Inject(method = ["drawIndexedIndirect"], at = [At("TAIL")])
    private fun `aamod$captureIndexedIndirect`(commands: GpuBufferSlice, count: Int, callback: CallbackInfo) {
        aamodFrozenPass?.record(FrozenDrawCommand.Indirect(true, commands, count))
    }

    @WrapMethod(method = ["drawMultipleIndexed"])
    private fun <Value : Any> `aamod$captureMultiple`(
        draws: Collection<RenderPass.Draw<Value>>, defaultIndices: GpuBuffer?, defaultType: IndexType?,
        dynamicUniforms: Collection<String>, argument: Value, original: Operation<Void>
    ) {
        val recorder = aamodFrozenPass
        if (recorder == null) original.call(draws, defaultIndices, defaultType, dynamicUniforms, argument)
        else recorder.captureMultiple(draws, defaultIndices, defaultType, dynamicUniforms) { frozen ->
            original.call(frozen, defaultIndices, defaultType, dynamicUniforms, argument)
        }
    }

    @Inject(method = ["close"], at = [At("TAIL")])
    private fun `aamod$finishCapture`(callback: CallbackInfo) {
        val recorder = aamodFrozenPass ?: return
        aamodFrozenPass = null
        recorder.finish()
    }
}
