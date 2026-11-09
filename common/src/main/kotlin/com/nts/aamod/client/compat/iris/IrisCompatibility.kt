package com.nts.aamod.client.compat.iris

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.nts.aamod.AAMod
import com.nts.aamod.client.antialiasing.ColorEncoding
import com.nts.aamod.client.antialiasing.FrameContext
import com.nts.aamod.client.antialiasing.RenderEnvironment
import com.nts.aamod.client.antialiasing.RenderEnvironmentProvider
import com.nts.aamod.client.antialiasing.RenderInput
import com.nts.aamod.client.antialiasing.RenderResolution
import com.nts.aamod.client.antialiasing.RenderStage
import com.nts.aamod.client.antialiasing.RenderSupport
import com.nts.aamod.client.antialiasing.VanillaRenderEnvironment
import com.nts.aamod.client.option.IrisSettings
import com.nts.aamod.client.option.VideoAntiAliasingSettings
import com.nts.aamod.platform.PlatformHelper
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

internal interface IrisAccess {
    fun snapshot(): IrisState
}

internal data class IrisState(
    val installed: Boolean = false,
    val shaders: Boolean = false,
    val preparing: Boolean = false,
    val shadow: Boolean = false,
    val displayColor: Boolean = true,
    val error: String? = null
)

object IrisCompatibility {
    private var initialized = false
    private var access: IrisAccess? = null
    private var state = IrisState()
    private var revision = 0L
    private var frame = 0L
    private var outputFrame = -1L
    private var outputTarget: RenderTarget? = null
    private var outputColor: Any? = null
    private var loggedError = false

    fun beginFrame(): RenderEnvironmentProvider {
        frame++
        outputFrame = -1L
        outputTarget = null
        outputColor = null
        initialize()
        readState()
        if (!state.installed) return VanillaRenderEnvironment
        return IrisEnvironment(state, revision, IrisSettings.policy.get())
    }

    @JvmStatic
    fun pipelineChanged() {
        revision++
        outputFrame = -1L
        outputTarget = null
        outputColor = null
    }

    @JvmStatic
    fun finalOutputReady() {
        val target = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        outputFrame = frame
        outputTarget = target
        outputColor = target.colorTexture
    }

    fun describe(): Component {
        val key = when {
            state.error != null -> state.error
            !state.installed -> "aamod.compat.iris.absent"
            state.preparing -> "aamod.compat.iris.preparing"
            !state.shaders -> "aamod.compat.iris.disabled"
            IrisSettings.policy.get() == IrisSettings.AUTOMATIC -> "aamod.compat.iris.automatic"
            !state.displayColor -> "aamod.compat.iris.color"
            else -> "aamod.compat.iris.overlay"
        }
        return Component.translatable(checkNotNull(key))
    }

    private fun initialize() {
        if (initialized) return
        initialized = true
        val version = PlatformHelper.getModVersion("iris") ?: return
        state = IrisState(installed = true)
        if (version.substringBefore('+') != "1.11.4") {
            state = state.copy(error = "aamod.compat.iris.version")
            AAMod.LOGGER.warn("Iris {} is outside the verified 1.11.4 rendering adapter", version)
            return
        }
        try {
            access = IrisApiAccess()
        } catch (exception: Exception) {
            queryFailed(exception)
        } catch (error: LinkageError) {
            queryFailed(error)
        }
    }

    private fun queryFailed(exception: Throwable) {
        state = state.copy(installed = true, error = "aamod.compat.iris.query_failed")
        if (!loggedError) {
            AAMod.LOGGER.error("Iris compatibility query failed; anti-aliasing is paused", exception)
            loggedError = true
        }
    }

    private fun readState(): IrisState {
        access?.let { adapter ->
            try {
                state = adapter.snapshot()
            } catch (exception: Exception) {
                queryFailed(exception)
            } catch (error: LinkageError) {
                queryFailed(error)
            }
        }
        return state
    }

    private data class IrisEnvironment(val snapshot: IrisState, val revision: Long, val policy: Int) : RenderEnvironmentProvider {
        override val identity: Any = Triple(snapshot.copy(shadow = false), revision, policy)

        override fun support(mode: Int): RenderSupport {
            val reason = when {
                snapshot.error != null -> snapshot.error
                snapshot.shadow -> "aamod.compat.iris.shadow"
                snapshot.preparing -> "aamod.compat.iris.preparing"
                !snapshot.shaders -> null
                policy == IrisSettings.AUTOMATIC -> "aamod.compat.iris.automatic"
                mode == VideoAntiAliasingSettings.SSAA -> "aamod.compat.iris.ssaa"
                mode == VideoAntiAliasingSettings.TAA -> "aamod.compat.iris.taa"
                !snapshot.displayColor -> "aamod.compat.iris.color"
                else -> null
            }
            return RenderSupport(reason == null, reason)
        }

        override fun preparationInputs(context: FrameContext): Set<RenderInput> =
            if (snapshot.shaders || snapshot.preparing) emptySet() else VanillaRenderEnvironment.preparationInputs(context)

        override fun planResolution(context: FrameContext, scale: Double, maximum: Int): RenderResolution {
            check(!snapshot.shaders && !snapshot.preparing) { "Iris shader resolution control is unavailable" }
            return VanillaRenderEnvironment.planResolution(context, scale, maximum)
        }

        override fun capture(stage: RenderStage, target: RenderTarget, context: FrameContext): RenderEnvironment {
            val vanilla = VanillaRenderEnvironment.capture(stage, target, context)
            val current = readState()
            val reason = when {
                current.error != null -> current.error
                current.shadow -> "aamod.compat.iris.shadow"
                current.preparing -> "aamod.compat.iris.preparing"
                current.shaders != snapshot.shaders || revision != IrisCompatibility.revision -> "aamod.compat.iris.changed"
                !snapshot.shaders -> null
                !current.displayColor || target.colorTexture?.format != GpuFormat.RGBA8_UNORM -> "aamod.compat.iris.color"
                stage != RenderStage.FINAL_COLOR || outputFrame != frame || outputTarget !== target || outputColor !== target.colorTexture -> "aamod.compat.iris.output"
                else -> null
            }
            return if (reason == null) vanilla else vanilla.copy(
                colorEncoding = ColorEncoding.UNKNOWN, inputs = emptySet(), unavailableReason = reason
            )
        }
    }
}
