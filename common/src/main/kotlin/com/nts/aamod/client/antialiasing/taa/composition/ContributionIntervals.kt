package com.nts.aamod.client.antialiasing.taa.composition

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.BlendFactor
import com.mojang.blaze3d.platform.BlendOp
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuSampler
import java.util.WeakHashMap
import java.util.Optional

private class ContributionPipeline(source: RenderPipeline, state: ColorTargetState) : RenderPipeline(
    source.location.withSuffix("/aamod_contribution"), source.vertexShader, source.fragmentShader, source.shaderDefines,
    source.bindGroupLayouts, arrayOf(state), source.depthStencilState, source.polygonMode, source.isCull,
    source.vertexFormatBindings.copyOf(), source.primitiveTopology, source.sortKey
)

enum class ContributionBlend { ALPHA_OVER, ADDITIVE, DEPTH_ONLY, UNSUPPORTED }

data class ContributionIntervalKey(val phase: String, val target: String, val material: String, val occurrence: Int) {
    init { require(phase.isNotEmpty() && target.isNotEmpty() && material.isNotEmpty() && occurrence >= 0) }
}

class ContributionInterval(
    val key: ContributionIntervalKey, val blend: ContributionBlend, draws: List<FrozenDraw>
) {
    val draws: List<FrozenDraw> = java.util.Collections.unmodifiableList(ArrayList(draws))
}

class ContributionIntervals private constructor(val intervals: List<ContributionInterval>, val failure: String?) {
    companion object {
        private val textureIds = WeakHashMap<GpuTexture, Long>()
        private val samplerIds = WeakHashMap<GpuSampler, Long>()
        private val pipelineIds = WeakHashMap<RenderPipeline, Long>()
        private var nextResourceId = 1L

        private fun material(state: FrozenDrawState): String = buildString {
            append(state.pipeline.location)
            append('#').append(pipelineIds.getOrPut(state.pipeline) { nextResourceId++ })
            append('|').append(state.pipeline.primitiveTopology)
            for ((name, binding) in state.textures.toSortedMap()) {
                append('|').append(name).append('=')
                binding.view?.let { view ->
                    append(textureIds.getOrPut(view.texture()) { nextResourceId++ })
                    append(':').append(view.baseMipLevel()).append(':').append(view.mipLevels())
                } ?: append("none")
                append(':').append(binding.sampler?.let { samplerIds.getOrPut(it) { nextResourceId++ } } ?: 0L)
            }
        }

        fun blend(pipeline: RenderPipeline): ContributionBlend {
            val states = pipeline.colorTargetStates
            if (states.size != 1) return ContributionBlend.UNSUPPORTED
            val state = states[0] ?: return ContributionBlend.UNSUPPORTED
            if (state.writeMask() == ColorTargetState.WRITE_NONE) return ContributionBlend.DEPTH_ONLY
            if (state.writeMask() and ColorTargetState.WRITE_COLOR != ColorTargetState.WRITE_COLOR) return ContributionBlend.UNSUPPORTED
            val blend = state.blendFunction().orElse(null) ?: return ContributionBlend.UNSUPPORTED
            val color = blend.color()
            if (color.op() != BlendOp.ADD) return ContributionBlend.UNSUPPORTED
            if (color.destFactor() == BlendFactor.ONE && color.sourceFactor() in setOf(
                    BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA,
                    BlendFactor.SRC_COLOR, BlendFactor.ONE_MINUS_SRC_COLOR
                )) return ContributionBlend.ADDITIVE
            if (color.destFactor() == BlendFactor.ONE_MINUS_SRC_ALPHA &&
                color.sourceFactor() in setOf(BlendFactor.ONE, BlendFactor.SRC_ALPHA)
            ) return ContributionBlend.ALPHA_OVER
            return ContributionBlend.UNSUPPORTED
        }

        fun create(phase: String, target: String, recorder: FrozenPassRecorder, output: GpuTexture?): ContributionIntervals {
            recorder.failure?.let { return ContributionIntervals(emptyList(), it) }
            val intervals = mutableListOf<ContributionInterval>()
            val occurrences = linkedMapOf<String, Int>()
            var current = mutableListOf<FrozenDraw>()
            var currentBlend: ContributionBlend? = null
            var material = ""
            fun flush() {
                val blend = currentBlend ?: return
                if (current.isEmpty()) return
                val occurrence = occurrences.getOrDefault(material, 0)
                occurrences[material] = occurrence + 1
                intervals.add(ContributionInterval(ContributionIntervalKey(phase, target, material, occurrence), blend, current))
                current = mutableListOf()
            }
            for (draw in recorder.draws) {
                val blend = blend(draw.state.pipeline)
                if (blend == ContributionBlend.UNSUPPORTED) return ContributionIntervals(emptyList(), "composition_blend_unsupported")
                if (output != null && draw.state.textures.values.any { it.view?.texture() === output }) {
                    return ContributionIntervals(emptyList(), "composition_destination_read")
                }
                val nextMaterial = material(draw.state)
                if (blend != currentBlend || material != nextMaterial) {
                    flush()
                    currentBlend = blend
                    material = nextMaterial
                }
                current.add(draw)
            }
            flush()
            return ContributionIntervals(java.util.Collections.unmodifiableList(intervals), null)
        }

        fun contributionPipeline(source: RenderPipeline): RenderPipeline {
            check(blend(source) in setOf(ContributionBlend.ALPHA_OVER, ContributionBlend.ADDITIVE))
            val native = checkNotNull(source.colorTargetState)
            val nativeBlend = native.blendFunction().orElseThrow()
            val coverage = BlendFunction(nativeBlend.color(), BlendFunction.TRANSLUCENT.alpha())
            return ContributionPipeline(source, ColorTargetState(Optional.of(coverage), native.format(), ColorTargetState.WRITE_ALL))
        }
    }
}
