package com.spatialx.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.spatialx.R
import com.spatialx.util.SXLog
import com.spatialx.util.SXTags
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Core OpenGL ES 3.0 renderer.
 *
 * Manages a [SurfaceTexture] that receives camera frames (from PhoneCameraSource
 * or VideoFileSource) and renders them as a fullscreen textured quad using
 * GL_TEXTURE_EXTERNAL_OES. Tracks frame timing for the debug HUD.
 */
class GLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // --- Camera texture ---
    private var cameraTextureId = 0
    var surfaceTexture: SurfaceTexture? = null
        private set
    private val texMatrix = FloatArray(16)
    private var frameAvailable = false
    private val frameLock = Object()

    // --- Shader program ---
    private var program = 0
    private var uTexMatrixLoc = 0
    private var uTextureLoc = 0

    // --- Fullscreen quad geometry ---
    private lateinit var quadVertices: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer

    // --- Timing ---
    @Volatile var lastFrameTimeMs: Long = 0L; private set
    @Volatile var fps: Float = 0f; private set
    private var frameCount = 0L
    private var fpsStartTimeNs = 0L

    /** Called on the UI thread once the SurfaceTexture is ready for camera binding. */
    var onSurfaceTextureAvailable: ((SurfaceTexture) -> Unit)? = null

    /** Called each time the GL surface is (re-)configured — including preserved-context resumes. */
    var onSurfaceReady: ((SurfaceTexture) -> Unit)? = null

    // ---------------------------------------------------------------
    // GLSurfaceView.Renderer
    // ---------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        // Create external texture for camera
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        cameraTextureId = texIds[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(cameraTextureId).also { st ->
            st.setOnFrameAvailableListener {
                synchronized(frameLock) { frameAvailable = true }
            }
        }

        // Load and compile shaders
        val vertSrc = ShaderUtil.readRawResource(context, R.raw.camera_vert)
        val fragSrc = ShaderUtil.readRawResource(context, R.raw.camera_frag)
        program = ShaderUtil.createProgram(vertSrc, fragSrc)
        uTexMatrixLoc = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uTextureLoc = GLES30.glGetUniformLocation(program, "uTexture")

        // Fullscreen quad: two triangles as a triangle strip
        val vertices = floatArrayOf(
            -1f, -1f, 0f,
             1f, -1f, 0f,
            -1f,  1f, 0f,
             1f,  1f, 0f
        )
        val texCoords = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
        quadVertices = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).also { it.position(0) }
        quadTexCoords = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords).also { it.position(0) }

        fpsStartTimeNs = System.nanoTime()
        SXLog.i(SXTags.RENDER, "GL surface created, texture=$cameraTextureId")

        onSurfaceTextureAvailable?.invoke(surfaceTexture!!)
    }

    /** Width/height of the camera feed — set before surface creation or updated on source change. */
    var cameraWidth = 1920
    var cameraHeight = 1080

    /** Sensor orientation in degrees (0, 90, 180, 270). Set from PhoneCameraSource. */
    var sensorRotation: Int = 0
        set(value) {
            field = value
            // Recalculate aspect ratio now that rotation is known
            if (lastSurfaceWidth > 0) updateQuadForAspect(lastSurfaceWidth, lastSurfaceHeight)
        }

    private var lastSurfaceWidth = 0
    private var lastSurfaceHeight = 0

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        lastSurfaceWidth = width
        lastSurfaceHeight = height
        updateQuadForAspect(width, height)
        SXLog.i(SXTags.RENDER, "GL surface changed: ${width}x${height}")

        // Always notify — covers both fresh context and preserved-context resume
        surfaceTexture?.let { st -> onSurfaceReady?.invoke(st) }
    }

    /**
     * Adjusts the fullscreen quad so the entire camera image is visible
     * (fit / zoom-to-fit). Black bars appear on the shorter axis.
     */
    private fun updateQuadForAspect(surfaceWidth: Int, surfaceHeight: Int) {
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
        val cameraAspect = cameraWidth.toFloat() / cameraHeight

        // Fit: show entire image, black bars on the axis with extra space
        var sx = 1f
        var sy = 1f
        if (cameraAspect > surfaceAspect) {
            // Camera wider than screen → fill width, reduce height
            sy = surfaceAspect / cameraAspect
        } else {
            // Screen wider than camera → fill height, reduce width
            sx = cameraAspect / surfaceAspect
        }

        val vertices = floatArrayOf(
            -sx, -sy, 0f,
             sx, -sy, 0f,
            -sx,  sy, 0f,
             sx,  sy, 0f
        )
        quadVertices = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).also { it.position(0) }
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStartNs = System.nanoTime()

        // Update texture if a new frame is available
        synchronized(frameLock) {
            if (frameAvailable) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(texMatrix)
                // Apply sensor rotation around texture center (0.5, 0.5)
                if (sensorRotation != 0) {
                    Matrix.translateM(texMatrix, 0, 0.5f, 0.5f, 0f)
                    Matrix.rotateM(texMatrix, 0, -sensorRotation.toFloat(), 0f, 0f, 1f)
                    Matrix.translateM(texMatrix, 0, -0.5f, -0.5f, 0f)
                }
                frameAvailable = false
            }
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // Draw camera background
        if (cameraTextureId != 0) {
            GLES30.glUseProgram(program)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            GLES30.glUniform1i(uTextureLoc, 0)
            GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, quadVertices)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, quadTexCoords)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
        }

        // Update timing
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

    // ---------------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------------

    /** Call from GL thread (e.g. via GLSurfaceView.queueEvent). */
    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        if (cameraTextureId != 0) {
            val ids = intArrayOf(cameraTextureId)
            GLES30.glDeleteTextures(1, ids, 0)
            cameraTextureId = 0
        }
        SXLog.i(SXTags.RENDER, "GLRenderer released")
    }
}
