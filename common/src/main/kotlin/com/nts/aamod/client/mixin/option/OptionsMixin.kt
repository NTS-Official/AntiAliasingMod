package com.nts.aamod.client.mixin.option

import com.nts.aamod.client.option.FxaaSettings
import com.nts.aamod.client.option.IrisSettings
import com.nts.aamod.client.option.SpatialSettings
import com.nts.aamod.client.option.TemporalSettings
import com.nts.aamod.client.option.VideoAntiAliasingSettings
import com.nts.aamod.client.option.VideoAntiAliasingSettings.applyFromPreset
import com.nts.aamod.client.option.VideoAntiAliasingSettings.beginLoading
import com.nts.aamod.client.option.VideoAntiAliasingSettings.endLoading
import com.nts.aamod.client.option.VideoAntiAliasingSettings.getCurrentAAMode
import com.nts.aamod.client.option.VideoAntiAliasingSettings.setLoadedMode
import net.minecraft.client.GraphicsPreset
import net.minecraft.client.Options
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Options::class)
abstract class OptionsMixin {
    @Inject(method = ["load"], at = [At("HEAD")])
    private fun `aamod$beginLoading`(callback: CallbackInfo?) {
        beginLoading()
        FxaaSettings.resetDefaults()
        SpatialSettings.resetDefaults()
        IrisSettings.resetDefaults()
        TemporalSettings.resetDefaults()
    }

    @Inject(method = ["load"], at = [At("RETURN")])
    private fun `aamod$endLoading`(callback: CallbackInfo?) {
        endLoading()
    }

    @Inject(method = ["processOptions"], at = [At("TAIL")])
    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    private fun `aamod$processOptions`(access: Options.FieldAccess, callback: CallbackInfo?) {
        val loadedMode = (access as OptionFieldAccess).`aamod$processInteger`(
            VideoAntiAliasingSettings.OPTIONS_KEY, getCurrentAAMode()
        )
        setLoadedMode(loadedMode)
        FxaaSettings.processOptions(access)
        SpatialSettings.processOptions(access)
        IrisSettings.processOptions(access)
        TemporalSettings.processOptions(access)
    }

    @Inject(method = ["applyGraphicsPreset"], at = [At("TAIL")])
    private fun `aamod$applyPreset`(preset: GraphicsPreset, callback: CallbackInfo?) {
        applyFromPreset(preset.getSerializedName())
    }
}
