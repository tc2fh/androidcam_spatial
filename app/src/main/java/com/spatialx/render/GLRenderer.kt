package com.spatialx.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.spatialx.R
import com.spatialx.util.SXLog
import com.spatialx.util.SXTags
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    enum class Mode { AR, VIDEO }

    var mode: Mode = Mode.AR
    var displayRotation: Int = 0

    // --- AR mode ---
    var arSession: Session? = null
    private var hasSetTextureName = false
    private lateinit var screenTexCoords: FloatBuffer
    private lateinit var arTransformedTexCoords: FloatBuffer

    // --- Video mode ---
    var surfaceTexture: SurfaceTexture? = null
        private set
    private val texMatrix = FloatArray(16)
    private var videoFrameAvailable = false
    private val frameLock = Object()
    var onSurfaceTextureAvailable: ((SurfaceTexture) -> Unit)? = null
    var sensorRotation: Int = 0

    // --- Camera background (shared) ---
    private var cameraTextureId = 0
    private var bgProgram = 0
    private var bgTexMatrixLoc = 0
    private var bgTextureLoc = 0
    private lateinit var quadVertices: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // --- Cube (AR mode) ---
    private var cubeRenderer: CubeRenderer? = null
    private var cubeModelMatrix: FloatArray? = null
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // --- Timing ---
    @Volatile var lastFrameTimeMs: Long = 0L; private set
    @Volatile var fps: Float = 0f; private set
    private var frameCount = 0L
    private var fpsStartTimeNs = 0L

    // --- Tracking state (for HUD) ---
    @Volatile var trackingStateName: String = ""; private set
    @Volatile var planeCount: Int = 0; private set

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        cameraTextureId = texIds[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        if (mode == Mode.VIDEO) {
            surfaceTexture = SurfaceTexture(cameraTextureId).also { st ->
                st.setOnFrameAvailableListener { synchronized(frameLock) { videoFrameAvailable = true } }
            }
        }

        val vertSrc = ShaderUtil.readRawResource(context, R.raw.camera_vert)
        val fragSrc = ShaderUtil.readRawResource(context, R.raw.camera_frag)
        bgProgram = ShaderUtil.createProgram(vertSrc, fragSrc)
        bgTexMatrixLoc = GLES30.glGetUniformLocation(bgProgram, "uTexMatrix")
        bgTextureLoc = GLES30.glGetUniformLocation(bgProgram, "uTexture")

        val verts = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f)
        quadVertices = allocFloatBuffer(verts)

        val texCoords = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        quadTexCoords = allocFloatBuffer(texCoords)

        if (mode == Mode.AR) {
            // VIEW_NORMALIZED coords for the four quad corners (matches quadTexCoords layout)
            screenTexCoords = allocFloatBuffer(texCoords)
            // Initialize with same coords as fallback in case first frame misses geometry change
            arTransformedTexCoords = allocFloatBuffer(texCoords)

            cubeRenderer = CubeRenderer(context)
            cubeRenderer.init()
        }

        fpsStartTimeNs = System.nanoTime()
        SXLog.i(SXTags.RENDER, "GL surface created, mode=$mode, texture=$cameraTextureId")

        if (mode == Mode.VIDEO) {
            onSurfaceTextureAvailable?.invoke(surfaceTexture!!)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        if (mode == Mode.AR) {
            arSession?.setDisplayGeometry(displayRotation, width, height)
        }
        SXLog.i(SXTags.RENDER, "GL surface changed: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStartNs = System.nanoTime()
        if (mode == Mode.AR) drawARFrame() else drawVideoFrame()
        frameCount++
        val now = System.nanoTime()
        lastFrameTimeMs = (now - frameStartNs) / 1_000_000L
        val elapsed = (now - fpsStartTimeNs) / 1_000_000_000.0
        if (elapsed >= 1.0) {
            fps = (frameCount / elapsed).toFloat()
            frameCount = 0
            fpsStartTimeNs = now
        }
    }

    private fun drawARFrame() {
        val session = arSession ?: return
        if (!hasSetTextureName) {
            session.setCameraTextureName(cameraTextureId)
            hasSetTextureName = true
        }
        val frame: Frame
        try {
            frame = session.update()
        } catch (e: CameraNotAvailableException) {
            SXLog.e(SXTags.AR, "Camera not available during update", e)
            trackingStateName = "ERROR"
            return
        }
        val camera = frame.camera
        trackingStateName = camera.trackingState.name

        // Transform screen-space UV coords to camera texture coords using current API
        if (frame.hasDisplayGeometryChanged()) {
            screenTexCoords.rewind()
            arTransformedTexCoords.rewind()
            frame.transformCoordinates2d(
                Coordinates2d.VIEW_NORMALIZED,
                screenTexCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                arTransformedTexCoords
            )
        }
        arTransformedTexCoords.rewind()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        // Draw camera background — AR mode uses identity texMatrix; coords are pre-transformed
        GLES30.glUseProgram(bgProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glUniform1i(bgTextureLoc, 0)
        GLES30.glUniformMatrix4fv(bgTexMatrixLoc, 1, false, identityMatrix, 0)

        quadVertices.rewind()
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, quadVertices)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, arTransformedTexCoords)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)

        if (camera.trackingState == TrackingState.TRACKING) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)
            if (cubeModelMatrix == null) placeCubeAhead(camera.displayOrientedPose)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, cubeModelMatrix!!, 0)
            cubeRenderer?.draw(mvpMatrix)
        }

        planeCount = session.getAllTrackables(Plane::class.java)
            .count { it.trackingState == TrackingState.TRACKING }
    }

    private fun placeCubeAhead(cameraPose: com.google.ar.core.Pose) {
        val cameraMatrix = FloatArray(16)
        cameraPose.toMatrix(cameraMatrix, 0)
        // Column-major: column 2 (indices 8,9,10) is the camera's -Z forward in OpenGL convention
        val forwardX = -cameraMatrix[8]
        val forwardY = -cameraMatrix[9]
        val forwardZ = -cameraMatrix[10]
        val distance = 2f
        val cx = cameraPose.tx() + forwardX * distance
        val cy = cameraPose.ty() + forwardY * distance
        val cz = cameraPose.tz() + forwardZ * distance
        cubeModelMatrix = FloatArray(16).also {
            Matrix.setIdentityM(it, 0)
            Matrix.translateM(it, 0, cx, cy, cz)
        }
        SXLog.i(SXTags.RENDER, "Cube placed at (%.2f, %.2f, %.2f)".format(cx, cy, cz))
    }

    private fun drawVideoFrame() {
        synchronized(frameLock) {
            if (videoFrameAvailable) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(texMatrix)
                if (sensorRotation != 0) {
                    Matrix.translateM(texMatrix, 0, 0.5f, 0.5f, 0f)
                    Matrix.rotateM(texMatrix, 0, -sensorRotation.toFloat(), 0f, 0f, 1f)
                    Matrix.translateM(texMatrix, 0, -0.5f, -0.5f, 0f)
                }
                videoFrameAvailable = false
            }
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (cameraTextureId != 0) {
            GLES30.glUseProgram(bgProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            GLES30.glUniform1i(bgTextureLoc, 0)
            GLES30.glUniformMatrix4fv(bgTexMatrixLoc, 1, false, texMatrix, 0)
            quadVertices.rewind()
            quadTexCoords.rewind()
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, quadVertices)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, quadTexCoords)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
        }
    }

    private fun allocFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .also { it.position(0) }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        cubeRenderer?.release()
        if (bgProgram != 0) { GLES30.glDeleteProgram(bgProgram); bgProgram = 0 }
        if (cameraTextureId != 0) { GLES30.glDeleteTextures(1, intArrayOf(cameraTextureId), 0); cameraTextureId = 0 }
        SXLog.i(SXTags.RENDER, "GLRenderer released")
    }
}
