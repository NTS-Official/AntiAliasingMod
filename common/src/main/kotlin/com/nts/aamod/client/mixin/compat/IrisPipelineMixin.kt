package com.nts.aamod.client.mixin.compat

import com.nts.aamod.client.compat.iris.IrisCompatibility
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Pseudo
@Mixin(targets = ["net.irisshaders.iris.pipeline.IrisRenderingPipeline"], remap = false)
abstract class IrisPipelineMixin {
    @Inject(method = ["<init>", "destroy"], at = [At("RETURN")], require = 0)
    private fun `aamod$pipelineChanged`(callback: CallbackInfo) {
        IrisCompatibility.pipelineChanged()
    }

    @Inject(method = ["finalizeGameRendering"], at = [At("RETURN")], require = 0)
    private fun `aamod$finalOutput`(callback: CallbackInfo) {
        IrisCompatibility.finalOutputReady()
    }
}
