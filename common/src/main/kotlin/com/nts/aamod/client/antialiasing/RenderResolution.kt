package com.nts.aamod.client.antialiasing

import kotlin.math.ceil

data class RenderResolution(
    val outputWidth: Int,
    val outputHeight: Int,
    val requestedScale: Double,
    val worldWidth: Int,
    val worldHeight: Int
) {
    companion object {
        fun create(width: Int, height: Int, scale: Double, maximum: Int): RenderResolution {
            require(width > 0 && height > 0 && scale.isFinite() && scale in 1.0..4.0)
            val worldWidth = ceil(width * scale).toInt()
            val worldHeight = ceil(height * scale).toInt()
            require(worldWidth <= maximum && worldHeight <= maximum) {
                "SSAA ${worldWidth}x$worldHeight exceeds device texture limit $maximum"
            }
            return RenderResolution(width, height, scale, worldWidth, worldHeight)
        }
    }
}
