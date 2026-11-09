package com.nts.aamod.client.antialiasing.taa

class TemporalJitterController {
    var epoch = 0L
        private set
    var frameIndex = 0L
        private set
    var current = JitterPoint(0.0, 0.0)
        private set
    var previous = current
        private set
    var resetReason = "initial"
        private set

    fun reset(reason: String) {
        epoch++
        frameIndex = 0
        current = JitterPoint(0.0, 0.0)
        previous = current
        resetReason = reason
    }

    fun advance(sequence: TemporalJitterSequence, samples: Int): JitterPoint {
        previous = current
        current = sequence.sample((frameIndex % samples).toInt(), samples, epoch)
        frameIndex++
        return current
    }
}
