package com.spatialx.core

/**
 * 6DoF pose: position (x, y, z) + rotation (quaternion x, y, z, w).
 * Coordinate convention: right-handed, Y-up, camera looks along -Z.
 * (Will be validated against SLAM output in M1.)
 */
data class Pose(
    val tx: Float = 0f,
    val ty: Float = 0f,
    val tz: Float = 0f,
    val qx: Float = 0f,
    val qy: Float = 0f,
    val qz: Float = 0f,
    val qw: Float = 1f,
    val timestampNs: Long = 0L
) {
    companion object {
        val IDENTITY = Pose()
    }
}
