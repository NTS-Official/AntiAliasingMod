package com.nts.aamod.client.mixin.option

import com.nts.aamod.client.gui.TaaDebugEntry
import com.llamalad7.mixinextras.injector.ModifyReturnValue
import net.minecraft.client.gui.components.debug.DebugScreenEntries
import net.minecraft.client.gui.components.debug.DebugScreenEntry
import net.minecraft.resources.Identifier
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(DebugScreenEntries::class)
abstract class DebugScreenEntriesMixin {
    private companion object {
        @ModifyReturnValue(method = ["allEntries"], at = [At("RETURN")])
        @JvmStatic
        private fun `aamod$listDiagnostics`(entries: Map<Identifier, DebugScreenEntry>): Map<Identifier, DebugScreenEntry> =
            entries + (TaaDebugEntry.ID to TaaDebugEntry)

        @ModifyReturnValue(method = ["getEntry"], at = [At("RETURN")])
        @JvmStatic
        private fun `aamod$findDiagnostics`(entry: DebugScreenEntry?, identifier: Identifier): DebugScreenEntry? =
            if (identifier == TaaDebugEntry.ID) TaaDebugEntry else entry
    }
}
