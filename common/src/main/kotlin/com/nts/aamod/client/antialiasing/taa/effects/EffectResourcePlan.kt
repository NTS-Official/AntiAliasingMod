package com.nts.aamod.client.antialiasing.taa.effects

data class EffectResource(val name: String, val bytes: Long) {
    init { require(bytes >= 0) }
}

class EffectResourcePlan private constructor(
    val resources: List<EffectResource>, val historicalDomains: Set<EffectDomain>, val currentOnlyDomains: Set<EffectDomain>,
    val budgetBytes: Long
) {
    val bytes: Long = resources.fold(0L) { total, resource -> Math.addExact(total, resource.bytes) }

    companion object {
        fun create(
            width: Int, height: Int, maxDimension: Int, budgetBytes: Long,
            domains: Set<EffectDomain>, additional: List<EffectResource> = emptyList()
        ): EffectResourcePlan {
            require(width in 1..maxDimension && height in 1..maxDimension && budgetBytes >= 0)
            val pixels = width.toLong() * height
            val fixed = listOf(
                EffectResource("shared_scratch", pixels * 44),
                EffectResource("opaque_snapshot", pixels * 8),
                EffectResource("composition_output", pixels * 8),
                EffectResource("native_recovery", pixels * 4)
            ) + additional
            var allocated = fixed.fold(0L) { total, resource -> Math.addExact(total, resource.bytes) }
            val resources = mutableListOf<EffectResource>()
            val historical = linkedSetOf<EffectDomain>()
            if (allocated <= budgetBytes) {
                resources.addAll(fixed)
                for (domain in EffectDomain.entries.filter { it != EffectDomain.OPAQUE && it in domains }) {
                    val size = pixels * 40
                    if (size <= budgetBytes - allocated) {
                        resources.add(EffectResource("history_${domain.name.lowercase()}", size))
                        allocated += size
                        historical.add(domain)
                    }
                }
            }
            return EffectResourcePlan(resources, historical, domains.filter { it != EffectDomain.OPAQUE && it !in historical }.toSet(), budgetBytes)
        }
    }
}
