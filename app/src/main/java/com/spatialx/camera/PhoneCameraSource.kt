package com.spatialx.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.content.ContextCompat
import com.spatialx.core.CameraIntrinsics
import com.spatialx.core.FramePacket
import com.spatialx.core.ImageFormat
import com.spatialx.util.SXLog
import com.spatialx.util.SXTags
import java.util.concurrent.atomic.AtomicLong

/**
 * Rear phone camera source using the Camera2 API.
 *
 * Routes preview frames to a [SurfaceTexture] owned by [GLRenderer] for
 * zero-copy GPU rendering. Produces [FramePacket] metadata on each capture
 * completion for downstream consumers.
 */
class PhoneCameraSource(private val context: Context) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var surface: Surface? = null

    private val frameIdCounter = AtomicLong(0)
    private var intrinsics = CameraIntrinsics(0f, 0f, 0f, 0f, 0, 0)

    var frameCallback: FrameSourceCallback? = null
    var targetWidth = 1920
    var targetHeight = 1080
    var sensorRotationDegrees: Int = 0; private set

    @Volatile var isRunning = false; private set

    fun start(surfaceTexture: SurfaceTexture) {
        if (isRunning) return

        cameraThread = HandlerThread("SX_CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findRearCamera(manager) ?: run {
            SXLog.e(SXTags.CAM, "No rear camera found")
            return
        }

        loadIntrinsics(manager, cameraId)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            SXLog.e(SXTags.CAM, "Camera permission not granted")
            return
        }

        surfaceTexture.setDefaultBufferSize(targetWidth, targetHeight)
        surface = Surface(surfaceTexture)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                isRunning = true
                SXLog.i(SXTags.CAM, "Camera opened: $cameraId")
                createCaptureSession(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                SXLog.w(SXTags.CAM, "Camera disconnected")
                stop()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                SXLog.e(SXTags.CAM, "Camera error: $error")
                stop()
            }
        }, cameraHandler)
    }

    private fun createCaptureSession(camera: CameraDevice) {
        val target = surface ?: return

        camera.createCaptureSession(
            listOf(target),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(target)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }
                    session.setRepeatingRequest(
                        request.build(),
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: System.nanoTime()
                                val packet = FramePacket(
                                    frameId = frameIdCounter.incrementAndGet(),
                                    timestampNs = ts,
                                    width = targetWidth,
                                    height = targetHeight,
                                    format = ImageFormat.EXTERNAL_TEXTURE,
                                    intrinsics = intrinsics
                                )
                                frameCallback?.onFrame(packet)
                            }
                        },
                        cameraHandler
                    )
                    SXLog.i(SXTags.CAM, "Capture session started, ${targetWidth}x${targetHeight}")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    SXLog.e(SXTags.CAM, "Capture session configuration failed")
                }
            },
            cameraHandler
        )
    }

    fun stop() {
        isRunning = false
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        surface = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        SXLog.i(SXTags.CAM, "Camera stopped")
    }

    private fun findRearCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return null
    }

    private fun loadIntrinsics(manager: CameraManager, cameraId: String) {
        val chars = manager.getCameraCharacteristics(cameraId)

        sensorRotationDegrees = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        SXLog.i(SXTags.CAM, "Sensor orientation: ${sensorRotationDegrees}°")

        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focalLengths != null && sensorSize != null && focalLengths.isNotEmpty()) {
            val focalMm = focalLengths[0]
            val fx = focalMm * targetWidth / sensorSize.width
            val fy = focalMm * targetHeight / sensorSize.height
            intrinsics = CameraIntrinsics(fx, fy, targetWidth / 2f, targetHeight / 2f, targetWidth, targetHeight)
            SXLog.i(SXTags.CAM, "Intrinsics: fx=$fx fy=$fy cx=${targetWidth / 2f} cy=${targetHeight / 2f}")
        }
    }
}
