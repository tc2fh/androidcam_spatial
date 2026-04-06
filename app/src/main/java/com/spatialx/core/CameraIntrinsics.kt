package com.spatialx.core

/**
 * Pinhole camera intrinsics.
 * fx, fy: focal length in pixels
 * cx, cy: principal point in pixels
 */
data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int
)
