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
import com.spatialx.ar.ARSessionManager
import com.spatialx.camera.VideoFileSource
import com.spatialx.render.GLRenderer
import com.spatialx.ui.OverlayUI
import com.spatialx.util.SXLog
import com.spatialx.util.SXTags

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: GLRenderer
    private lateinit var overlayUI: OverlayUI

    private var arSessionManager: ARSessionManager? = null
    private var videoSource: VideoFileSource? = null
    private var videoPath: String? = null

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
    }

    private val isVideoMode: Boolean get() = videoPath != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        videoPath = intent.getStringExtra("video_path")
        SXLog.i(SXTags.LIFECYCLE, "onCreate, videoMode=$isVideoMode, videoPath=$videoPath")

        renderer = GLRenderer(this).apply {
            mode = if (isVideoMode) GLRenderer.Mode.VIDEO else GLRenderer.Mode.AR
            @Suppress("DEPRECATION")
            displayRotation = windowManager.defaultDisplay.rotation
        }

        if (!isVideoMode) {
            arSessionManager = ARSessionManager(this)
        } else {
            videoSource = VideoFileSource()
        }

        glSurfaceView = findViewById<GLSurfaceView>(R.id.gl_surface).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        overlayUI = OverlayUI(this).apply {
            sourceLabel = if (isVideoMode) "video_file" else "arcore"
            fpsProvider = { renderer.fps }
            frameTimeProvider = { renderer.lastFrameTimeMs }
            if (!isVideoMode) {
                trackingStateProvider = { renderer.trackingStateName }
                planeCountProvider = { renderer.planeCount }
            }
        }
        findViewById<FrameLayout>(R.id.root).addView(overlayUI)

        if (isVideoMode) {
            renderer.onSurfaceTextureAvailable = { surfaceTexture ->
                videoSource?.start(surfaceTexture, videoPath!!)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isVideoMode) {
            if (!hasCameraPermission()) {
                requestCameraPermission()
                return
            }
            setupAndResumeAR()
        }
        glSurfaceView.onResume()
        overlayUI.startUpdating()
        SXLog.i(SXTags.LIFECYCLE, "onResume")
    }

    override fun onPause() {
        super.onPause()
        overlayUI.stopUpdating()
        if (!isVideoMode) {
            arSessionManager?.pause()
        } else {
            videoSource?.stop()
        }
        glSurfaceView.onPause()
        SXLog.i(SXTags.LIFECYCLE, "onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        glSurfaceView.queueEvent { renderer.release() }
        arSessionManager?.destroy()
        SXLog.i(SXTags.LIFECYCLE, "onDestroy")
    }

    private fun setupAndResumeAR() {
        val manager = arSessionManager ?: return
        if (!manager.tryCreateSession()) {
            SXLog.w(SXTags.AR, "ARCore session not ready")
            return
        }
        renderer.arSession = manager.session
        manager.resume()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            SXLog.i(SXTags.CAM, "Camera permission granted")
            if (!isVideoMode) setupAndResumeAR()
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
