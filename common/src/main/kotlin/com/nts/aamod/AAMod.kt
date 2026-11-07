package com.nts.aamod

import com.nts.aamod.platform.PlatformHelper
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object AAMod {
    const val MOD_ID = "aamod"
    const val MOD_NAME = "Anti Aliasing Mod"
    const val CLASS_PATH = "com.nts.aamod"

    @JvmField
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    @JvmStatic
    fun init() {
        LOGGER.info("[$MOD_ID] $MOD_NAME initialized on ${PlatformHelper.platformName} (client=${PlatformHelper.isClient}).")
    }

	fun id(path: String) = Identifier.fromNamespaceAndPath(MOD_ID, path)
}
