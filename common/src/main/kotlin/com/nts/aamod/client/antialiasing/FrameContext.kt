package com.nts.aamod.client.antialiasing

import net.minecraft.client.CameraType
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Matrix4fc

enum class ViewIdentity {
    MAIN
}

class CameraSnapshot(
    projection: Matrix4fc,
    view: Matrix4fc,
    val position: Vec3
) {
    private val projectionMatrix = Matrix4f(projection)
    private val viewMatrix = Matrix4f(view)

    fun projection(destination: Matrix4f): Matrix4f = destination.set(projectionMatrix)

    fun view(destination: Matrix4f): Matrix4f = destination.set(viewMatrix)

    companion object {
        fun capture(projection: Matrix4fc, view: Matrix4fc, position: Vec3): CameraSnapshot? {
            if (!isUsable(projection) || !isUsable(view) ||
                !position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite()
            ) return null
            return CameraSnapshot(projection, view, position)
        }

        fun isUsable(matrix: Matrix4fc): Boolean {
            if (!matrix.isFinite) return false
            val determinant = matrix.determinant()
            return determinant.isFinite() && determinant != 0f
        }
    }
}

data class FrameContext(
    val frameId: Long,
    val requestedMode: Int,
    val width: Int,
    val height: Int,
    val hasWorld: Boolean,
    val dimension: Identifier?,
    val cameraType: CameraType,
    val view: ViewIdentity = ViewIdentity.MAIN,
    val camera: CameraSnapshot? = null
)
