package com.spatialx.ar

import android.app.Activity
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.spatialx.util.SXLog
import com.spatialx.util.SXTags

/**
 * Manages the ARCore Session lifecycle.
 *
 * Created in MainActivity.onCreate(). Handles installation checks,
 * session creation, configuration, and resume/pause. The Session
 * itself is accessed by GLRenderer for per-frame updates.
 */
class ARSessionManager(private val activity: Activity) {

    var session: Session? = null
        private set

    private var installRequested = false

    /**
     * Attempts to create and configure the ARCore session.
     * Returns true if session is ready, false if install was requested
     * or creation failed.
     */
    fun tryCreateSession(): Boolean {
        if (session != null) return true

        try {
            val availability = ArCoreApk.getInstance().requestInstall(activity, !installRequested)
            if (availability == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                SXLog.i(SXTags.AR, "ARCore install requested")
                return false
            }
        } catch (e: UnavailableException) {
            SXLog.e(SXTags.AR, "ARCore not available: ${e.message}", e)
            return false
        }

        try {
            val newSession = Session(activity)
            val config = Config(newSession).apply {
                setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL)
                setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
                setFocusMode(Config.FocusMode.AUTO)
            }
            if (newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.setDepthMode(Config.DepthMode.AUTOMATIC)
                SXLog.i(SXTags.AR, "Depth mode: AUTOMATIC")
            }
            newSession.configure(config)
            session = newSession
            SXLog.i(SXTags.AR, "ARCore session created")
            return true
        } catch (e: UnavailableException) {
            SXLog.e(SXTags.AR, "Failed to create ARCore session: ${e.message}", e)
            return false
        }
    }

    fun resume() {
        try {
            session?.resume()
            SXLog.i(SXTags.AR, "ARCore session resumed")
        } catch (e: CameraNotAvailableException) {
            SXLog.e(SXTags.AR, "Camera not available on resume", e)
            session = null
        }
    }

    fun pause() {
        session?.pause()
        SXLog.i(SXTags.AR, "ARCore session paused")
    }

    fun destroy() {
        session?.close()
        session = null
        SXLog.i(SXTags.AR, "ARCore session destroyed")
    }
}
