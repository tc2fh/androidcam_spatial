package com.spatialx.camera

import com.spatialx.core.FramePacket

/** Common callback interface for frame sources (camera, video file). */
fun interface FrameSourceCallback {
    fun onFrame(packet: FramePacket)
}
