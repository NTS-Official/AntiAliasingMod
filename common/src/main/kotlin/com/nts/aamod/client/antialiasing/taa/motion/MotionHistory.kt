package com.nts.aamod.client.antialiasing.taa.motion

import org.joml.Matrix4f
import org.joml.Vector4f
import org.joml.Vector3f
import kotlin.math.abs

interface MotionOwnerAccess {
    var aamodMotionOwner: MotionOwner?
}

interface MotionSubmissionAccess {
    val aamodMotionSubmission: MotionSubmission?
}

class MotionOwner {
    private var frame = -1L
    private val occurrences = mutableMapOf<SubmissionKind, Int>()
    internal val history = mutableMapOf<GeometryKey, VertexHistory>()

    fun submission(frameId: Long, kind: SubmissionKind): MotionSubmission {
        if (frame != frameId) {
            frame = frameId
            occurrences.clear()
            history.entries.removeIf { it.value.frame != frameId - 1 }
        }
        val occurrence = occurrences.getOrDefault(kind, 0)
        occurrences[kind] = occurrence + 1
        return MotionSubmission(this, kind, occurrence)
    }
}

class IdentityKey(private val value: Any) {
    override fun equals(other: Any?): Boolean = other is IdentityKey && value === other.value
    override fun hashCode(): Int = System.identityHashCode(value)
}

data class SubmissionKind(val type: Class<*>, val model: IdentityKey?)

class MotionSubmission(val owner: MotionOwner, val kind: SubmissionKind, val occurrence: Int) {
    internal val sections = mutableMapOf<IdentityKey, Int>()

    internal fun key(geometry: Any, material: Any): GeometryKey {
        val identity = IdentityKey(geometry)
        val occurrence = sections.getOrDefault(identity, 0)
        sections[identity] = occurrence + 1
        return GeometryKey(kind, this.occurrence, identity, IdentityKey(material), occurrence)
    }
}

data class GeometryKey(
    val kind: SubmissionKind,
    val submission: Int,
    val geometry: IdentityKey,
    val material: IdentityKey,
    val occurrence: Int
)

class VertexHistory(val frame: Long, val epoch: Long, val clip: FloatArray, val geometry: FloatArray) {
    fun corresponds(frameId: Long, epochId: Long, current: FloatArray): Boolean {
        if (frame != frameId - 1 || epoch != epochId || geometry.size != current.size) return false
        return geometry.indices.all { abs(geometry[it] - current[it]) <= if (it % 5 < 3) 0.0001f else 0.000001f }
    }
}

internal class MotionSection(val geometry: Any, pose: Matrix4f?, val reliable: Boolean) {
    val inversePose = pose?.let { if (it.isFinite && abs(it.determinant()) > 1e-12f) Matrix4f(it).invert() else null }
    val batches = linkedMapOf<Pair<MotionMaterial, Any?>, MotionBatch>()
}

internal class MotionBatch(val material: MotionMaterial, val source: Any?) {
    private var vertices = FloatArray(9 * 64)
    private val localPosition = Vector3f()
    var count = 0
        private set

    fun append(position: FloatArray, inverse: Matrix4f?) {
        if ((count + 1) * 9 > vertices.size) vertices = vertices.copyOf(vertices.size * 2)
        val offset = count++ * 9
        position.copyInto(vertices, offset, 0, 6)
        val local = inverse?.transformPosition(position[0], position[1], position[2], localPosition)
        vertices[offset + 6] = local?.x ?: 0f
        vertices[offset + 7] = local?.y ?: 0f
        vertices[offset + 8] = local?.z ?: 0f
    }

    fun geometry(): FloatArray = FloatArray(count * 5) { index ->
        val vertex = index / 5
        val component = index % 5
        vertices[vertex * 9 + if (component < 3) component + 6 else component]
    }

    fun clips(matrix: Matrix4f): FloatArray {
        val result = FloatArray(count * 4)
        val clip = Vector4f()
        repeat(count) { index ->
            matrix.transform(clip.set(vertices[index * 9], vertices[index * 9 + 1], vertices[index * 9 + 2], 1f))
            result[index * 4] = clip.x
            result[index * 4 + 1] = clip.y
            result[index * 4 + 2] = clip.z
            result[index * 4 + 3] = clip.w
        }
        return result
    }

    fun value(vertex: Int, component: Int): Float = vertices[vertex * 9 + component]
}
