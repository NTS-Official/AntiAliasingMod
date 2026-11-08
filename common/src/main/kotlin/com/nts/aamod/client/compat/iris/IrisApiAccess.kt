package com.nts.aamod.client.compat.iris

import net.irisshaders.iris.api.v0.IrisApi
import net.irisshaders.iris.Iris
import net.irisshaders.iris.gui.option.IrisVideoSettings
import net.irisshaders.iris.pathways.colorspace.ColorSpace

internal class IrisApiAccess : IrisAccess {
    private val api = IrisApi.getInstance()

    override fun snapshot(): IrisState {
        val shaders = api.isShaderPackInUse
        return IrisState(
            installed = true,
            shaders = shaders,
            preparing = !shaders && api.config.areShadersEnabled() && Iris.getCurrentPack().isPresent,
            shadow = api.isRenderingShadowPass,
            displayColor = IrisVideoSettings.colorSpace == ColorSpace.SRGB
        )
    }
}
