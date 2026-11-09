package com.nts.aamod.client.mixin.option

import com.nts.aamod.client.gui.TaaDebugEntry
import net.minecraft.client.gui.components.debug.DebugScreenEntryList
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus
import net.minecraft.client.gui.components.debug.DebugScreenProfile
import net.minecraft.resources.Identifier
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(DebugScreenEntryList::class)
abstract class DebugScreenEntryListMixin {
    @Shadow
    @Final
    private val allStatuses: MutableMap<Identifier, DebugScreenEntryStatus>? = null

    @Inject(method = ["resetToProfile"], at = [At("RETURN")])
    private fun `aamod$defaultDiagnostics`(profile: DebugScreenProfile, callback: CallbackInfo) {
        allStatuses!!.putIfAbsent(TaaDebugEntry.ID, DebugScreenEntryStatus.IN_OVERLAY)
    }
}
