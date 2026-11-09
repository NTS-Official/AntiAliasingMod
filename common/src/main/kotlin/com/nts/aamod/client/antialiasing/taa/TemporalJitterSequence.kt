package com.nts.aamod.client.antialiasing.taa

import kotlin.math.floor

data class JitterPoint(val x: Double, val y: Double)

enum class TemporalJitterSequence {
    HALTON_23,
    HAMMERSLEY,
    SOBOL_OWEN,
    RANK_1_R2,
    POISSON_PROGRESSIVE;

    fun sample(index: Int, sampleCount: Int, epochSeed: Long): JitterPoint {
        require(sampleCount in listOf(8, 16, 32))
        val phase = Math.floorMod(index, sampleCount)
        val point = when (this) {
            HALTON_23 -> JitterPoint(radicalInverse(phase + 1, 2), radicalInverse(phase + 1, 3))
            HAMMERSLEY -> JitterPoint((phase + 0.5) / sampleCount, radicalInverse(phase + 1, 2))
            SOBOL_OWEN -> {
                val gray = phase xor (phase ushr 1)
                var first = 0
                var second = 0
                var direction = Int.MIN_VALUE
                for (bit in 0 until 32) {
                    if ((gray ushr bit) and 1 != 0) {
                        first = first xor (Int.MIN_VALUE ushr bit)
                        second = second xor direction
                    }
                    direction = direction xor (direction ushr 1)
                }
                JitterPoint(owen(first, epochSeed), owen(second, mix(epochSeed + 1)))
            }
            RANK_1_R2 -> JitterPoint(
                fraction((phase + 0.5) * 0.7548776662466927 + unit(mix(epochSeed))),
                fraction((phase + 0.5) * 0.5698402909980532 + unit(mix(epochSeed + 1)))
            )
            POISSON_PROGRESSIVE -> poisson[phase].let {
                JitterPoint(fraction(it.x + unit(mix(epochSeed))), fraction(it.y + unit(mix(epochSeed + 1))))
            }
        }
        return JitterPoint(point.x - 0.5, point.y - 0.5)
    }

    companion object {
        private fun fraction(value: Double) = value - floor(value)

        private fun radicalInverse(index: Int, base: Int): Double {
            var digits = index
            var factor = 1.0 / base
            var result = 0.0
            while (digits > 0) {
                result += (digits % base) * factor
                digits /= base
                factor /= base
            }
            return result
        }

        private fun mix(value: Long): Long {
            var mixed = value
            mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
            mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
            return mixed xor (mixed ushr 31)
        }

        private fun unit(value: Long): Double = (value ushr 11).toDouble() / 9007199254740992.0

        private fun owen(value: Int, seed: Long): Double {
            var result = 0L
            var prefix = 0L
            for (bit in 31 downTo 0) {
                val digit = (value ushr bit) and 1
                val permutation = (mix(seed xor prefix xor (bit.toLong() shl 32)) and 1).toInt()
                result = (result shl 1) or (digit xor permutation).toLong()
                prefix = (prefix shl 1) or digit.toLong()
            }
            return result.toDouble() / 4294967296.0
        }

        private val poisson = listOf(
            JitterPoint(0.8298633238, 0.4851662170),
            JitterPoint(0.3291681693, 0.8830613235),
            JitterPoint(0.8457931387, 0.0603022401),
            JitterPoint(0.3060675883, 0.3884388136),
            JitterPoint(0.1106480304, 0.6488947959),
            JitterPoint(0.5958691081, 0.2632334779),
            JitterPoint(0.6290860730, 0.7317654763),
            JitterPoint(0.0444739973, 0.3164524760),
            JitterPoint(0.2137077386, 0.1095878452),
            JitterPoint(0.4033393604, 0.6848960987),
            JitterPoint(0.0155916205, 0.8781268101),
            JitterPoint(0.8052979787, 0.8540436684),
            JitterPoint(0.6234341976, 0.0438668674),
            JitterPoint(0.8047791640, 0.2740707186),
            JitterPoint(0.0354965045, 0.0552402840),
            JitterPoint(0.4539162084, 0.1127765730),
            JitterPoint(0.5772939636, 0.4708911881),
            JitterPoint(0.9445195873, 0.7021631061),
            JitterPoint(0.4918956538, 0.8491646408),
            JitterPoint(0.3255182779, 0.5476550676),
            JitterPoint(0.1601809813, 0.4839180324),
            JitterPoint(0.6918516420, 0.5904393827),
            JitterPoint(0.0052184993, 0.5586820811),
            JitterPoint(0.4020773210, 0.9887441506),
            JitterPoint(0.1633743425, 0.9135011195),
            JitterPoint(0.4939633477, 0.5774780826),
            JitterPoint(0.2835634900, 0.7543029997),
            JitterPoint(0.8171655228, 0.6779668133),
            JitterPoint(0.1735480693, 0.3361476755),
            JitterPoint(0.3658154765, 0.2421131029),
            JitterPoint(0.6337727848, 0.8807605000),
            JitterPoint(0.4451679653, 0.4434351798)
        )
    }
}
