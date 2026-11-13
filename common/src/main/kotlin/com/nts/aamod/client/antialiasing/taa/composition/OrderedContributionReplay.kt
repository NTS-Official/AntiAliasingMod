package com.nts.aamod.client.antialiasing.taa.composition

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.RenderSystem
import com.nts.aamod.client.antialiasing.taa.effects.EffectStage
import com.nts.aamod.client.antialiasing.taa.effects.EffectStageTimings
import org.joml.Vector4f
import java.util.IdentityHashMap
import java.util.Optional

class OrderedContributionReplay @JvmOverloads constructor(private val timings: EffectStageTimings? = null) : AutoCloseable {
    private var contribution: RenderTarget? = null
    private var entry: RenderTarget? = null
    private val pipelines = IdentityHashMap<RenderPipeline, RenderPipeline>()
    private var pending: FrozenPassRecorder? = null
    private val occurrences = linkedMapOf<Triple<String, String, String>, Int>()
    val allocatedBytes: Long get() = listOfNotNull(contribution, entry).sumOf { it.width.toLong() * it.height * 8 }

    fun reserve(width: Int, height: Int, availableBytes: Long) {
        require(width > 0 && height > 0)
        val required = Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), 16L)
        check(required <= availableBytes) { "history_budget" }
        if (contribution?.let { it.width == width && it.height == height } == true) return
        check(pending == null) { "Cannot resize an active contribution capture" }
        releaseTargets()
        try {
            contribution = target("aamod ordered contribution", width, height)
            entry = target("aamod ordered entry snapshot", width, height)
        } catch (exception: Exception) { releaseTargets(); throw exception }
    }

    fun beginFrame() {
        check(pending == null) { "An ordered contribution pass was not closed" }
        occurrences.clear()
    }

    fun preserveNativeColor(source: RenderTarget): RenderTarget {
        check(pending == null) { "Cannot preserve native color during a contribution capture" }
        val snapshot = checkNotNull(contribution)
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
            checkNotNull(source.colorTexture), checkNotNull(snapshot.colorTexture),
            0, 0, 0, 0, 0, source.width, source.height)
        return snapshot
    }

    fun capture(
        descriptor: RenderPassDescriptor, phase: String, target: String,
        consume: (ContributionInterval, RenderTarget, RenderTarget) -> Unit,
        fail: (String, Exception?) -> Unit
    ): FrozenPassRecorder? {
        val output = contribution ?: return null
        val entrance = checkNotNull(entry)
        if (pending != null) { fail("composition_concurrent_pass", null); return null }
        val color = descriptor.colorAttachments.singleOrNull()
        val depth = descriptor.depthAttachment
        val area = descriptor.renderArea
        if (color == null || color.textureView().texture().format != GpuFormat.RGBA8_UNORM ||
            depth == null || depth.textureView().texture().format != GpuFormat.D32_FLOAT || area == null ||
            color.textureView().getWidth(0) != output.width || color.textureView().getHeight(0) != output.height ||
            color.clearValue().isPresent
        ) { fail("composition_pass_unsupported", null); return null }
        val recorder = FrozenPassRecorder()
        try {
            measure {
                val encoder = RenderSystem.getDevice().createCommandEncoder()
                if (depth.clearValue().isPresent) encoder.clearDepthTexture(checkNotNull(entrance.depthTexture), depth.clearValue().asDouble)
                else encoder.copyTextureToTexture(depth.textureView().texture(), checkNotNull(entrance.depthTexture),
                    depth.textureView().baseMipLevel(), 0, 0, 0, 0, output.width, output.height)
                output.copyDepthFrom(entrance)
            }
        } catch (exception: Exception) { fail("composition_entry_depth_failed", exception); return null }
        pending = recorder
        recorder.onFinished = { frozen ->
            pending = null
            TemporalCommandCapture.suppress {
                try {
                    val plan = ContributionIntervals.create(phase, target, frozen, color.textureView().texture())
                    if (plan.failure != null) { fail(plan.failure, null); return@suppress }
                    else for (interval in plan.intervals) {
                        val material = Triple(phase, target, interval.key.material)
                        val occurrence = occurrences.getOrDefault(material, 0)
                        occurrences[material] = occurrence + 1
                        val actualInterval = ContributionInterval(interval.key.copy(occurrence = occurrence), interval.blend, interval.draws)
                        val encoder = RenderSystem.getDevice().createCommandEncoder()
                        val resolvedPipelines = interval.draws.associate { draw ->
                            val original = draw.state.pipeline
                            val pipeline = if (interval.blend == ContributionBlend.DEPTH_ONLY) original
                            else pipelines[original] ?: run {
                                check(pipelines.size < 1024) { "capture_budget" }
                                val replacement = ContributionIntervals.contributionPipeline(original)
                                check(RenderSystem.getDevice().precompilePipeline(replacement).isValid) { "Could not compile contribution pipeline" }
                                pipelines[original] = replacement
                                replacement
                            }
                            original to pipeline
                        }
                        measure {
                            encoder.clearColorTexture(checkNotNull(output.colorTexture), Vector4f(0f))
                            val replay = RenderPassDescriptor.create { "aamod ${interval.key.phase} contribution" }
                                .withColorAttachment(checkNotNull(output.colorTextureView), Optional.empty())
                                .withDepthAttachment(checkNotNull(output.depthTextureView)).withRenderArea(area)
                            encoder.createRenderPass(replay).use { pass ->
                                for (draw in interval.draws) {
                                    draw.replay(pass, resolvedPipelines.getValue(draw.state.pipeline))
                                }
                            }
                        }
                        if (interval.blend != ContributionBlend.DEPTH_ONLY) consume(actualInterval, output, entrance)
                        measure { entrance.copyDepthFrom(output) }
                    }
                } catch (exception: Exception) { fail("composition_replay_failed", exception) }
            }
        }
        return recorder
    }

    private fun measure(action: () -> Unit) {
        if (timings == null) action() else timings.measure(EffectStage.REPLAY, action)
    }

    private fun target(name: String, width: Int, height: Int): RenderTarget {
        val result = object : RenderTarget(name, true, GpuFormat.RGBA8_UNORM) {}
        try { result.createBuffers(width, height) }
        catch (exception: Exception) { result.destroyBuffers(); throw exception }
        return result
    }

    private fun releaseTargets() {
        contribution?.destroyBuffers()
        entry?.destroyBuffers()
        contribution = null
        entry = null
    }

    override fun close() {
        pending?.onFinished = null
        pending = null
        releaseTargets()
        pipelines.clear()
        occurrences.clear()
    }
}
