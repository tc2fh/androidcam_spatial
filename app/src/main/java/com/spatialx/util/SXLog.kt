package com.spatialx.util

import android.util.Log

/**
 * Structured logging with SX_* tag prefixes.
 * Usage: SXLog.d("CAM", "opened rear camera") -> tag "SX_CAM"
 */
object SXLog {
    private const val PREFIX = "SX_"

    fun d(tag: String, msg: String) { Log.d("$PREFIX$tag", msg) }
    fun i(tag: String, msg: String) { Log.i("$PREFIX$tag", msg) }
    fun w(tag: String, msg: String) { Log.w("$PREFIX$tag", msg) }
    fun w(tag: String, msg: String, t: Throwable) { Log.w("$PREFIX$tag", msg, t) }
    fun e(tag: String, msg: String) { Log.e("$PREFIX$tag", msg) }
    fun e(tag: String, msg: String, t: Throwable) { Log.e("$PREFIX$tag", msg, t) }
}

/** Standard tag constants used across the app. */
object SXTags {
    const val CAM = "CAM"
    const val RENDER = "RENDER"
    const val VIDEO = "VIDEO"
    const val LIFECYCLE = "LIFECYCLE"
    const val HUD = "HUD"
    const val AR = "AR"
}
