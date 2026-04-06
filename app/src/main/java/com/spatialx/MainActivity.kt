package com.spatialx

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.spatialx.camera.PhoneCameraSource
import com.spatialx.render.GLRenderer
import com.spatialx.ui.OverlayUI
import com.spatialx.util.SXLog
import com.spatialx.util.SXTags

/**
 * Main activity for SpatialX Milestone 0.
 *
 * Wires together GLSurfaceView + GLRenderer + PhoneCameraSource + OverlayUI.
 * Manages camera permissions and lifecycle (pause/resume without leaks).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: GLRenderer
    private lateinit var cameraSource: PhoneCameraSource
    private lateinit var overlayUI: OverlayUI

companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        SXLog.i(SXTags.LIFECYCLE, "onCreate")

        renderer = GLRenderer(this)
        cameraSource = PhoneCameraSource(this)

        glSurfaceView = findViewById<GLSurfaceView>(R.id.gl_surface).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        // Set up HUD overlay
        overlayUI = OverlayUI(this).apply {
            sourceLabel = "phone_camera"
            fpsProvider = { renderer.fps }
            frameTimeProvider = { renderer.lastFrameTimeMs }
        }
        findViewById<FrameLayout>(R.id.root).addView(overlayUI)

        // When GL surface is first created, request permission if needed
        renderer.onSurfaceTextureAvailable = { surfaceTexture ->
            runOnUiThread {
                if (!hasCameraPermission()) {
                    requestCameraPermission()
                }
            }
        }

        // Fires on every surface (re-)configuration — both fresh and preserved-context resume.
        // This is the sole camera-start path to avoid races between onResume and the GL thread.
        renderer.onSurfaceReady = { surfaceTexture ->
            runOnUiThread {
                if (hasCameraPermission() && !cameraSource.isRunning) {
                    cameraSource.start(surfaceTexture)
                    renderer.sensorRotation = cameraSource.sensorRotationDegrees
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        overlayUI.startUpdating()
        // Camera restart is handled by renderer.onSurfaceReady callback
        // which fires once the GL thread resumes and re-configures the surface.
        SXLog.i(SXTags.LIFECYCLE, "onResume")
    }

    override fun onPause() {
        super.onPause()
        overlayUI.stopUpdating()
        cameraSource.stop()
        glSurfaceView.onPause()
        SXLog.i(SXTags.LIFECYCLE, "onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        glSurfaceView.queueEvent { renderer.release() }
        SXLog.i(SXTags.LIFECYCLE, "onDestroy")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            SXLog.i(SXTags.CAM, "Camera permission granted")
            renderer.surfaceTexture?.let { cameraSource.start(it) }
        } else {
            SXLog.e(SXTags.CAM, "Camera permission denied")
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
    }
}
