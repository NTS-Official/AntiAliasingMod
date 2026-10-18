package com.nts.aamod

import net.fabricmc.api.ModInitializer
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object AAMod : ModInitializer {
	const val MOD_ID = "aamod"
	const val MOD_NAME = "Anti Aliasing Mod"
	const val CLASS_PATH = "com.nts.aamod"

	val LOGGER = LoggerFactory.getLogger(MOD_ID)

	override fun onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("[$MOD_ID] $MOD_NAME is initialized.")
	}

	fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
}
