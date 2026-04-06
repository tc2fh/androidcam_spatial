package com.spatialx.core

import java.util.concurrent.atomic.AtomicInteger

/** Pixel format of the frame data. */
enum class ImageFormat { NV21, YUV_420_888, EXTERNAL_TEXTURE, UNKNOWN }

/** Raw YUV plane buffers for CPU-side consumers. */
data class YuvPlanes(
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val yRowStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int
)

/**
 * Immutable frame container with explicit retain/release lifecycle.
 *
 * The render path consumes the GPU texture directly (zero-copy).
 * Async ML consumers must either retain/release the packet,
 * or copy into a worker-owned buffer.
 */
class FramePacket(
    val frameId: Long,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val format: ImageFormat,
    val gpuTextureId: Int? = null,
    val yuvPlanes: YuvPlanes? = null,
    val intrinsics: CameraIntrinsics,
    private val onRelease: (() -> Unit)? = null
) {
    private val refCount = AtomicInteger(1)

    /** True if this packet has not been fully released. */
    val isValid: Boolean get() = refCount.get() > 0

    /** Increment reference count. Must be balanced with a [release] call. */
    fun retain() {
        val count = refCount.incrementAndGet()
        check(count > 1) { "retain() called on already-released FramePacket $frameId" }
    }

    /** Decrement reference count. Invokes onRelease when it reaches zero. */
    fun release() {
        val count = refCount.decrementAndGet()
        check(count >= 0) { "release() called too many times on FramePacket $frameId" }
        if (count == 0) {
            onRelease?.invoke()
        }
    }
}
