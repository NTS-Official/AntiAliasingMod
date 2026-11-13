package com.nts.aamod.client.antialiasing.taa.composition

import com.mojang.blaze3d.systems.RenderPassDescriptor

object TemporalCommandCapture {
    private var factory: ((RenderPassDescriptor) -> FrozenPassRecorder?)? = null
    private var suppressed = 0

    fun begin(factory: (RenderPassDescriptor) -> FrozenPassRecorder?) {
        check(this.factory == null) { "A temporal command capture is already active" }
        this.factory = factory
    }

    fun capture(descriptor: RenderPassDescriptor): FrozenPassRecorder? =
        if (suppressed > 0) null else factory?.invoke(descriptor)

    fun <Value> suppress(action: () -> Value): Value {
        suppressed++
        try { return action() } finally { suppressed-- }
    }

    fun end() { factory = null }
}
