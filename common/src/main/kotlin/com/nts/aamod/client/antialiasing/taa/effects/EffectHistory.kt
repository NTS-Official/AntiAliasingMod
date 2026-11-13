package com.nts.aamod.client.antialiasing.taa.effects

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class EffectKind { PARTICLE, WEATHER, CLOUD, LEASH, FLAME, TEXT, TRANSLUCENT }

enum class EffectDomain { OPAQUE, TRANSLUCENT, ITEM_ENTITY, PARTICLES, WEATHER, CLOUDS }

enum class EffectRejection { NONE, NEW_SURFACE, FRAME_GAP, APPEARANCE, TOPOLOGY, PATTERN_DISCONTINUITY, UNRELIABLE_SOURCE, CAPTURE_BUDGET }

data class EffectSurfaceKey(val kind: EffectKind, val owner: Long, val part: Int, val material: String) {
    override fun hashCode(): Int {
        var hash = (owner xor (owner ushr 33)) * -49064778989728563L
        hash = (hash xor (hash ushr 33)) * -4265267296055464877L
        hash = hash xor (hash ushr 33)
        var result = kind.hashCode()
        result = 31 * result + (hash xor (hash ushr 32)).toInt()
        result = 31 * result + part
        return 31 * result + material.hashCode()
    }
}

class EffectAppearance(
    uv: FloatArray, val color: Int, val revision: Long = 0L,
    alphas: FloatArray? = null
) {
    private val coordinates = uv.copyOf()
    private val alphaCoordinates = alphas?.copyOf()

    fun confidence(previous: EffectAppearance): Float {
        val alphaShapeChanged = when {
            alphaCoordinates == null -> previous.alphaCoordinates != null
            previous.alphaCoordinates == null -> true
            alphaCoordinates.size != previous.alphaCoordinates.size -> true
            else -> false
        }
        if (revision != previous.revision || coordinates.size != previous.coordinates.size ||
            coordinates.indices.any { abs(coordinates[it] - previous.coordinates[it]) > 0.000001f } ||
            alphaShapeChanged
        ) return 0f
        var difference = 0
        for (shift in intArrayOf(0, 8, 16, 24)) {
            difference = max(difference, abs((color ushr shift and 255) - (previous.color ushr shift and 255)))
        }
        alphaCoordinates?.let { current ->
            val prior = previous.alphaCoordinates ?: return@let
            current.forEachIndexed { index, alpha ->
                difference = max(difference, abs((alpha.coerceIn(0f, 1f) * 255f).roundToInt() -
                    (prior[index].coerceIn(0f, 1f) * 255f).roundToInt()))
            }
        }
        return (1f - difference / 32f).coerceIn(0f, 1f)
    }
}

class EffectPattern(uv: DoubleArray, val continuity: Long) {
    private val coordinates = uv.copyOf()
    init { require(coordinates.size == 8 && coordinates.all { it.isFinite() }) }
    fun correspondence(previous: EffectPattern): FloatArray? =
        if (continuity == previous.continuity) EffectGeometry.patternCoordinates(coordinates, previous.coordinates) else null
}

class EffectSnapshot @JvmOverloads constructor(
    val key: EffectSurfaceKey, val domain: EffectDomain, val token: Int,
    val frame: Long, val epoch: Long, clip: FloatArray, val appearance: EffectAppearance, val pattern: EffectPattern? = null,
    val reliable: Boolean = true
) {
    private val coordinates = clip.copyOf()
    val vertexCount: Int get() = coordinates.size / 4
    fun clip(): FloatArray = coordinates.copyOf()
}

class EffectCorrespondence(
    val current: EffectSnapshot, val previous: EffectSnapshot?,
    val confidence: Float, val rejection: EffectRejection
) {
    fun previousCoordinates(): FloatArray? = current.pattern?.let { pattern ->
        previous?.pattern?.let { pattern.correspondence(it) }
    }

    fun previousClip(): FloatArray? {
        val prior = previous ?: return null
        val coordinates = previousCoordinates() ?: return prior.clip()
        return EffectGeometry.mapPattern(prior.clip(), coordinates)
    }
}

class EffectOwnerRegistry {
    private class OwnerReference(owner: Any, queue: ReferenceQueue<Any>?) : WeakReference<Any>(owner, queue) {
        private val identity = System.identityHashCode(owner)
        override fun hashCode(): Int = identity
        override fun equals(other: Any?): Boolean = this === other ||
            other is OwnerReference && get()?.let { it === other.get() } == true
    }

    private val queue = ReferenceQueue<Any>()
    private val owners = HashMap<OwnerReference, Long>()
    private var nextOwner = 1L

    fun owner(instance: Any): Long {
        drain()
        val lookup = OwnerReference(instance, null)
        return owners[lookup] ?: nextOwner++.also { owners[OwnerReference(instance, queue)] = it }
    }

    fun drain() {
        while (true) owners.remove(queue.poll() ?: break)
    }

    fun clear() {
        owners.clear()
        while (queue.poll() != null) { }
    }
}

class EffectHistory(
    private val maxSurfaces: Int = 65536,
    private val maxVertices: Int = 262144,
    private val maxToken: Int = 0xffffff
) {
    private var previous = HashMap<EffectSurfaceKey, EffectSnapshot>()
    private var pending = HashMap<EffectSurfaceKey, EffectSnapshot>()
    private var nextToken = 1
    private var vertices = 0
    private var frame = -1L
    private var committedFrame = -1L
    private var epoch = 0L
    private val exhausted = linkedMapOf<EffectDomain, EffectRejection>()
    private var previouslyExhausted = emptyMap<EffectDomain, EffectRejection>()
    val rejectedReasons: Map<EffectDomain, EffectRejection> get() = previouslyExhausted + exhausted
    val rejectedDomains: Set<EffectDomain> get() = rejectedReasons.keys
    val pendingCount: Int get() = pending.size
    val committedCount: Int get() = previous.size

    init {
        require(maxSurfaces > 0 && maxVertices > 0 && maxToken in 1..0xffffff)
    }

    fun begin(frameId: Long) {
        require(frameId >= 0)
        abort()
        if (nextToken > maxToken) reset()
        frame = frameId
    }

    @JvmOverloads
    fun capture(
        key: EffectSurfaceKey, domain: EffectDomain, clip: FloatArray, appearance: EffectAppearance, pattern: EffectPattern? = null,
        reliable: Boolean = true
    ): EffectCorrespondence? {
        check(frame >= 0) { "No active effects frame" }
        require(clip.isNotEmpty() && clip.size % 4 == 0 && clip.all { it.isFinite() })
        if (key in pending) {
            rejectDomain(domain, EffectRejection.UNRELIABLE_SOURCE)
            return null
        }
        val prior = previous[key]
        val token = prior?.token ?: nextToken
        if (pending.size >= maxSurfaces || vertices.toLong() + clip.size / 4 > maxVertices || token > maxToken) {
            rejectDomain(domain, EffectRejection.CAPTURE_BUDGET)
            return null
        }
        if (prior == null) nextToken++
        val snapshot = EffectSnapshot(key, domain, token, frame, epoch, clip, appearance, pattern, reliable)
        pending[key] = snapshot
        vertices += snapshot.vertexCount
        val confidence = prior?.let { appearance.confidence(it.appearance) } ?: 0f
        val domainRejection = exhausted[domain] ?: previouslyExhausted[domain]
        val rejection = when {
            domainRejection != null -> domainRejection
            prior == null -> EffectRejection.NEW_SURFACE
            committedFrame != frame - 1 || prior.frame != frame - 1 || prior.epoch != epoch -> EffectRejection.FRAME_GAP
            prior.domain != domain || prior.vertexCount != snapshot.vertexCount -> EffectRejection.TOPOLOGY
            !reliable || !prior.reliable -> EffectRejection.UNRELIABLE_SOURCE
            (pattern == null) != (prior.pattern == null) ||
                pattern != null && prior.pattern?.let { pattern.correspondence(it) } == null -> EffectRejection.PATTERN_DISCONTINUITY
            confidence <= 0f -> EffectRejection.APPEARANCE
            else -> EffectRejection.NONE
        }
        return EffectCorrespondence(snapshot, prior, if (rejection == EffectRejection.NONE) confidence else 0f, rejection)
    }

    fun commit(frameId: Long) {
        check(frame == frameId) { "Effects frame changed before commit" }
        pending.entries.removeIf { it.value.domain in exhausted }
        previouslyExhausted = exhausted.toMap()
        val retired = previous
        previous = pending
        pending = retired
        pending.clear()
        committedFrame = frame
        frame = -1L
        vertices = 0
    }

    fun abort() {
        if (frame >= 0) previouslyExhausted = exhausted.toMap()
        pending.clear()
        exhausted.clear()
        vertices = 0
        frame = -1L
    }

    fun reset() {
        abort()
        previouslyExhausted = emptyMap()
        previous.clear()
        committedFrame = -1L
        nextToken = 1
        epoch++
    }

    fun rejectDomain(domain: EffectDomain, reason: EffectRejection) {
        check(frame >= 0) { "No active effects frame" }
        require(reason == EffectRejection.CAPTURE_BUDGET || reason == EffectRejection.UNRELIABLE_SOURCE)
        if (exhausted[domain] != EffectRejection.CAPTURE_BUDGET) exhausted[domain] = reason
    }
}
