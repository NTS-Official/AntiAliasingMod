package com.nts.aamod.client.antialiasing.taa.composition

import com.mojang.blaze3d.vertex.VertexConsumer
import com.nts.aamod.client.antialiasing.taa.effects.TemporalEffects
import net.minecraft.client.renderer.StagedVertexBuffer
import java.util.IdentityHashMap

object NativeEffectDraws {
    private val builders = IdentityHashMap<VertexConsumer, Any>()
    private val executions = IdentityHashMap<Any, StagedVertexBuffer.ExecuteInfo>()
    private val unavailable = Any()

    fun bind(builder: VertexConsumer, draw: StagedVertexBuffer.Draw) {
        if (TemporalEffects.active && builders.size < 262144) builders[builder] = draw
    }

    fun alias(builder: VertexConsumer, wrapper: VertexConsumer) {
        if (TemporalEffects.active && builders.size < 262144) builders[wrapper] = builders[builder] ?: unavailable
    }

    fun source(builder: VertexConsumer): Any? = builders[builder]

    fun uploaded(draw: StagedVertexBuffer.Draw, execution: StagedVertexBuffer.ExecuteInfo?) {
        uploaded(draw as Any, execution)
    }

    fun uploaded(draw: Any, execution: StagedVertexBuffer.ExecuteInfo?) {
        if (TemporalEffects.active && execution != null && executions.size < 65536) executions[draw] = execution
    }

    fun matches(source: Any, draw: FrozenDraw): Boolean {
        if (source === draw.state.extension) return true
        val expected = executions[source] ?: return false
        val vertex = draw.state.vertices[0] ?: return false
        val indices = draw.state.indices ?: return false
        if (vertex.buffer() !== expected.vertexBuffer() || vertex.offset() != 0L ||
            indices.buffer !== expected.indexBuffer() || indices.type != expected.indexType()
        ) return false
        val command = draw.command as? FrozenDrawCommand.Direct ?: return false
        return command.indexed && command.instances == 1 && command.firstInstance == 0 &&
            command.vertexOffset == expected.baseVertex() && command.first == expected.firstIndex() && command.count == expected.indexCount()
    }

    fun clear() { builders.clear(); executions.clear() }
}
