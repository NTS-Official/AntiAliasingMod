package com.nts.aamod.client.mixin.option

import com.nts.aamod.client.option.VideoAntiAliasingSettings.getOption
import net.minecraft.client.OptionInstance
import net.minecraft.client.Options
import net.minecraft.client.gui.screens.options.VideoSettingsScreen
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(VideoSettingsScreen::class)
abstract class VideoSettingScreenMixin {
    private companion object {
        @Inject(method = ["qualityOptions"], at = [At("RETURN")], cancellable = true)
        @JvmStatic
        private fun qualityOptions(options: Options?, cir: CallbackInfoReturnable<Array<OptionInstance<*>?>?>) {
            val vanillaOptions: Array<OptionInstance<*>?> = cir.getReturnValue()!!
            val extendedOptions = vanillaOptions.copyOf(vanillaOptions.size + 1)
            extendedOptions[vanillaOptions.size] = getOption()
            cir.setReturnValue(extendedOptions)
        }
    }
}
