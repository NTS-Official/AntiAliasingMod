package com.nts.aamod.client.antialiasing.taa.composition

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.framegraph.FrameGraphBuilder
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.resource.GraphicsResourceAllocator
import com.mojang.blaze3d.resource.ResourceHandle
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.serialization.JsonOps
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.PostChain
import net.minecraft.client.renderer.PostChainConfig
import net.minecraft.resources.Identifier
import net.minecraft.util.StrictJsonParser
import java.security.MessageDigest
import java.util.WeakHashMap

object NativeTransparencyContract {
    private val contracts = WeakHashMap<PostChain, Boolean>()
    private val assets = linkedMapOf(
        "post_effect/transparency.json" to "79d5d0cf8ef7530037fc91fe29ee4bc83278acff0bc6ba3029a3452eb0aab863",
        "shaders/post/transparency.fsh" to "c6fe15750d43a4745889ce59307c7aeb1515da017f4b1fde45ca5437ee43619a",
        "shaders/post/blit.fsh" to "5be94ececa9950834ff5b2864efbd1946fc0342f882f5b8a929e18412388a06b",
        "shaders/core/screenquad.vsh" to "c4a7088b7fb8fe75793f9499597426ee32b989c3e972f221fc9e9c8a6cc0a55e"
    )

    fun register(id: Identifier, config: PostChainConfig, chain: PostChain) {
        if (id != Identifier.withDefaultNamespace("transparency")) return
        contracts[chain] = runCatching {
            val resources = Minecraft.getInstance().resourceManager
            var expected: PostChainConfig? = null
            for ((path, fingerprint) in assets) {
                val resource = resources.getResource(Identifier.withDefaultNamespace(path)).orElseThrow()
                val data = resource.open().use { it.readAllBytes() }
                val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
                if (hash != fingerprint) return@runCatching false
                if (path == "post_effect/transparency.json") {
                    expected = PostChainConfig.CODEC.parse(JsonOps.INSTANCE, StrictJsonParser.parse(data.toString(Charsets.UTF_8))).getOrThrow()
                }
            }
            config == expected
        }.getOrDefault(false)
    }

    fun supported(chain: PostChain): Boolean = contracts[chain] == true

    fun isTransparency(chain: PostChain): Boolean = contracts.containsKey(chain)

    fun clear() { contracts.clear() }
}

class VanillaSortedTargets {
    fun compose(chain: PostChain, inputs: Map<Identifier, RenderTarget>, allocator: GraphicsResourceAllocator): RenderTarget {
        check(NativeTransparencyContract.supported(chain)) { "composition_contract_unsupported" }
        check(inputs.keys == LevelTargetBundle.SORTING_TARGETS) { "Native sorted transparency requires all six actual targets" }
        val main = checkNotNull(inputs[LevelTargetBundle.MAIN_TARGET_ID])
        for (target in inputs.values) {
            check(target.width == main.width && target.height == main.height &&
                target.colorTexture?.format == GpuFormat.RGBA8_UNORM && target.depthTexture?.format == GpuFormat.D32_FLOAT
            ) { "Sorted targets require aligned current depth and native RGBA8 color" }
        }
        check(inputs.values.map { it.colorTexture }.toSet().size == inputs.size) { "Sorted targets must have independent color attachments" }
        val frame = FrameGraphBuilder()
        val handles = inputs.mapValuesTo(linkedMapOf()) { (id, target) -> frame.importExternal("aamod sorted $id", target) }
        val bundle = object : PostChain.TargetBundle {
            override fun get(id: Identifier): ResourceHandle<RenderTarget>? = handles[id]
            override fun replace(id: Identifier, handle: ResourceHandle<RenderTarget>) {
                check(id in handles) { "Unexpected native transparency output: $id" }
                handles[id] = handle
            }
        }
        val projection = RenderSystem.getProjectionMatrixBuffer()
        val projectionType = RenderSystem.getProjectionType()
        try {
            TemporalCommandCapture.suppress {
                chain.addToFrame(frame, main.width, main.height, bundle)
                frame.execute(allocator)
            }
        } finally {
            if (projection != null) RenderSystem.setProjectionMatrix(projection, projectionType)
        }
        return main
    }
}
