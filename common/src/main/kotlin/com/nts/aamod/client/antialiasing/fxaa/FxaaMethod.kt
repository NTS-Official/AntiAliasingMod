package com.nts.aamod.client.antialiasing.fxaa

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.framegraph.FrameGraphBuilder
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.resource.CrossFrameResourcePool
import com.mojang.blaze3d.resource.GraphicsResourceAllocator
import com.mojang.blaze3d.resource.ResourceDescriptor
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.serialization.JsonOps
import com.nts.aamod.AAMod
import com.nts.aamod.client.antialiasing.AntiAliasingMethod
import com.nts.aamod.client.antialiasing.ColorEncoding
import com.nts.aamod.client.antialiasing.FrameContext
import com.nts.aamod.client.antialiasing.InvalidationReason
import com.nts.aamod.client.antialiasing.RenderEnvironment
import com.nts.aamod.client.antialiasing.RenderInput
import com.nts.aamod.client.antialiasing.RenderStage
import com.nts.aamod.client.option.VideoAntiAliasingSettings
import com.nts.aamod.client.option.FxaaParameters
import com.nts.aamod.client.option.FxaaSettings
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.PostChain
import net.minecraft.client.renderer.PostChainConfig
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.UniformValue
import net.minecraft.util.StrictJsonParser
import java.util.IdentityHashMap

class FxaaMethod : AntiAliasingMethod {
    override val mode = VideoAntiAliasingSettings.FXAA
    override val requirements = mapOf(RenderStage.FINAL_COLOR to setOf(RenderInput.FINAL_COLOR))

    private var chain: PostChain? = null
    private var projectionBuffer: ProjectionMatrixBuffer? = null
    private var parameters: FxaaParameters? = null
    private val resourcePool = CrossFrameResourcePool(3)

    override fun render(context: FrameContext, environment: RenderEnvironment) {
        RenderSystem.assertOnRenderThread()
        check(environment.format == GpuFormat.RGBA8_UNORM && environment.colorEncoding == ColorEncoding.DISPLAY) {
            "FXAA requires RGBA8 display color, received ${environment.format} / ${environment.colorEncoding}"
        }
        val frameParameters = FxaaSettings.snapshot()
        if (parameters != frameParameters) closeChain()
        val activeChain = chain ?: loadChain(frameParameters).also {
            chain = it
            parameters = frameParameters
        }
        val frameGraph = FrameGraphBuilder()
        val target = environment.target
        val targets = PostChain.TargetBundle.of(PostChain.MAIN_TARGET_ID, frameGraph.importExternal("aamod_main", target))
        activeChain.addToFrame(frameGraph, environment.width, environment.height, targets)
        val allocations = FrameAllocations(resourcePool)
        val previousProjection = RenderSystem.getProjectionMatrixBuffer()
        val previousProjectionType = RenderSystem.getProjectionType()
        try {
            frameGraph.execute(allocations)
        } finally {
            if (previousProjection != null) RenderSystem.setProjectionMatrix(previousProjection, previousProjectionType)
            allocations.close()
            resourcePool.endFrame()
        }
    }

    override fun invalidate(reason: InvalidationReason) {
        close()
        if (reason == InvalidationReason.RESOURCES_RELOADED || reason == InvalidationReason.SHUTDOWN) {
            FxaaPipelines.clear()
        }
    }

    override fun close() {
        closeChain()
        resourcePool.clear()
    }

    private fun closeChain() {
        chain?.close()
        chain = null
        projectionBuffer?.close()
        projectionBuffer = null
        parameters = null
    }

    private fun loadChain(frameParameters: FxaaParameters): PostChain {
        val minecraft = Minecraft.getInstance()
        val resourceConfig = minecraft.resourceManager.getResourceOrThrow(AAMod.id("post_effect/fxaa.json")).openAsReader().use { reader ->
            PostChainConfig.CODEC.parse(JsonOps.INSTANCE, StrictJsonParser.parse(reader)).getOrThrow()
        }
        val uniforms = listOf(
            UniformValue.FloatUniform(frameParameters.subpixel),
            UniformValue.FloatUniform(frameParameters.edgeThreshold),
            UniformValue.FloatUniform(frameParameters.minimumThreshold),
            UniformValue.IntUniform(frameParameters.searchSteps)
        )
        val config = PostChainConfig(resourceConfig.internalTargets(), resourceConfig.passes().map { pass ->
            if (pass.fragmentShaderId() == AAMod.id("post/fxaa")) {
                PostChainConfig.Pass(
                    pass.vertexShaderId(), pass.fragmentShaderId(), pass.inputs(), pass.outputTarget(),
                    pass.uniforms() + ("FxaaConfig" to uniforms)
                )
            } else pass
        })
        precompile(config)
        val projection = Projection().apply { setupOrtho(0.1f, 1000f, 1f, 1f, false) }
        val buffer = ProjectionMatrixBuffer("aamod FXAA")
        try {
            val loaded = PostChain.load(config, minecraft.textureManager, setOf(PostChain.MAIN_TARGET_ID), AAMod.id("fxaa"), projection, buffer)
            projectionBuffer = buffer
            return loaded
        } catch (exception: Exception) {
            buffer.close()
            throw exception
        }
    }

    private fun precompile(config: PostChainConfig) {
        config.passes().forEachIndexed { passIndex, pass ->
            val bindings = BindGroupLayout.builder()
            pass.inputs().forEach { bindings.withSampler(it.samplerName() + "Sampler") }
            bindings.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
            pass.uniforms().keys.forEach { bindings.withUniform(it, UniformType.UNIFORM_BUFFER) }
            val pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(AAMod.id("fxaa/$passIndex"))
                .withVertexShader(pass.vertexShaderId())
                .withFragmentShader(pass.fragmentShaderId())
                .withBindGroupLayout(bindings.build())
                .build()
            check(RenderSystem.getDevice().precompilePipeline(FxaaPipelines.reuse(pipeline)).isValid) {
                "Could not compile FXAA pass $passIndex"
            }
        }
    }

    private class FrameAllocations(private val pool: CrossFrameResourcePool) : GraphicsResourceAllocator, AutoCloseable {
        private val outstanding = IdentityHashMap<Any, () -> Unit>()

        override fun <Resource : Any> acquire(descriptor: ResourceDescriptor<Resource>): Resource {
            val resource = pool.acquire(descriptor)
            outstanding[resource] = { pool.release(descriptor, resource) }
            return resource
        }

        override fun <Resource : Any> release(descriptor: ResourceDescriptor<Resource>, resource: Resource) {
            outstanding.remove(resource)
            pool.release(descriptor, resource)
        }

        override fun close() {
            outstanding.values.forEach { it() }
            outstanding.clear()
        }
    }
}
