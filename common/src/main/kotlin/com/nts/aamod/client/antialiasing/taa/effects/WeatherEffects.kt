package com.nts.aamod.client.antialiasing.taa.effects

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.WeatherEffectRenderer
import net.minecraft.resources.Identifier
import java.util.WeakHashMap
import com.nts.aamod.client.antialiasing.taa.composition.NativeEffectDraws

object WeatherEffects {
    private data class PatternPhase(val unwrappedU: Double, val unwrappedV: Double, val epoch: Long)
    private val phases = WeakHashMap<WeatherEffectRenderer.ColumnInstance, PatternPhase>()

    fun rain(column: WeatherEffectRenderer.ColumnInstance, ticks: Long, partialTick: Float, speed: Float) {
        if (!TemporalEffects.active) return
        val offset = (column.x() * column.x() * 3121 + column.x() * 45238971 +
            column.z() * column.z() * 418711 + column.z() * 13761) and 255
        val actualTime = (ticks and 131071L).toInt() + offset + partialTick
        phases[column] = PatternPhase(column.uOffset().toDouble(), (-actualTime / 32f * speed).toDouble(), ticks shr 17)
    }

    fun snow(column: WeatherEffectRenderer.ColumnInstance, ticks: Long) {
        if (!TemporalEffects.active) return
        val unwrappedV = column.vOffset().toDouble() - ((ticks and 131071L) shr 9).toDouble()
        phases[column] = PatternPhase(column.uOffset().toDouble(), unwrappedV, ticks shr 17)
    }

    private fun pattern(column: WeatherEffectRenderer.ColumnInstance, uv: FloatArray): EffectPattern? {
        val phase = phases[column] ?: return null
        val coordinates = DoubleArray(8) { component ->
            uv[component].toDouble() + if (component % 2 == 0) phase.unwrappedU - column.uOffset()
            else phase.unwrappedV - column.vOffset()
        }
        return coordinates.takeIf { values -> values.all { it.isFinite() } }?.let { EffectPattern(it, phase.epoch) }
    }

    fun record(builder: VertexConsumer, columns: List<WeatherEffectRenderer.ColumnInstance>, snow: Boolean): EffectQuadConsumer {
        val pipeline = if (Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency())
            RenderPipelines.WEATHER_DEPTH_WRITE else RenderPipelines.WEATHER_NO_DEPTH_WRITE
        val layer = SingleQuadParticle.Layer(true, Identifier.withDefaultNamespace(
            if (snow) "textures/environment/snow.png" else "textures/environment/rain.png"
        ), pipeline)
        return EffectQuadConsumer(builder) { index, position, uv, colors ->
            val column = columns.getOrNull(index)
            val pattern = column?.let { pattern(it, uv) }
            if (column != null && pattern != null) {
                val owner = (column.x().toLong() shl 32) or (column.z().toLong() and 0xffffffffL)
                val key = EffectSurfaceKey(EffectKind.WEATHER, owner, if (snow) 1 else 0, layer.textureAtlasLocation().toString())
                val alphas = FloatArray(4) { (colors[it] ushr 24) / 255f }
                TemporalEffects.capture(
                    EffectQuad(key, layer, position, uv, colors[0], alphas, NativeEffectDraws.source(builder)), EffectDomain.WEATHER,
                    EffectAppearance(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), colors[0], column.lightCoords().toLong(), alphas),
                    pattern
                )
            }
        }
    }

    fun clear() { phases.clear() }
}
