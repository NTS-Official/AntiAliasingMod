package com.nts.aamod.client.gui

import com.nts.aamod.AAMod
import com.nts.aamod.client.antialiasing.AntiAliasingController
import net.minecraft.client.gui.components.debug.DebugEntryCategory
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer
import net.minecraft.client.gui.components.debug.DebugScreenEntry
import net.minecraft.network.chat.Component
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk

object TaaDebugEntry : DebugScreenEntry {
    val ID = AAMod.id("taa_diagnostics")
    private val CATEGORY = DebugEntryCategory(Component.translatable("aamod.debug.category"), 2f)

    override fun display(displayer: DebugScreenDisplayer, level: Level?, clientChunk: LevelChunk?, serverChunk: LevelChunk?) {
        displayer.addToGroup(ID, AntiAliasingController.temporalDiagnostics())
    }

    override fun category(): DebugEntryCategory = CATEGORY
}
