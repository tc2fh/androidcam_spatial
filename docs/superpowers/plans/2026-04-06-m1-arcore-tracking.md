# M1: ARCore Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **IMPORTANT:** Use the context7 MCP tool (`mcp__plugin_context7_context7__resolve-library-id` then `mcp__plugin_context7_context7__query-docs`) to verify ARCore API signatures during implementation. The ARCore Java API may have changed since training data.

**Goal:** Replace PhoneCameraSource with ARCore SDK for 6DoF tracking, render a world-locked wireframe cube to prove tracking works, and show tracking state in the HUD.

**Architecture:** ARCore Session manages the camera and provides per-frame 6DoF pose, view/projection matrices, and plane detection. GLRenderer calls `session.update()` on the GL thread, renders the camera background using ARCore's texture, then renders a wireframe cube using the view/projection matrices. VideoFileSource remains as an alternative non-tracking mode via intent extra.

**Tech Stack:** ARCore SDK 1.53.0, OpenGL ES 3.0, Kotlin, Android Camera2 (managed by ARCore internally)

---

## File Structure

### New files

| File | Responsibility |
|------|---------------|
| `app/src/main/java/com/spatialx/ar/ARSessionManager.kt` | ARCore Session lifecycle: create, configure, resume, pause, error handling |
| `app/src/main/java/com/spatialx/render/CubeRenderer.kt` | Wireframe cube: geometry, shader compilation, draw call |
| `app/src/main/res/raw/cube_vert.glsl` | MVP vertex shader for 3D objects |
| `app/src/main/res/raw/cube_frag.glsl` | Solid color fragment shader |

### Modified files

| File | What changes |
|------|-------------|
| `gradle/libs.versions.toml` | Add ARCore version |
| `app/build.gradle.kts` | Add ARCore dependency |
| `app/src/main/AndroidManifest.xml` | Add AR feature + metadata |
| `app/src/main/java/com/spatialx/util/SXLog.kt` | Add `AR` tag constant |
| `app/src/main/java/com/spatialx/ui/OverlayUI.kt` | Add tracking state + plane count |
| `app/src/main/java/com/spatialx/render/GLRenderer.kt` | Dual-mode (AR/video), ARCore frame loop, cube rendering |
| `app/src/main/java/com/spatialx/MainActivity.kt` | ARSessionManager wiring, video mode intent, remove PhoneCameraSource |

### Deleted files

| File | Reason |
|------|--------|
| `app/src/main/java/com/spatialx/camera/PhoneCameraSource.kt` | Retired — ARCore manages camera |
| `app/src/main/java/com/spatialx/camera/FrameSourceCallback.kt` | Only consumer was PhoneCameraSource |

---

## Task 1: Build Configuration

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add ARCore to version catalog**

In `gradle/libs.versions.toml`, add the ARCore version and library:

```toml
[versions]
agp = "8.13.2"
kotlin = "2.0.21"
coreKtx = "1.15.0"
appcompat = "1.7.0"
arcore = "1.53.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
arcore = { group = "com.google.ar", name = "core", version.ref = "arcore" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

- [ ] **Step 2: Add ARCore dependency to app build**

In `app/build.gradle.kts`, add to dependencies block:

```kotlin
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.arcore)
}
```

- [ ] **Step 3: Update AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="true" />
    <uses-feature android:name="android.hardware.camera.ar" android:required="true" />
    <uses-feature android:glEsVersion="0x00030000" android:required="true" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.SpatialX">

        <meta-data android:name="com.google.ar.core" android:value="required" />

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 4: Verify build compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "feat(m1): add ARCore SDK 1.53.0 dependency and manifest config"
```

---

## Task 2: Add AR Tag Constant

**Files:**
- Modify: `app/src/main/java/com/spatialx/util/SXLog.kt`

- [ ] **Step 1: Add AR tag to SXTags**

In `SXLog.kt`, add to the `SXTags` object:

```kotlin
object SXTags {
    const val CAM = "CAM"
    const val RENDER = "RENDER"
    const val VIDEO = "VIDEO"
    const val LIFECYCLE = "LIFECYCLE"
    const val HUD = "HUD"
    const val AR = "AR"
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spatialx/util/SXLog.kt
git commit -m "feat(m1): add SX_AR log tag"
```

---

## Task 3: ARSessionManager

**Files:**
- Create: `app/src/main/java/com/spatialx/ar/ARSessionManager.kt`

- [ ] **Step 1: Create ARSessionManager**

Use context7 to verify ARCore `Session`, `Config`, and `ArCoreApk` API signatures before writing.

```kotlin
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

        // Check ARCore availability and request install if needed
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

        // Create and configure session
        try {
            val newSession = Session(activity)
            val config = Config(newSession).apply {
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
            }
            // Enable depth if supported
            if (newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
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
```

- [ ] **Step 2: Verify build compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/spatialx/ar/ARSessionManager.kt
git commit -m "feat(m1): add ARSessionManager for ARCore lifecycle"
```

---

## Task 4: Cube Shaders

**Files:**
- Create: `app/src/main/res/raw/cube_vert.glsl`
- Create: `app/src/main/res/raw/cube_frag.glsl`

- [ ] **Step 1: Create cube vertex shader**

`app/src/main/res/raw/cube_vert.glsl`:

```glsl
#version 300 es
layout(location = 0) in vec3 aPosition;
uniform mat4 uMVP;

void main() {
    gl_Position = uMVP * vec4(aPosition, 1.0);
}
```

- [ ] **Step 2: Create cube fragment shader**

`app/src/main/res/raw/cube_frag.glsl`:

```glsl
#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;

void main() {
    fragColor = uColor;
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/raw/cube_vert.glsl app/src/main/res/raw/cube_frag.glsl
git commit -m "feat(m1): add cube wireframe shaders"
```

---

## Task 5: CubeRenderer

**Files:**
- Create: `app/src/main/java/com/spatialx/render/CubeRenderer.kt`

- [ ] **Step 1: Create CubeRenderer**

```kotlin
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
```

- [ ] **Step 2: Verify build compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/spatialx/render/CubeRenderer.kt
git commit -m "feat(m1): add CubeRenderer for wireframe cube"
```

---

## Task 6: Update OverlayUI

**Files:**
- Modify: `app/src/main/java/com/spatialx/ui/OverlayUI.kt`

- [ ] **Step 1: Add tracking state and plane count providers**

Replace the full `OverlayUI.kt`:

```kotlin
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
```

- [ ] **Step 2: Verify build compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/spatialx/ui/OverlayUI.kt
git commit -m "feat(m1): add tracking state and plane count to HUD"
```

---

## Task 7: GLRenderer + MainActivity + Cleanup (Integration)

This is the switchover task. GLRenderer, MainActivity, and file deletions must happen together because they're interdependent.

**Files:**
- Rewrite: `app/src/main/java/com/spatialx/render/GLRenderer.kt`
- Rewrite: `app/src/main/java/com/spatialx/MainActivity.kt`
- Delete: `app/src/main/java/com/spatialx/camera/PhoneCameraSource.kt`
- Delete: `app/src/main/java/com/spatialx/camera/FrameSourceCallback.kt`

### Step 1: Rewrite GLRenderer

- [ ] **Step 1a: Replace GLRenderer.kt with dual-mode (AR/video) renderer**

Use context7 to verify: `Session.setCameraTextureName()`, `Session.update()`, `Frame.hasDisplayGeometryChanged()`, `Frame.transformDisplayUvCoords()`, `Camera.getViewMatrix()`, `Camera.getProjectionMatrix()`, `Camera.getDisplayOrientedPose()`, `TrackingState` enum values, `Plane` trackable query.

```kotlin
package com.spatialx.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
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

/**
 * OpenGL ES 3.0 renderer supporting two modes:
 * - AR mode: ARCore Session provides camera texture, 6DoF pose, and planes.
 * - Video mode: SurfaceTexture receives frames from VideoFileSource (no tracking).
 */
class GLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    enum class Mode { AR, VIDEO }

    /** Set before GLSurfaceView.setRenderer(). */
    var mode: Mode = Mode.AR

    // --- AR mode state ---
    var arSession: Session? = null
    private var hasSetTextureName = false
    private lateinit var screenTexCoords: FloatBuffer
    private lateinit var arTransformedTexCoords: FloatBuffer

    // --- Video mode state ---
    var surfaceTexture: SurfaceTexture? = null
        private set
    private val texMatrix = FloatArray(16)
    private var videoFrameAvailable = false
    private val frameLock = Object()

    /** Video mode: called when SurfaceTexture is ready. */
    var onSurfaceTextureAvailable: ((SurfaceTexture) -> Unit)? = null

    // --- Camera background (shared) ---
    private var cameraTextureId = 0
    private var bgProgram = 0
    private var bgTexMatrixLoc = 0
    private var bgTextureLoc = 0
    private lateinit var quadVertices: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // --- Cube rendering (AR mode only) ---
    private lateinit var cubeRenderer: CubeRenderer
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

    // --- Video mode aspect ratio ---
    var cameraWidth = 1920
    var cameraHeight = 1080
    var sensorRotation: Int = 0

    // ---------------------------------------------------------------
    // GLSurfaceView.Renderer
    // ---------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        // Create OES texture for camera
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        cameraTextureId = texIds[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Video mode: create SurfaceTexture from the OES texture
        if (mode == Mode.VIDEO) {
            surfaceTexture = SurfaceTexture(cameraTextureId).also { st ->
                st.setOnFrameAvailableListener {
                    synchronized(frameLock) { videoFrameAvailable = true }
                }
            }
        }

        // Compile camera background shaders (same for both modes)
        val vertSrc = ShaderUtil.readRawResource(context, R.raw.camera_vert)
        val fragSrc = ShaderUtil.readRawResource(context, R.raw.camera_frag)
        bgProgram = ShaderUtil.createProgram(vertSrc, fragSrc)
        bgTexMatrixLoc = GLES30.glGetUniformLocation(bgProgram, "uTexMatrix")
        bgTextureLoc = GLES30.glGetUniformLocation(bgProgram, "uTexture")

        // Fullscreen quad vertices (used for both modes; video mode may adjust for aspect)
        val verts = floatArrayOf(
            -1f, -1f, 0f,  1f, -1f, 0f,
            -1f,  1f, 0f,  1f,  1f, 0f
        )
        quadVertices = allocFloatBuffer(verts)

        // Default tex coords
        val texCoords = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        quadTexCoords = allocFloatBuffer(texCoords)

        // AR mode: prepare buffers for transformDisplayUvCoords
        if (mode == Mode.AR) {
            screenTexCoords = allocFloatBuffer(texCoords)
            arTransformedTexCoords = ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        }

        // Cube renderer (initialized for both modes but only drawn in AR)
        cubeRenderer = CubeRenderer(context)
        cubeRenderer.init()

        fpsStartTimeNs = System.nanoTime()
        SXLog.i(SXTags.RENDER, "GL surface created, mode=$mode, texture=$cameraTextureId")

        if (mode == Mode.VIDEO) {
            onSurfaceTextureAvailable?.invoke(surfaceTexture!!)
        }
    }

    // Display rotation for ARCore (set from Activity)
    var displayRotation: Int = 0

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)

        if (mode == Mode.AR) {
            arSession?.setDisplayGeometry(displayRotation, width, height)
        }

        SXLog.i(SXTags.RENDER, "GL surface changed: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStartNs = System.nanoTime()

        if (mode == Mode.AR) {
            drawARFrame()
        } else {
            drawVideoFrame()
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
    // AR Mode
    // ---------------------------------------------------------------

    private fun drawARFrame() {
        val session = arSession ?: return

        // Bind texture to ARCore on first frame
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

        // Update tex coords when display geometry changes (handles rotation/orientation)
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformDisplayUvCoords(screenTexCoords, arTransformedTexCoords)
        }

        // Draw camera background (fullscreen)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        GLES30.glUseProgram(bgProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glUniform1i(bgTextureLoc, 0)
        GLES30.glUniformMatrix4fv(bgTexMatrixLoc, 1, false, identityMatrix, 0)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, quadVertices)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, arTransformedTexCoords)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)

        // Draw cube if tracking
        if (camera.trackingState == TrackingState.TRACKING) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)

            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)

            // Place cube 2m ahead on first tracking frame
            if (cubeModelMatrix == null) {
                placeCubeAhead(camera.displayOrientedPose)
            }

            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, cubeModelMatrix!!, 0)
            cubeRenderer.draw(mvpMatrix)
        }

        // Count tracked planes
        planeCount = session.getAllTrackables(Plane::class.java)
            .count { it.trackingState == TrackingState.TRACKING }
    }

    private fun placeCubeAhead(cameraPose: com.google.ar.core.Pose) {
        // Camera forward is -Z in ARCore's right-handed coordinate system
        val cameraMatrix = FloatArray(16)
        cameraPose.toMatrix(cameraMatrix, 0)
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

    // ---------------------------------------------------------------
    // Video Mode (same as M0, simplified)
    // ---------------------------------------------------------------

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

            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, quadVertices)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, quadTexCoords)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun allocFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .also { it.position(0) }

    // ---------------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------------

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        cubeRenderer.release()
        if (bgProgram != 0) {
            GLES30.glDeleteProgram(bgProgram)
            bgProgram = 0
        }
        if (cameraTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
            cameraTextureId = 0
        }
        SXLog.i(SXTags.RENDER, "GLRenderer released")
    }
}
```

### Step 2: Rewrite MainActivity

- [ ] **Step 2a: Replace MainActivity.kt**

Use context7 to verify `ArCoreApk.requestInstall()` and `Session.resume()`/`Session.pause()` lifecycle requirements.

```kotlin
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

/**
 * Main activity for SpatialX Milestone 1.
 *
 * Two modes:
 * - AR mode (default): ARCore provides 6DoF tracking + camera.
 * - Video mode (via --es video_path "..."): VideoFileSource replays a video file.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: GLRenderer
    private lateinit var overlayUI: OverlayUI

    // AR mode
    private var arSessionManager: ARSessionManager? = null

    // Video mode
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

        // Check for video mode intent extra
        videoPath = intent.getStringExtra("video_path")
        SXLog.i(SXTags.LIFECYCLE, "onCreate, videoMode=$isVideoMode, videoPath=$videoPath")

        renderer = GLRenderer(this).apply {
            mode = if (isVideoMode) GLRenderer.Mode.VIDEO else GLRenderer.Mode.AR
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

        // Set up HUD
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

        // Video mode: start playback when surface texture is ready
        if (isVideoMode) {
            renderer.onSurfaceTextureAvailable = { surfaceTexture ->
                videoSource?.start(surfaceTexture, videoPath!!)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (!isVideoMode) {
            // Ensure camera permission before ARCore session
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
            SXLog.w(SXTags.AR, "ARCore session not ready (install may have been requested)")
            return
        }
        renderer.arSession = manager.session
        manager.resume()
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
            if (!isVideoMode) {
                setupAndResumeAR()
            }
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
```

### Step 3: Delete retired files

- [ ] **Step 3a: Delete PhoneCameraSource.kt and FrameSourceCallback.kt**

```bash
rm app/src/main/java/com/spatialx/camera/PhoneCameraSource.kt
rm app/src/main/java/com/spatialx/camera/FrameSourceCallback.kt
```

### Step 4: Build and commit

- [ ] **Step 4a: Verify build compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

If there are compilation errors, fix them before committing. Common issues:
- ARCore API name differences (use context7 to verify exact method signatures)
- Missing imports

- [ ] **Step 4b: Commit the integration**

```bash
git add -A
git commit -m "feat(m1): integrate ARCore tracking with dual-mode renderer

Replace PhoneCameraSource with ARCore Session for 6DoF tracking.
GLRenderer now supports AR mode (ARCore camera + pose + cube) and
video mode (VideoFileSource replay). Wireframe cube is placed 2m
ahead on first tracking frame. HUD shows tracking state and plane count.

Removed: PhoneCameraSource, FrameSourceCallback (retired)."
```

---

## Task 8: On-Device Verification

**No code changes — this is testing on the Samsung Galaxy S25 Ultra.**

- [ ] **Step 1: Build and install**

```powershell
.\gradlew.bat installDebug
```

- [ ] **Step 2: Launch AR mode**

```powershell
adb shell am start -n com.spatialx/.MainActivity
```

Verify:
- App launches without crash
- Camera feed appears
- ARCore initializes (may take a few seconds of scanning the room)
- Green wireframe cube appears ~2m ahead
- HUD shows: `Track: TRACKING`, `Planes: N` (N > 0 after scanning)
- FPS >= 30

- [ ] **Step 3: Test cube world-locking**

Walk around the room:
- Cube should stay fixed in world space
- Walk a full circle around the cube
- Look away, look back — cube should relocalize

- [ ] **Step 4: Test pause/resume**

1. Press Home, wait 3 seconds, return to app
2. Confirm camera feed resumes and cube is still visible
3. Repeat 3 times

- [ ] **Step 5: Capture logcat**

```powershell
adb logcat -s SX_AR SX_RENDER SX_LIFECYCLE | Select-Object -First 30
```

Expected:
- `SX_AR: ARCore session created`
- `SX_AR: ARCore session resumed`
- `SX_RENDER: GL surface created, mode=AR, texture=N`
- `SX_RENDER: Cube placed at (x, y, z)`

- [ ] **Step 6: Test video mode**

```powershell
adb shell am start -n com.spatialx/.MainActivity --es video_path "/sdcard/Download/test_video.mp4"
```

Verify:
- Video plays without tracking
- HUD shows `Src: video_file`, no Track/Planes lines
- No crash

- [ ] **Step 7: Test in a second indoor scene**

Move to a different room and repeat steps 2-3. The cube test must pass in at least two indoor scenes to satisfy the M1 gate.

- [ ] **Step 8: Record results**

Note any issues, FPS measurements, or observations for `docs/HARDWARE_NOTES.md`.
