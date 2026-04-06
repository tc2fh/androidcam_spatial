package com.spatialx.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.spatialx.util.SXLog
import com.spatialx.util.SXTags

/**
 * Debug HUD overlay that displays FPS, frame time, and source info.
 * Rendered as an Android View on top of the GLSurfaceView.
 * Updates at ~2 Hz to avoid layout jank.
 */
class OverlayUI(context: Context) : TextView(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    /** Provider for current FPS value. */
    var fpsProvider: (() -> Float)? = null

    /** Provider for last frame render time in ms. */
    var frameTimeProvider: (() -> Long)? = null

    /** Label for the active input source. */
    var sourceLabel: String = "none"

    init {
        setTextColor(Color.GREEN)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setBackgroundColor(Color.argb(128, 0, 0, 0))
        setPadding(16, 8, 16, 8)
        gravity = Gravity.START

        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(16, 16, 0, 0)
        }

        SXLog.d(SXTags.HUD, "OverlayUI created")
    }

    /** Start periodic HUD updates (~2 Hz). */
    fun startUpdating() {
        updateRunnable = object : Runnable {
            override fun run() {
                val fps = fpsProvider?.invoke() ?: 0f
                val frameTimeMs = frameTimeProvider?.invoke() ?: 0L
                text = "SpatialX M0\nSrc: $sourceLabel\nFPS: %.1f\nFrame: %d ms".format(fps, frameTimeMs)
                handler.postDelayed(this, 500)
            }
        }
        handler.post(updateRunnable!!)
    }

    /** Stop periodic HUD updates. */
    fun stopUpdating() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }
}
