package com.spatialx.camera

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import com.spatialx.util.SXLog
import com.spatialx.util.SXTags
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Video file source using MediaExtractor + MediaCodec.
 *
 * Decodes a .mp4 file to a [SurfaceTexture] with native frame timing.
 * Supports looping for test replay.
 */
class VideoFileSource {

    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var decodeThread: Thread? = null
    private val running = AtomicBoolean(false)

    var loop = true

    @Volatile var isRunning = false; private set
    @Volatile var videoWidth = 0; private set
    @Volatile var videoHeight = 0; private set

    fun start(surfaceTexture: SurfaceTexture, videoPath: String) {
        if (running.get()) return

        val ext = MediaExtractor()
        ext.setDataSource(videoPath)

        val trackIndex = findVideoTrack(ext) ?: run {
            SXLog.e(SXTags.VIDEO, "No video track found in $videoPath")
            ext.release()
            return
        }

        ext.selectTrack(trackIndex)
        val format = ext.getTrackFormat(trackIndex)
        videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight)
        val surface = Surface(surfaceTexture)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, surface, null, 0)
        codec.start()

        extractor = ext
        decoder = codec
        running.set(true)
        isRunning = true

        SXLog.i(SXTags.VIDEO, "Started: ${videoWidth}x${videoHeight} mime=$mime path=$videoPath")

        decodeThread = Thread({ decodeLoop(ext, codec) }, "SX_VideoDecoder").also { it.start() }
    }

    private fun decodeLoop(extractor: MediaExtractor, codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var firstPtsUs = -1L
        var startTimeUs = -1L

        while (running.get()) {
            // Feed input buffers
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)!!
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) {
                    if (loop) {
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        firstPtsUs = -1L
                        startTimeUs = -1L
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                        SXLog.d(SXTags.VIDEO, "Looping video")
                        continue
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        break
                    }
                } else {
                    val pts = extractor.sampleTime
                    codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0)
                    extractor.advance()
                }
            }

            // Drain output with native timing
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex >= 0) {
                val ptsUs = bufferInfo.presentationTimeUs
                if (firstPtsUs < 0) {
                    firstPtsUs = ptsUs
                    startTimeUs = System.nanoTime() / 1000
                }

                // Wait for native presentation time
                val targetUs = startTimeUs + (ptsUs - firstPtsUs)
                val nowUs = System.nanoTime() / 1000
                val waitUs = targetUs - nowUs
                if (waitUs > 1000) {
                    try {
                        Thread.sleep(waitUs / 1000, ((waitUs % 1000) * 1000).toInt())
                    } catch (_: InterruptedException) {
                        break
                    }
                }

                codec.releaseOutputBuffer(outputIndex, true)
            }
        }

        isRunning = false
        SXLog.i(SXTags.VIDEO, "Decode loop ended")
    }

    fun stop() {
        running.set(false)
        decodeThread?.interrupt()
        decodeThread?.join(2000)
        decodeThread = null

        try { decoder?.stop() } catch (_: Exception) {}
        decoder?.release()
        decoder = null
        extractor?.release()
        extractor = null
        isRunning = false
        SXLog.i(SXTags.VIDEO, "VideoFileSource stopped")
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) return i
        }
        return null
    }
}
