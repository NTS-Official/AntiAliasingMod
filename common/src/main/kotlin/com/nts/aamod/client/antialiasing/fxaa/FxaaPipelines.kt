package com.nts.aamod.client.antialiasing.fxaa

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.nts.aamod.AAMod
import net.minecraft.resources.Identifier

object FxaaPipelines {
    private val pipelines = mutableMapOf<Identifier, RenderPipeline>()

    @JvmStatic
    fun reuse(pipeline: RenderPipeline): RenderPipeline {
        val location = pipeline.location
        if (location.namespace != AAMod.MOD_ID || listOf("fxaa/", "ssaa/", "smaa/").none(location.path::startsWith)) return pipeline
        return pipelines.getOrPut(location) { pipeline }
    }

    fun clear() {
        pipelines.clear()
    }
}
