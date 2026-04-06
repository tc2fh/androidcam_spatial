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
 * Debug HUD overlay that displays FPS, frame time, source info,
 * tracking state, and plane count.
 * Rendered as an Android View on top of the GLSurfaceView.
 * Updates at ~2 Hz to avoid layout jank.
 */
class OverlayUI(context: Context) : TextView(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    var fpsProvider: (() -> Float)? = null
    var frameTimeProvider: (() -> Long)? = null
    var trackingStateProvider: (() -> String)? = null
    var planeCountProvider: (() -> Int)? = null
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

    fun startUpdating() {
        updateRunnable = object : Runnable {
            override fun run() {
                val fps = fpsProvider?.invoke() ?: 0f
                val frameTimeMs = frameTimeProvider?.invoke() ?: 0L
                val trackState = trackingStateProvider?.invoke()
                val planes = planeCountProvider?.invoke()

                val sb = StringBuilder()
                sb.append("SpatialX M1\n")
                sb.append("Src: $sourceLabel\n")
                sb.append("FPS: %.1f\n".format(fps))
                sb.append("Frame: %d ms".format(frameTimeMs))
                if (trackState != null) {
                    sb.append("\nTrack: $trackState")
                }
                if (planes != null) {
                    sb.append("\nPlanes: $planes")
                }
                text = sb.toString()

                handler.postDelayed(this, 500)
            }
        }
        handler.post(updateRunnable!!)
    }

    fun stopUpdating() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }
}
