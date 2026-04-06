# Milestone 0 — Foundation Bring-Up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show live phone camera on screen with a debug HUD and repeatable test input sources on Samsung Galaxy S25 Ultra.

**Architecture:** Single-activity Android app with a `GLSurfaceView` rendering camera frames via `SurfaceTexture` (zero-copy GPU path). Camera sources implement a common callback interface and produce `FramePacket` snapshots. A `View`-based overlay shows FPS/timing stats. All hardware-facing code uses harness-first validation per AGENTS.md (no fake TDD).

**Tech Stack:** Kotlin, Android SDK 35, Camera2 API, OpenGL ES 3.0, MediaExtractor/MediaCodec, `GL_TEXTURE_EXTERNAL_OES`

---

## File Structure

```
app/
  build.gradle.kts                        — App module build config
  src/main/
    AndroidManifest.xml                    — Permissions, activity declaration
    res/
      layout/activity_main.xml             — GLSurfaceView + overlay container
      values/strings.xml                   — App name
      values/themes.xml                    — Fullscreen theme
      raw/camera_vert.glsl                 — Camera background vertex shader
      raw/camera_frag.glsl                 — Camera background fragment shader (OES)
    java/com/spatialx/
      MainActivity.kt                      — Lifecycle, permissions, wiring
      core/
        CameraIntrinsics.kt                — Focal length, principal point
        Pose.kt                            — Position + rotation placeholder
        FramePacket.kt                     — Immutable frame container with retain/release
      camera/
        FrameSourceCallback.kt             — Common callback interface for frame sources
        PhoneCameraSource.kt               — Camera2 rear camera source
        VideoFileSource.kt                 — MediaCodec video file decoder
      render/
        GLRenderer.kt                      — GLSurfaceView.Renderer + SurfaceTexture mgmt
        ShaderUtil.kt                      — Shader compile/link helpers
      ui/
        OverlayUI.kt                       — FPS + timing debug HUD
      util/
        SXLog.kt                           — Structured logging with SX_* prefixes
build.gradle.kts                           — Root project build config
settings.gradle.kts                        — Project settings
gradle.properties                          — Gradle/Android properties
gradle/
  libs.versions.toml                       — Version catalog
```

---

## Task 1: Project Scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`, `themes.xml`
- Create: `app/src/main/res/layout/activity_main.xml`

- [ ] **Step 1: Create root Gradle files**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "SpatialX"
include(":app")
```

`build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

`gradle/libs.versions.toml`:
```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
coreKtx = "1.15.0"
appcompat = "1.7.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

- [ ] **Step 2: Create app build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.spatialx"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spatialx"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
}
```

- [ ] **Step 3: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="true" />
    <uses-feature android:glEsVersion="0x00030000" android:required="true" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.SpatialX">

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

- [ ] **Step 4: Create resource files**

`strings.xml`:
```xml
<resources>
    <string name="app_name">SpatialX</string>
</resources>
```

`themes.xml`:
```xml
<resources>
    <style name="Theme.SpatialX" parent="Theme.AppCompat.NoActionBar">
        <item name="android:windowFullscreen">true</item>
        <item name="android:windowKeepScreenOn">true</item>
    </style>
</resources>
```

`activity_main.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <android.opengl.GLSurfaceView
        android:id="@+id/gl_surface"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- Debug HUD overlay added programmatically -->

</FrameLayout>
```

- [ ] **Step 5: Initialize git repo and commit**

```bash
git init
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/res/
git commit -m "scaffold: Android project skeleton for SpatialX M0"
```

---

## Task 2: Structured Logging

**Files:**
- Create: `app/src/main/java/com/spatialx/util/SXLog.kt`

- [ ] **Step 1: Create SXLog utility**

```kotlin
package com.spatialx.util

import android.util.Log

/**
 * Structured logging with SX_* tag prefixes.
 * Usage: SXLog.d("CAM", "opened rear camera") → tag "SX_CAM"
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

/** Standard tag constants */
object SXTags {
    const val CAM = "CAM"
    const val RENDER = "RENDER"
    const val VIDEO = "VIDEO"
    const val LIFECYCLE = "LIFECYCLE"
    const val HUD = "HUD"
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spatialx/util/
git commit -m "feat: add structured logging with SX_* prefixes"
```

---

## Task 3: Core Data Types

**Files:**
- Create: `app/src/main/java/com/spatialx/core/CameraIntrinsics.kt`
- Create: `app/src/main/java/com/spatialx/core/Pose.kt`
- Create: `app/src/main/java/com/spatialx/core/FramePacket.kt`

- [ ] **Step 1: Create CameraIntrinsics**

```kotlin
package com.spatialx.core

data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int
)
```

- [ ] **Step 2: Create Pose**

```kotlin
package com.spatialx.core

/**
 * 6DoF pose: position (x, y, z) + rotation (quaternion x, y, z, w).
 * Coordinate convention: right-handed, Y-up, camera looks along -Z.
 * (Will be validated against SLAM output in M1.)
 */
data class Pose(
    val tx: Float = 0f,
    val ty: Float = 0f,
    val tz: Float = 0f,
    val qx: Float = 0f,
    val qy: Float = 0f,
    val qz: Float = 0f,
    val qw: Float = 1f,
    val timestampNs: Long = 0L
) {
    companion object {
        val IDENTITY = Pose()
    }
}
```

- [ ] **Step 3: Create FramePacket**

Matches roadmap spec exactly:

```kotlin
package com.spatialx.core

import java.util.concurrent.atomic.AtomicInteger

enum class ImageFormat { NV21, YUV_420_888, EXTERNAL_TEXTURE, UNKNOWN }

data class YuvPlanes(
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val yRowStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int
)

class FramePacket(
    val frameId: Long,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val format: ImageFormat,
    val gpuTextureId: Int? = null,
    val yuvPlanes: YuvPlanes? = null,
    val intrinsics: CameraIntrinsics,
    private val onRelease: (() -> Unit)? = null
) {
    private val refCount = AtomicInteger(1)

    val isValid: Boolean get() = refCount.get() > 0

    fun retain() {
        val count = refCount.incrementAndGet()
        check(count > 1) { "retain() called on already-released FramePacket $frameId" }
    }

    fun release() {
        val count = refCount.decrementAndGet()
        check(count >= 0) { "release() called too many times on FramePacket $frameId" }
        if (count == 0) {
            onRelease?.invoke()
        }
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/spatialx/core/
git commit -m "feat: add core data types — FramePacket, Pose, CameraIntrinsics"
```

---

## Task 4: Shader Utilities + Camera Background Shaders

**Files:**
- Create: `app/src/main/java/com/spatialx/render/ShaderUtil.kt`
- Create: `app/src/main/res/raw/camera_vert.glsl`
- Create: `app/src/main/res/raw/camera_frag.glsl`

- [ ] **Step 1: Create ShaderUtil**

```kotlin
package com.spatialx.render

import android.content.Context
import android.opengl.GLES30
import com.spatialx.util.SXLog
import com.spatialx.util.SXTags

object ShaderUtil {

    fun loadShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    fun readRawResource(context: Context, resourceId: Int): String {
        return context.resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
    }
}
```

- [ ] **Step 2: Create camera shaders**

`camera_vert.glsl`:
```glsl
#version 300 es
layout(location = 0) in vec4 aPosition;
layout(location = 1) in vec2 aTexCoord;
uniform mat4 uTexMatrix;
out vec2 vTexCoord;

void main() {
    gl_Position = aPosition;
    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}
```

`camera_frag.glsl`:
```glsl
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vTexCoord;
uniform samplerExternalOES uTexture;
out vec4 fragColor;

void main() {
    fragColor = texture(uTexture, vTexCoord);
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/spatialx/render/ShaderUtil.kt
git add app/src/main/res/raw/
git commit -m "feat: add shader utilities and camera background shaders"
```

---

## Task 5: GLRenderer

**Files:**
- Create: `app/src/main/java/com/spatialx/render/GLRenderer.kt`

- [ ] **Step 1: Create GLRenderer**

Key responsibilities:
- Implements `GLSurfaceView.Renderer`
- Creates and manages `SurfaceTexture` for camera input
- Renders camera background as fullscreen quad using `GL_TEXTURE_EXTERNAL_OES`
- Tracks frame timing for HUD
- Exposes `SurfaceTexture` so camera sources can target it

```kotlin
package com.spatialx.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.spatialx.R
import com.spatialx.util.SXLog
import com.spatialx.util.SXTags
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // Camera texture
    private var cameraTextureId = 0
    var surfaceTexture: SurfaceTexture? = null
        private set
    private val texMatrix = FloatArray(16)
    private var frameAvailable = false
    private val frameLock = Object()

    // Shader program
    private var program = 0
    private var uTexMatrixLoc = 0
    private var uTextureLoc = 0

    // Fullscreen quad geometry
    private lateinit var quadVertices: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer

    // Timing
    @Volatile var lastFrameTimeMs: Long = 0L; private set
    @Volatile var fps: Float = 0f; private set
    private var frameCount = 0L
    private var fpsStartTimeNs = 0L

    // Callback when SurfaceTexture is ready
    var onSurfaceTextureAvailable: ((SurfaceTexture) -> Unit)? = null

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

        // Load shaders
        val vertSrc = ShaderUtil.readRawResource(context, R.raw.camera_vert)
        val fragSrc = ShaderUtil.readRawResource(context, R.raw.camera_frag)
        program = ShaderUtil.createProgram(vertSrc, fragSrc)
        uTexMatrixLoc = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uTextureLoc = GLES30.glGetUniformLocation(program, "uTexture")

        // Fullscreen quad: two triangles
        val vertices = floatArrayOf(
            -1f, -1f,  0f,
             1f, -1f,  0f,
            -1f,  1f,  0f,
             1f,  1f,  0f
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

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        SXLog.i(SXTags.RENDER, "GL surface changed: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStartNs = System.nanoTime()

        // Update texture if new frame available
        synchronized(frameLock) {
            if (frameAvailable) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(texMatrix)
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

        // Timing
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

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        SXLog.i(SXTags.RENDER, "GLRenderer released")
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spatialx/render/GLRenderer.kt
git commit -m "feat: add GLRenderer with camera background via SurfaceTexture"
```

---

## Task 6: Frame Source Callback Interface

**Files:**
- Create: `app/src/main/java/com/spatialx/camera/FrameSourceCallback.kt`

- [ ] **Step 1: Create callback interface**

```kotlin
package com.spatialx.camera

import com.spatialx.core.FramePacket

fun interface FrameSourceCallback {
    fun onFrame(packet: FramePacket)
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spatialx/camera/FrameSourceCallback.kt
git commit -m "feat: add FrameSourceCallback interface"
```

---

## Task 7: PhoneCameraSource

**Files:**
- Create: `app/src/main/java/com/spatialx/camera/PhoneCameraSource.kt`

- [ ] **Step 1: Create PhoneCameraSource**

Uses Camera2 API. Opens rear camera, routes preview to the GLRenderer's SurfaceTexture.
Produces FramePacket on each frame for downstream consumers.
Handles start/stop lifecycle cleanly.

```kotlin
package com.spatialx.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.content.ContextCompat
import com.spatialx.core.CameraIntrinsics
import com.spatialx.core.FramePacket
import com.spatialx.core.ImageFormat
import com.spatialx.util.SXLog
import com.spatialx.util.SXTags
import java.util.concurrent.atomic.AtomicLong

class PhoneCameraSource(private val context: Context) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var surfaceTexture: SurfaceTexture? = null

    private val frameIdCounter = AtomicLong(0)
    private var intrinsics = CameraIntrinsics(0f, 0f, 0f, 0f, 0, 0)

    var frameCallback: FrameSourceCallback? = null
    var targetWidth = 1920
    var targetHeight = 1080

    @Volatile var isRunning = false; private set

    fun start(surfaceTexture: SurfaceTexture) {
        if (isRunning) return
        this.surfaceTexture = surfaceTexture

        cameraThread = HandlerThread("SX_CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findRearCamera(manager) ?: run {
            SXLog.e(SXTags.CAM, "No rear camera found")
            return
        }

        loadIntrinsics(manager, cameraId)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            SXLog.e(SXTags.CAM, "Camera permission not granted")
            return
        }

        surfaceTexture.setDefaultBufferSize(targetWidth, targetHeight)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                isRunning = true
                SXLog.i(SXTags.CAM, "Camera opened: $cameraId")
                createCaptureSession(camera, surfaceTexture)
            }
            override fun onDisconnected(camera: CameraDevice) {
                SXLog.w(SXTags.CAM, "Camera disconnected")
                stop()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                SXLog.e(SXTags.CAM, "Camera error: $error")
                stop()
            }
        }, cameraHandler)
    }

    private fun createCaptureSession(camera: CameraDevice, surfaceTexture: SurfaceTexture) {
        val surface = Surface(surfaceTexture)
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }
                    session.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: System.nanoTime()
                            val packet = FramePacket(
                                frameId = frameIdCounter.incrementAndGet(),
                                timestampNs = ts,
                                width = targetWidth,
                                height = targetHeight,
                                format = ImageFormat.EXTERNAL_TEXTURE,
                                intrinsics = intrinsics
                            )
                            frameCallback?.onFrame(packet)
                        }
                    }, cameraHandler)
                    SXLog.i(SXTags.CAM, "Capture session started, ${targetWidth}x${targetHeight}")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    SXLog.e(SXTags.CAM, "Capture session configuration failed")
                }
            },
            cameraHandler
        )
    }

    fun stop() {
        isRunning = false
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        SXLog.i(SXTags.CAM, "Camera stopped")
    }

    private fun findRearCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return null
    }

    private fun loadIntrinsics(manager: CameraManager, cameraId: String) {
        val chars = manager.getCameraCharacteristics(cameraId)
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focalLengths != null && sensorSize != null && focalLengths.isNotEmpty()) {
            val focalMm = focalLengths[0]
            val fx = focalMm * targetWidth / sensorSize.width
            val fy = focalMm * targetHeight / sensorSize.height
            intrinsics = CameraIntrinsics(fx, fy, targetWidth / 2f, targetHeight / 2f, targetWidth, targetHeight)
            SXLog.i(SXTags.CAM, "Intrinsics: fx=$fx fy=$fy cx=${targetWidth/2f} cy=${targetHeight/2f}")
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spatialx/camera/PhoneCameraSource.kt
git commit -m "feat: add PhoneCameraSource with Camera2 API"
```

---

## Task 8: VideoFileSource

**Files:**
- Create: `app/src/main/java/com/spatialx/camera/VideoFileSource.kt`

- [ ] **Step 1: Create VideoFileSource**

Uses MediaExtractor + MediaCodec to decode a .mp4 file to a SurfaceTexture with native frame timing.

```kotlin
package com.spatialx.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import com.spatialx.core.CameraIntrinsics
import com.spatialx.core.FramePacket
import com.spatialx.core.ImageFormat
import com.spatialx.util.SXLog
import com.spatialx.util.SXTags
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VideoFileSource(private val context: Context) {

    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var decodeThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val frameIdCounter = AtomicLong(0)

    var frameCallback: FrameSourceCallback? = null
    var loop = true

    @Volatile var isRunning = false; private set
    @Volatile var videoWidth = 0; private set
    @Volatile var videoHeight = 0; private set

    fun start(surfaceTexture: SurfaceTexture, videoPath: String) {
        if (running.get()) return

        val ext = MediaExtractor()
        ext.setDataSource(videoPath)

        val trackIndex = findVideoTrack(ext) ?: run {
            SXLog.e(SXTags.VIDEO, "No video track found in $videoPath")
            ext.release()
            return
        }

        ext.selectTrack(trackIndex)
        val format = ext.getTrackFormat(trackIndex)
        videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight)
        val surface = Surface(surfaceTexture)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, surface, null, 0)
        codec.start()

        extractor = ext
        decoder = codec
        running.set(true)
        isRunning = true

        SXLog.i(SXTags.VIDEO, "Started: ${videoWidth}x${videoHeight} mime=$mime path=$videoPath")

        decodeThread = Thread({ decodeLoop(ext, codec) }, "SX_VideoDecoder").also { it.start() }
    }

    private fun decodeLoop(extractor: MediaExtractor, codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var firstPtsUs = -1L
        var startTimeUs = -1L

        while (running.get()) {
            // Feed input
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)!!
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) {
                    if (loop) {
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        firstPtsUs = -1L
                        startTimeUs = -1L
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                        SXLog.d(SXTags.VIDEO, "Looping video")
                        continue
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        break
                    }
                } else {
                    val pts = extractor.sampleTime
                    codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0)
                    extractor.advance()
                }
            }

            // Drain output with native timing
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex >= 0) {
                val ptsUs = bufferInfo.presentationTimeUs
                if (firstPtsUs < 0) {
                    firstPtsUs = ptsUs
                    startTimeUs = System.nanoTime() / 1000
                }

                // Wait for native presentation time
                val targetUs = startTimeUs + (ptsUs - firstPtsUs)
                val nowUs = System.nanoTime() / 1000
                val waitUs = targetUs - nowUs
                if (waitUs > 1000) {
                    try { Thread.sleep(waitUs / 1000, ((waitUs % 1000) * 1000).toInt()) }
                    catch (_: InterruptedException) { break }
                }

                codec.releaseOutputBuffer(outputIndex, true)

                val packet = FramePacket(
                    frameId = frameIdCounter.incrementAndGet(),
                    timestampNs = ptsUs * 1000,
                    width = videoWidth,
                    height = videoHeight,
                    format = ImageFormat.EXTERNAL_TEXTURE,
                    intrinsics = CameraIntrinsics(0f, 0f, 0f, 0f, videoWidth, videoHeight)
                )
                frameCallback?.onFrame(packet)
            }
        }

        isRunning = false
        SXLog.i(SXTags.VIDEO, "Decode loop ended")
    }

    fun stop() {
        running.set(false)
        decodeThread?.interrupt()
        decodeThread?.join(2000)
        decodeThread = null

        decoder?.stop()
        decoder?.release()
        decoder = null
        extractor?.release()
        extractor = null
        isRunning = false
        SXLog.i(SXTags.VIDEO, "VideoFileSource stopped")
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) return i
        }
        return null
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spatialx/camera/VideoFileSource.kt
git commit -m "feat: add VideoFileSource with MediaCodec decoding and native timing"
```

---

## Task 9: Debug HUD Overlay

**Files:**
- Create: `app/src/main/java/com/spatialx/ui/OverlayUI.kt`

- [ ] **Step 1: Create OverlayUI**

Android View overlay that displays FPS, frame time, and source info.
Updates at ~2 Hz to avoid layout jank.

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

class OverlayUI(context: Context) : TextView(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    var fpsProvider: (() -> Float)? = null
    var frameTimeProvider: (() -> Long)? = null
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
                text = "SpatialX M0\nSrc: $sourceLabel\nFPS: %.1f\nFrame: %d ms".format(fps, frameTimeMs)
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

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spatialx/ui/OverlayUI.kt
git commit -m "feat: add FPS + timing debug HUD overlay"
```

---

## Task 10: MainActivity — Wire Everything Together

**Files:**
- Create: `app/src/main/java/com/spatialx/MainActivity.kt`

- [ ] **Step 1: Create MainActivity**

Handles permissions, creates GLSurfaceView + GLRenderer, starts PhoneCameraSource,
adds OverlayUI, manages lifecycle (pause/resume without leaks).

```kotlin
package com.spatialx

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.spatialx.camera.PhoneCameraSource
import com.spatialx.render.GLRenderer
import com.spatialx.ui.OverlayUI
import com.spatialx.util.SXLog
import com.spatialx.util.SXTags

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
        setContentView(R.layout.activity_main)
        SXLog.i(SXTags.LIFECYCLE, "onCreate")

        renderer = GLRenderer(this)
        cameraSource = PhoneCameraSource(this)

        glSurfaceView = findViewById<GLSurfaceView>(R.id.gl_surface).apply {
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

        // When GL surface is ready, start camera (after permission check)
        renderer.onSurfaceTextureAvailable = { surfaceTexture ->
            runOnUiThread {
                if (hasCameraPermission()) {
                    cameraSource.start(surfaceTexture)
                } else {
                    requestCameraPermission()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        overlayUI.startUpdating()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == CAMERA_PERMISSION_REQUEST && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spatialx/MainActivity.kt
git commit -m "feat: add MainActivity wiring camera, renderer, and HUD"
```

---

## Task 11: On-Device Verification

- [ ] **Step 1: Build and install on S25 Ultra**

```bash
./gradlew installDebug
```

- [ ] **Step 2: Verify acceptance criteria**

1. **Live rear camera feed at 30+ FPS** — check HUD reads ≥30 FPS
2. **Pause/resume without leaks** — press home, return; camera restarts cleanly; check logcat for `SX_LIFECYCLE` and `SX_CAM` messages
3. **Video file playback** — push a test .mp4, switch source to VideoFileSource, verify playback with native timing
4. **HUD shows FPS and frame timestamps** — visible green overlay with updating values

- [ ] **Step 3: Write verification document and phase notes**

- [ ] **Step 4: Final commit with all docs**

```bash
git add docs/
git commit -m "docs: add M0 verification guide and phase notes"
```
