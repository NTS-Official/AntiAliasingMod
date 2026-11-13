package com.nts.aamod.client.antialiasing.taa.composition

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.resource.GraphicsResourceAllocator
import com.nts.aamod.AAMod
import com.nts.aamod.client.antialiasing.taa.JitterPoint
import com.nts.aamod.client.antialiasing.taa.effects.EffectDomain
import com.nts.aamod.client.antialiasing.taa.effects.EffectDraw
import com.nts.aamod.client.antialiasing.taa.effects.EffectHistoryKey
import com.nts.aamod.client.antialiasing.taa.effects.EffectLayerHistoryPool
import com.nts.aamod.client.antialiasing.taa.effects.EffectMotionPass
import com.nts.aamod.client.antialiasing.taa.effects.EffectRejection
import com.nts.aamod.client.antialiasing.taa.effects.EffectStage
import com.nts.aamod.client.antialiasing.taa.effects.EffectStageTimings
import com.nts.aamod.client.antialiasing.taa.effects.TemporalEffects
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.PostChain
import net.minecraft.resources.Identifier
import java.util.IdentityHashMap

class TemporalSceneComposition : AutoCloseable {
    val timings = EffectStageTimings()
    private val replay = OrderedContributionReplay(timings)
    private val motion = EffectMotionPass()
    private val histories = EffectLayerHistoryPool()
    private val colors = TemporalColorPass()
    private val alignment = TemporalTargetAlignment()
    private val sorted = VanillaSortedTargets()
    private val sortedTargets = linkedMapOf<Identifier, RenderTarget>()
    private var accumulated: RenderTarget? = null
    private var nativeMain: RenderTarget? = null
    private var nativeRecovery: (() -> Unit)? = null
    private var opaque: RenderTarget? = null
    private var frame = -1L
    private var budget = 0L
    private var externalBytes: () -> Long = { 0L }
    private var current = JitterPoint(0.0, 0.0)
    private var previous = current
    private var samples = 16
    private var chain: PostChain? = null
    private var preparedSorted = false
    private var running = false
    private var reportedBackend = "COMBINED_WORLD"
    private val consumed = IdentityHashMap<EffectDraw, Boolean>()
    private val resolvedDomains = linkedSetOf<EffectDomain>()
    private val captureBudgetDomains = linkedSetOf<EffectDomain>()
    private val captureDowngrades = linkedMapOf<EffectDomain, EffectRejection>()
    private var latchedFailure: String? = null
    var failure: String? = null
        private set
    var currentOnlyLayers = 0
        private set
    var preparedLayers = 0
        private set
    var composeCpuNanos = 0L
        private set
    val backend: String get() = reportedBackend
    val allocatedBytes: Long get() = replay.allocatedBytes + motion.allocatedBytes + histories.allocatedBytes +
        colors.allocatedBytes + alignment.allocatedBytes + (accumulated?.let { it.width.toLong() * it.height * 4 } ?: 0L) +
        sortedTargets.values.sumOf { it.width.toLong() * it.height * 8 } + timings.allocatedBytes
    val output: RenderTarget? get() = if (!running || failure != null) null else if (chain == null) accumulated
        else sortedTargets[LevelTargetBundle.MAIN_TARGET_ID].takeIf { preparedSorted }
    val activeDomains: Set<EffectDomain> get() = histories.historicalDomains
    val historyBudgetDomains: Set<EffectDomain> get() = histories.budgetRejectedDomains
    fun sampleHistoryTelemetry(): String = histories.telemetry.sampleDiagnostic()
    val rejectedDomains: Set<EffectDomain> get() = captureBudgetDomains.toSet()
    val captureRejections: Map<EffectDomain, EffectRejection> get() = captureDowngrades.toMap()

    fun beginFrame(frameId: Long, budgetBytes: Long, externalBytes: () -> Long) {
        endFrame()
        frame = frameId
        budget = budgetBytes
        this.externalBytes = externalBytes
        chain = null
        preparedSorted = false
        failure = latchedFailure
        currentOnlyLayers = 0
        preparedLayers = 0
        composeCpuNanos = 0L
        consumed.clear()
        resolvedDomains.clear()
        captureBudgetDomains.clear()
        captureDowngrades.clear()
        reportedBackend = if (failure == null) "COMBINED_WORLD" else "CURRENT_FRAME_FALLBACK"
        if (failure != null) return
        motion.beginFrame()
        colors.beginFrame()
        alignment.beginFrame()
        timings.beginFrame(frameId)
    }

    fun configureSorted(chain: PostChain) {
        check(!running) { "Cannot change temporal composition while rendering" }
        this.chain = chain
        if (!NativeTransparencyContract.supported(chain)) fail("composition_contract_unsupported", null)
    }

    fun renderOpaqueMotion(source: RenderTarget, destination: RenderTarget): Boolean {
        if (failure != null) return false
        if (EffectDomain.OPAQUE in TemporalEffects.rejectedDomains) {
            captureBudgetDomains.add(EffectDomain.OPAQUE)
            captureDowngrades[EffectDomain.OPAQUE] = TemporalEffects.rejectedReasons.getValue(EffectDomain.OPAQUE)
            return false
        }
        val draws = TemporalEffects.draws.filter { it.correspondence.current.domain == EffectDomain.OPAQUE }
        if (draws.isEmpty()) return true
        val groups = draws.map { it.quad.layer }.toSet().size
        check(allocatedBytes + externalBytes() + motion.additionalUploadBytes(draws.size, groups) <= budget) { "history_budget" }
        timings.measure(EffectStage.MOTION) { motion.renderWorld(source, destination, draws) }
        resolvedDomains.add(EffectDomain.OPAQUE)
        checkBudget()
        return true
    }

    fun start(nativeMain: RenderTarget, opaque: RenderTarget, stableWorld: RenderTarget, current: JitterPoint, previous: JitterPoint, samples: Int) {
        if (frame < 0 || failure != null) return
        try {
            val pixels = nativeMain.width.toLong() * nativeMain.height
            check(pixels * (if (chain == null) 52 else 100) + externalBytes() + 1048576 <= budget) { "history_budget" }
            timings.reserve((budget - externalBytes() - allocatedBytes - pixels * (if (chain == null) 52 else 100) - 1048576).coerceAtLeast(0))
            if (accumulated?.let { it.width != nativeMain.width || it.height != nativeMain.height } != false) {
                releaseTargets()
                accumulated = target("aamod temporal ordered world", nativeMain.width, nativeMain.height, false)
            }
            if (chain != null) {
                for (id in LevelTargetBundle.SORTING_TARGETS) sortedTargets.getOrPut(id) {
                    target("aamod temporal sorted $id", nativeMain.width, nativeMain.height, true)
                }
            } else {
                sortedTargets.values.forEach { it.destroyBuffers() }
                sortedTargets.clear()
            }
            replay.reserve(nativeMain.width, nativeMain.height, budget - externalBytes() - allocatedBytes + replay.allocatedBytes)
            replay.beginFrame()
            this.nativeMain = nativeMain
            this.opaque = opaque
            this.current = current
            this.previous = previous
            this.samples = samples
            histories.beginFrame(frame, nativeMain.width, nativeMain.height,
                (budget - externalBytes() - allocatedBytes + histories.allocatedBytes -
                    motion.additionalAttachmentBytes(nativeMain.width, nativeMain.height) - 1048576).coerceAtLeast(0))
            timings.measure(EffectStage.COMPOSE) { colors.draw(stableWorld, checkNotNull(accumulated)) }
            running = true
            reportedBackend = if (chain == null) "ORDERED_CONTRIBUTIONS" else "VANILLA_SIX_TARGETS"
            checkBudget()
            TemporalCommandCapture.begin { descriptor ->
                if (!running || failure != null || descriptor.colorAttachments.none { it?.textureView()?.texture() === nativeMain.colorTexture }) null
                else replay.capture(descriptor, "after_opaque", "main", ::consume, ::fail)
            }
        } catch (exception: Exception) { fail("composition_prepare_failed", exception) }
    }

    private fun checkBudget() {
        check(allocatedBytes + externalBytes() <= budget) { "history_budget" }
    }

    private fun consume(interval: ContributionInterval, contribution: RenderTarget, entrance: RenderTarget) {
        if (failure != null) return
        val started = System.nanoTime()
        val draws = TemporalEffects.draws.filter { effect ->
            !consumed.containsKey(effect) && interval.draws.any { native ->
                native.state.pipeline === effect.quad.layer.pipeline() &&
                    (effect.quad.source?.let { NativeEffectDraws.matches(it, native) } ?: true) &&
                    native.state.textures["Sampler0"]?.view?.texture().let { bound ->
                        bound == null || bound === Minecraft.getInstance().textureManager.getTexture(effect.quad.layer.textureAtlasLocation()).texture
                    }
            }
        }
        draws.forEach { consumed[it] = true }
        val domain = draws.firstOrNull()?.correspondence?.current?.domain ?: EffectDomain.TRANSLUCENT
        val key = EffectHistoryKey(domain, interval.key)
        val stable = resolve(key, contribution, entrance, draws)
        timings.measure(EffectStage.COMPOSE) {
            colors.draw(stable ?: contribution, checkNotNull(accumulated), interval.blend,
                if (stable == null) current else JitterPoint(0.0, 0.0))
        }
        checkBudget()
        composeCpuNanos += System.nanoTime() - started
    }

    private fun resolve(key: EffectHistoryKey, source: RenderTarget, entry: RenderTarget, draws: List<EffectDraw>): RenderTarget? {
        if (key.domain in TemporalEffects.rejectedDomains) {
            captureBudgetDomains.add(key.domain)
            captureDowngrades[key.domain] = TemporalEffects.rejectedReasons.getValue(key.domain)
            histories.resetHistory(key)
            currentOnlyLayers++
            return null
        }
        if (draws.isEmpty()) { currentOnlyLayers++; return null }
        val groups = draws.map { it.quad.layer }.toSet().size
        val attachmentBytes = motion.additionalAttachmentBytes(source.width, source.height)
        val requiredBytes = allocatedBytes - histories.allocatedBytes + attachmentBytes +
            motion.additionalUploadBytes(draws.size, groups) + externalBytes() + 49152
        if (!histories.reserveHistory(key, (budget - requiredBytes).coerceAtLeast(0))) {
            currentOnlyLayers++
            return null
        }
        timings.measure(EffectStage.MOTION) { motion.render(source, entry, draws) }
        histories.limitBudget((budget - externalBytes() - allocatedBytes + histories.allocatedBytes - 49152).coerceAtLeast(0))
        val stable = timings.measure(EffectStage.RESOLVE) {
            histories.prepare(key, source, motion.motion, motion.identity, motion.conflict, current, previous, samples)
        }
        if (stable == null) currentOnlyLayers++ else {
            preparedLayers++
            resolvedDomains.add(key.domain)
        }
        return stable
    }

    fun prepareSorted(inputs: Map<Identifier, RenderTarget>, allocator: GraphicsResourceAllocator) {
        val chain = chain ?: return
        if (!running || failure != null) return
        val started = System.nanoTime()
        try {
            TemporalCommandCapture.end()
            check(inputs.keys == LevelTargetBundle.SORTING_TARGETS)
            val domains = mapOf(
                LevelTargetBundle.TRANSLUCENT_TARGET_ID to EffectDomain.TRANSLUCENT,
                LevelTargetBundle.ITEM_ENTITY_TARGET_ID to EffectDomain.ITEM_ENTITY,
                LevelTargetBundle.PARTICLES_TARGET_ID to EffectDomain.PARTICLES,
                LevelTargetBundle.WEATHER_TARGET_ID to EffectDomain.WEATHER,
                LevelTargetBundle.CLOUDS_TARGET_ID to EffectDomain.CLOUDS
            )
            for ((id, source) in inputs) {
                val stable = if (id == LevelTargetBundle.MAIN_TARGET_ID) accumulated else {
                    val domain = domains.getValue(id)
                    resolve(EffectHistoryKey(domain), source, checkNotNull(opaque), TemporalEffects.draws.filter {
                        it.correspondence.current.domain == domain && !consumed.containsKey(it)
                    })
                }
                timings.measure(EffectStage.COMPOSE) { alignment.align(source, stable, sortedTargets.getValue(id), current) }
            }
            verifyCaptureBudget()
            if (failure != null) return
            timings.measure(EffectStage.COMPOSE) { sorted.compose(chain, sortedTargets, allocator) }
            checkBudget()
            preparedSorted = true
        } catch (exception: Exception) { fail("composition_sorted_failed", exception) }
        composeCpuNanos += System.nanoTime() - started
    }

    fun commit() {
        check(output != null) { "No temporal scene output to commit" }
        histories.commit()
    }

    fun finishCapture() {
        TemporalCommandCapture.end()
        verifyCaptureBudget()
        if (output != null) {
            try {
                timings.measure(EffectStage.COMPOSE) {
                    val target = checkNotNull(nativeMain)
                    nativeRecovery = colors.prepareNativeRecovery(replay.preserveNativeColor(target), target, current)
                }
                checkBudget()
            } catch (exception: Exception) { fail("composition_native_snapshot_failed", exception) }
        }
    }

    fun restoreNativeColor() {
        timings.measure(EffectStage.COMPOSE) { checkNotNull(nativeRecovery).invoke() }
    }

    private fun verifyCaptureBudget() {
        val rejected = TemporalEffects.rejectedDomains
        captureBudgetDomains.addAll(rejected)
        captureDowngrades.putAll(TemporalEffects.rejectedReasons)
        val late = resolvedDomains.intersect(rejected)
        if (late.isNotEmpty()) fail(if (late.any { captureDowngrades[it] == EffectRejection.CAPTURE_BUDGET })
            "capture_budget" else "effect_source_unavailable", null)
    }

    fun abort() { histories.abort() }

    fun reject(reason: String, exception: Exception? = null) { fail(reason, exception) }

    fun resetHistory() {
        histories.resetHistory()
    }

    fun clearFailure() { latchedFailure = null; failure = null }

    private fun fail(reason: String, exception: Exception?) {
        if (failure != null) return
        failure = exception?.message?.takeIf { it == "history_budget" || it == "capture_budget" } ?: reason
        if (failure !in setOf("history_budget", "capture_budget", "draw_capture_budget", "draw_parameter_budget",
                "effect_source_unavailable", "composition_input_missing")) latchedFailure = failure
        reportedBackend = "CURRENT_FRAME_FALLBACK"
        TemporalCommandCapture.end()
        histories.abort()
        histories.endFrame()
        motion.endFrame()
        colors.endFrame()
        alignment.endFrame()
        timings.endFrame()
        if (exception != null) AAMod.LOGGER.error("Temporal scene composition failed; keeping native current-frame output", exception)
    }

    fun endFrame() {
        TemporalCommandCapture.end()
        histories.abort()
        histories.endFrame()
        motion.endFrame()
        colors.endFrame()
        alignment.endFrame()
        timings.endFrame()
        nativeMain = null
        nativeRecovery = null
        opaque = null
        running = false
        frame = -1L
        if (failure != null) releaseResources()
    }

    private fun target(name: String, width: Int, height: Int, depth: Boolean): RenderTarget {
        val target = object : RenderTarget(name, depth, GpuFormat.RGBA8_UNORM) {}
        try { target.createBuffers(width, height) }
        catch (exception: Exception) { target.destroyBuffers(); throw exception }
        return target
    }

    private fun releaseTargets() {
        accumulated?.destroyBuffers()
        accumulated = null
        sortedTargets.values.forEach { it.destroyBuffers() }
        sortedTargets.clear()
    }

    private fun releaseResources() {
        releaseTargets()
        replay.close()
        motion.close()
        histories.close()
        colors.close()
        alignment.close()
        timings.close()
    }

    override fun close() {
        endFrame()
        releaseResources()
        resetHistory()
        clearFailure()
        chain = null
        failure = null
        reportedBackend = "COMBINED_WORLD"
    }
}
