package com.spatialx.render

import android.content.Context
import android.opengl.GLES30
import com.spatialx.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a 1m wireframe cube using GL_LINES.
 * Call [init] once on the GL thread, then [draw] each frame with an MVP matrix.
 */
class CubeRenderer(private val context: Context) {

    private var program = 0
    private var uMVPLoc = 0
    private var uColorLoc = 0
    private lateinit var vertexBuffer: FloatBuffer

    companion object {
        private const val H = 0.5f // half-edge length for 1m cube
        private const val VERTEX_COUNT = 24 // 12 edges * 2 vertices

        // 12 edges of a cube as line segment pairs
        private val VERTICES = floatArrayOf(
            // Bottom face (y = -H)
            -H, -H, -H,  H, -H, -H,
             H, -H, -H,  H, -H,  H,
             H, -H,  H, -H, -H,  H,
            -H, -H,  H, -H, -H, -H,
            // Top face (y = +H)
            -H,  H, -H,  H,  H, -H,
             H,  H, -H,  H,  H,  H,
             H,  H,  H, -H,  H,  H,
            -H,  H,  H, -H,  H, -H,
            // Vertical edges
            -H, -H, -H, -H,  H, -H,
             H, -H, -H,  H,  H, -H,
             H, -H,  H,  H,  H,  H,
            -H, -H,  H, -H,  H,  H,
        )
    }

    fun init() {
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(VERTICES)
            .also { it.position(0) }

        val vertSrc = ShaderUtil.readRawResource(context, R.raw.cube_vert)
        val fragSrc = ShaderUtil.readRawResource(context, R.raw.cube_frag)
        program = ShaderUtil.createProgram(vertSrc, fragSrc)
        uMVPLoc = GLES30.glGetUniformLocation(program, "uMVP")
        uColorLoc = GLES30.glGetUniformLocation(program, "uColor")
    }

    /**
     * Draws the wireframe cube with the given MVP matrix.
     * @param mvpMatrix 16-element column-major MVP matrix
     * @param r red [0..1]
     * @param g green [0..1]
     * @param b blue [0..1]
     */
    fun draw(mvpMatrix: FloatArray, r: Float = 0f, g: Float = 1f, b: Float = 0f) {
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMVPLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniform4f(uColorLoc, r, g, b, 1f)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, vertexBuffer)
        GLES30.glLineWidth(3f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, VERTEX_COUNT)
        GLES30.glDisableVertexAttribArray(0)
    }

    fun release() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }
}
