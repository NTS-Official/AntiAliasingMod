package com.nts.aamod.client.antialiasing.taa.motion

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexConsumer
import com.nts.aamod.client.mixin.render.motion.RenderSetupAccess
import com.nts.aamod.client.mixin.render.motion.RenderTypeAccess
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer
import net.minecraft.client.renderer.feature.CustomFeatureRenderer
import net.minecraft.client.renderer.feature.ItemFeatureRenderer
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderType
import org.joml.Matrix4f
import java.util.WeakHashMap

internal class MotionMaterial(val type: RenderType, val prepared: PreparedRenderType, val matrix: Matrix4f) {
    val textureMatrix: Matrix4f = if ("APPLY_TEXTURE_MATRIX" in type.pipeline().shaderDefines.flags()) {
        ((type as RenderTypeAccess).`aamod$getState`() as Any as RenderSetupAccess).`aamod$getTextureTransform`().createMatrix()
    } else Matrix4f()
    val cutoff = type.pipeline().shaderDefines.values()["ALPHA_CUTOUT"]?.removeSuffix("F")?.removeSuffix("f")?.toFloatOrNull() ?: 0f
    val conservative = type.hasBlending() || "DISSOLVE" in type.pipeline().shaderDefines.flags()
}

internal class MotionDraw(val batch: MotionBatch, val current: FloatArray, val previous: FloatArray?, val valid: Boolean)

object MotionCapture {
    private const val MAX_VERTICES = 262144
    private val owners = WeakHashMap<Any, MotionOwner>()
    private val materials = mutableMapOf<RenderType, MotionMaterial>()
    private val consumers = linkedSetOf<MotionVertexConsumer>()
    private val draws = mutableListOf<MotionDraw>()
    private val rejectedSections = linkedMapOf<MotionVertexConsumer, MotionSection>()
    private val pending = mutableListOf<Triple<MotionOwner, GeometryKey, VertexHistory>>()
    private val renderer = MotionVectorRenderer()
    private var frame = -1L
    private var epoch = 0L
    private var projection: Matrix4f? = null
    private var owner: MotionOwner? = null
    private var submission: MotionSubmission? = null
    internal var section: MotionSection? = null
        private set
    var active = false
        private set
    var vertices = 0
        private set
    var validBatches = 0
        private set
    var rejectedBatches = 0
        private set
    var overflow = false
        private set
    val allocatedBytes: Long
        get() = renderer.allocatedBytes

    fun beginFrame(frameId: Long) {
        endFrame()
        active = true
        frame = frameId
        owners.entries.removeIf { entry ->
            entry.value.history.entries.removeIf { it.value.frame != frameId - 1 }
            entry.value.history.isEmpty()
        }
        projection = null
        vertices = 0
        validBatches = 0
        rejectedBatches = 0
        overflow = false
    }

    fun setProjection(matrix: Matrix4f) { projection = Matrix4f(matrix) }

    fun owner(instance: Any): MotionOwner? = if (active) owners.getOrPut(instance) { MotionOwner() } else null

    fun submission(node: Any): MotionSubmission? {
        val currentOwner = owner ?: return null
        val model = when (node) {
            is ModelFeatureRenderer.Submit<*> -> node.model()
            is ItemFeatureRenderer.Submit -> node.displayContext()
            is BlockModelFeatureRenderer.Submit -> BlockModelFeatureRenderer.TYPE
            is CustomFeatureRenderer.Submit -> node.customGeometryRenderer().javaClass
            else -> return null
        }
        return currentOwner.submission(frame, SubmissionKind(node.javaClass, IdentityKey(model)))
    }

    fun <Result> withOwner(next: MotionOwner?, action: () -> Result): Result {
        val previous = owner
        owner = if (active) next else null
        try { return action() } finally { owner = previous }
    }

    fun <Result> withSubmission(node: Any, action: () -> Result): Result {
        val previous = submission
        submission = if (active) (node as MotionSubmissionAccess).aamodMotionSubmission else null
        try { return action() } finally { submission = previous }
    }

    fun <Result> withGeometry(geometry: Any, pose: Matrix4f?, reliable: Boolean, action: () -> Result): Result {
        val current = submission
        if (!active || current == null || projection == null) return action()
        val previous = section
        flush()
        val captured = MotionSection(geometry, pose, reliable)
        section = captured
        try { return action() } finally {
            flush()
            section = previous
            finish(current, captured)
        }
    }

    fun wrap(consumer: VertexConsumer, type: RenderType): VertexConsumer {
        if (!active || projection == null || type.isOutline() ||
            type.outputTarget() == OutputTarget.OUTLINE_TARGET || "glint" in type.pipeline().location.path
        ) return consumer
        if (type.primitiveTopology() != PrimitiveTopology.QUADS && type.primitiveTopology() != PrimitiveTopology.TRIANGLES) {
            if (submission != null) overflow = true
            return consumer
        }
        return MotionVertexConsumer(consumer, type)
    }

    internal fun section(consumer: MotionVertexConsumer, type: RenderType): MotionSection? {
        if (!active || projection == null) return null
        return section ?: rejectedSections.getOrPut(consumer) { MotionSection(type, null, false) }
    }

    internal fun touch(consumer: MotionVertexConsumer) { consumers.add(consumer) }

    internal fun vertex(section: MotionSection, type: RenderType, values: FloatArray) {
        if (vertices >= MAX_VERTICES || values.any { !it.isFinite() } ||
            type.primitiveTopology() != PrimitiveTopology.QUADS && type.primitiveTopology() != PrimitiveTopology.TRIANGLES
        ) {
            overflow = true
            return
        }
        val material = materials.getOrPut(type) {
            val state = (type as RenderTypeAccess).`aamod$getState`() as Any as RenderSetupAccess
            val modelView = RenderSystem.getModelViewMatrixCopy()
            state.`aamod$getLayeringTransform`().modifier?.accept(modelView)
            MotionMaterial(type, type.prepare(), Matrix4f(checkNotNull(projection)).mul(modelView))
        }
        section.batches.getOrPut(material) { MotionBatch(material) }.append(values, section.inversePose)
        vertices++
    }

    private fun flush() {
        val dirty = consumers.toList()
        consumers.clear()
        dirty.forEach { it.flush() }
    }

    private fun finish(submission: MotionSubmission, section: MotionSection) {
        for ((material, batch) in section.batches) {
            if (draws.size >= 8192) { overflow = true; return }
            val key = submission.key(section.geometry, material.type)
            val geometry = batch.geometry()
            val prior = submission.owner.history[key]
            val clips = batch.clips(material.matrix)
            val primitive = if (material.type.primitiveTopology() == PrimitiveTopology.QUADS) 4 else 3
            val wellFormed = batch.count % primitive == 0 && clips.all { it.isFinite() }
            if (!wellFormed) { overflow = true; continue }
            val valid = section.reliable && section.inversePose != null && !material.conservative &&
                prior?.corresponds(frame, epoch, geometry) == true
            draws.add(MotionDraw(batch, clips, prior?.clip, valid))
            if (valid) validBatches++ else rejectedBatches++
            pending.add(Triple(submission.owner, key, VertexHistory(frame, epoch, clips, geometry)))
        }
    }

    fun render(target: RenderTarget): RenderTarget {
        flush()
        for (section in rejectedSections.values) {
            for (batch in section.batches.values) {
                if (draws.size >= 8192) { overflow = true; break }
                val clips = batch.clips(batch.material.matrix)
                val primitive = if (batch.material.type.primitiveTopology() == PrimitiveTopology.QUADS) 4 else 3
                if (batch.count % primitive != 0 || clips.any { !it.isFinite() }) { overflow = true; continue }
                draws.add(MotionDraw(batch, clips, null, false))
                rejectedBatches++
            }
        }
        rejectedSections.clear()
        return renderer.render(target, draws.sortedBy { if (it.valid) 0 else if (it.batch.material.conservative) 2 else 1 })
    }

    fun commit() {
        if (!overflow) pending.forEach { (owner, key, history) -> owner.history[key] = history }
        pending.clear()
    }

    fun resetHistory() {
        epoch++
        pending.clear()
        owners.values.forEach { it.history.clear() }
    }

    fun endFrame() {
        active = false
        owner = null
        submission = null
        section = null
        materials.clear()
        consumers.clear()
        draws.clear()
        rejectedSections.clear()
        pending.clear()
    }

    fun close() {
        endFrame()
        resetHistory()
        owners.clear()
        renderer.close()
    }
}
