package com.nts.aamod.client.antialiasing

import com.mojang.blaze3d.framegraph.FrameGraphBuilder
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.resource.CrossFrameResourcePool
import com.mojang.blaze3d.resource.GraphicsResourceAllocator
import com.mojang.blaze3d.resource.ResourceDescriptor
import com.mojang.blaze3d.resource.ResourceHandle
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.serialization.JsonOps
import com.nts.aamod.AAMod
import com.nts.aamod.client.antialiasing.fxaa.FxaaPipelines
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.PostChain
import net.minecraft.client.renderer.PostChainConfig
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.util.StrictJsonParser
import java.util.IdentityHashMap

class SpatialPostChain(private val name: String) : AutoCloseable {
    private val pool = CrossFrameResourcePool(3)
    private var chain: PostChain? = null
    private var projection: ProjectionMatrixBuffer? = null

    fun execute(width: Int, height: Int, targets: Map<Identifier, RenderTarget>) {
        val active = chain ?: load(targets.keys).also { chain = it }
        val graph = FrameGraphBuilder()
        val handles = targets.mapValues { (identifier, target) -> graph.importExternal(identifier.toString(), target) }.toMutableMap()
        active.addToFrame(graph, width, height, object : PostChain.TargetBundle {
            override fun get(id: Identifier): ResourceHandle<RenderTarget>? = handles[id]
            override fun replace(id: Identifier, handle: ResourceHandle<RenderTarget>) { handles[id] = handle }
        })
        val allocations = Allocations(pool)
        val previousProjection = RenderSystem.getProjectionMatrixBuffer()
        val previousType = RenderSystem.getProjectionType()
        try {
            graph.execute(allocations)
        } finally {
            if (previousProjection != null) RenderSystem.setProjectionMatrix(previousProjection, previousType)
            allocations.close()
            pool.endFrame()
        }
    }

    private fun load(externalTargets: Set<Identifier>): PostChain {
        val minecraft = Minecraft.getInstance()
        val config = minecraft.resourceManager.getResourceOrThrow(AAMod.id("post_effect/$name.json")).openAsReader().use {
            PostChainConfig.CODEC.parse(JsonOps.INSTANCE, StrictJsonParser.parse(it)).getOrThrow()
        }
        config.passes().forEachIndexed { index, pass ->
            val bindings = BindGroupLayout.builder()
            pass.inputs().forEach { bindings.withSampler(it.samplerName() + "Sampler") }
            bindings.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
            pass.uniforms().keys.forEach { bindings.withUniform(it, UniformType.UNIFORM_BUFFER) }
            val pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(AAMod.id("$name/$index"))
                .withVertexShader(pass.vertexShaderId()).withFragmentShader(pass.fragmentShaderId())
                .withBindGroupLayout(bindings.build()).build()
            check(RenderSystem.getDevice().precompilePipeline(FxaaPipelines.reuse(pipeline)).isValid) {
                "Could not compile $name pass $index"
            }
        }
        val buffer = ProjectionMatrixBuffer("aamod $name")
        try {
            return PostChain.load(config, minecraft.textureManager, externalTargets, AAMod.id(name),
                Projection().apply { setupOrtho(0.1f, 1000f, 1f, 1f, false) }, buffer).also { projection = buffer }
        } catch (exception: Exception) {
            buffer.close()
            throw exception
        }
    }

    override fun close() {
        chain?.close()
        chain = null
        projection?.close()
        projection = null
        pool.clear()
    }

    private class Allocations(private val pool: CrossFrameResourcePool) : GraphicsResourceAllocator, AutoCloseable {
        private val borrowed = IdentityHashMap<Any, () -> Unit>()
        override fun <Resource : Any> acquire(descriptor: ResourceDescriptor<Resource>): Resource {
            val resource = pool.acquire(descriptor)
            borrowed[resource] = { pool.release(descriptor, resource) }
            return resource
        }
        override fun <Resource : Any> release(descriptor: ResourceDescriptor<Resource>, resource: Resource) {
            borrowed.remove(resource)
            pool.release(descriptor, resource)
        }
        override fun close() {
            borrowed.values.forEach { it() }
            borrowed.clear()
        }
    }
}
