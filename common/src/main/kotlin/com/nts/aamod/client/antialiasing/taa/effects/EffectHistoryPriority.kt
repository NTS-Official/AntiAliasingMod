package com.nts.aamod.client.antialiasing.taa.effects

object EffectHistoryPriority {
    fun select(keys: Set<EffectHistoryKey>, resident: Set<EffectHistoryKey>, capacity: Int): Set<EffectHistoryKey> {
        require(capacity >= 0)
        return keys.sortedWith(compareBy<EffectHistoryKey> { it.domain.ordinal }
            .thenBy { if (it in resident) 0 else 1 }
            .thenBy { it.interval?.phase.orEmpty() }
            .thenBy { it.interval?.target.orEmpty() }
            .thenBy { it.interval?.material.orEmpty() }
            .thenBy { it.interval?.occurrence ?: -1 })
            .take(capacity).toCollection(linkedSetOf())
    }
}
