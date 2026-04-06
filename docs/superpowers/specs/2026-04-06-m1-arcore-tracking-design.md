# M1 Design: ARCore Tracking

## Context

SpatialX v1 needs stable 6DoF tracking before building spatial UI (panels, placement). M0 established the camera + OpenGL ES rendering foundation on Samsung Galaxy S25 Ultra. M1 replaces the standalone camera path with Google ARCore, which provides hardware-accelerated visual-inertial odometry, plane detection, and anchors through a single Gradle dependency.

**Decision:** Use ARCore SDK (`com.google.ar:core:1.53.0`) instead of ORB-SLAM3. ARCore was chosen for fastest reliable path to stable tracking: no native cross-compilation, 60fps hardware VIO, built-in plane detection, and full S25 Ultra support. This also eliminates the need for a separate depth model (Depth Anything V2) and custom plane extraction — ARCore provides planes natively, collapsing old M3 into M1.

**Trade-off:** ARCore takes over camera management. `PhoneCameraSource` is retired. `VideoFileSource` remains for non-tracking video replay.

---

## Architecture

### Two runtime modes

1. **AR mode** (default): ARCore Session owns the camera. Provides 6DoF pose, planes, and camera texture. GLRenderer renders camera background + cube test overlay + HUD.

2. **Video mode** (via intent extra): VideoFileSource renders a pre-recorded video through the existing SurfaceTexture path. No tracking, no pose. Selected via:
   ```
   adb shell am start -n com.spatialx/.MainActivity --es video_path "/sdcard/Download/test.mp4"
   ```

### Core new module: `ar/`

- **`ARSessionManager`** — Owns the ARCore `Session` lifecycle. Thin wrapper that isolates ARCore API from the rest of the app. Handles:
  - Session creation after ARCore availability check (`ArCoreApk.requestInstall()`)
  - Configuration: `PlaneFindingMode.HORIZONTAL_AND_VERTICAL`, depth mode automatic if supported
  - Camera texture binding via `session.setCameraTextureName(textureId)`
  - `session.resume()` / `session.pause()` lifecycle
  - Error handling for `UnavailableException` family and `CameraNotAvailableException`

### Per-frame flow

In `GLRenderer.onDrawFrame()`:

```
session.update() -> Frame
  -> frame.camera.trackingState       (TRACKING / PAUSED / STOPPED)
  -> frame.camera.getViewMatrix()     (for rendering)
  -> frame.camera.getProjectionMatrix() (for rendering)
  -> frame.camera.displayOrientedPose (for HUD/logging)
  -> session.getAllTrackables(Plane::class) (log count, not used for placement yet)
```

### Error handling

| Error | Response |
|-------|----------|
| `CameraNotAvailableException` from `session.update()` | Log error, show "Camera unavailable" in HUD, render black |
| `UnavailableException` during session creation | Show error message, fall back to video mode or exit |
| `TrackingState.PAUSED` | Keep rendering camera feed, hide cube, show "Tracking lost" in HUD |
| `TrackingState.STOPPED` | Keep rendering camera feed, hide cube, show "Tracking stopped" in HUD |

---

## Cube Test

A wireframe cube proves world-locking is working.

- **Geometry:** 1m edges, 12 lines rendered as `GL_LINES`, solid green color
- **Placement:** On first frame where `trackingState == TRACKING`, place the cube 2m in front of camera along its forward direction. Stays at that world position permanently.
- **Shaders:** `cube_vert.glsl` (MVP matrix uniform), `cube_frag.glsl` (solid color output)
- **Rendering order:** Camera background first (fullscreen quad), then cube wireframe on top using `viewMatrix * projectionMatrix` from ARCore Camera

---

## HUD Changes

Add to `OverlayUI`:

```
SpatialX M1
Src: arcore / video_file
FPS: 30.0
Frame: 5 ms
Track: TRACKING
Planes: 3
```

- `Track:` shows `TRACKING`, `PAUSED`, or `STOPPED`
- `Planes:` shows count of detected planes (informational for M1)
- `Src:` shows `arcore` or `video_file` depending on mode

---

## File Changes

### New files

| File | Purpose |
|------|---------|
| `ar/ARSessionManager.kt` | ARCore session lifecycle, config, error handling |
| `render/CubeRenderer.kt` | Wireframe cube geometry, shader, draw call |
| `res/raw/cube_vert.glsl` | Simple MVP vertex shader |
| `res/raw/cube_frag.glsl` | Solid color fragment shader |

### Modified files

| File | Changes |
|------|---------|
| `MainActivity.kt` | Replace PhoneCameraSource with ARSessionManager, add video mode intent extra parsing |
| `GLRenderer.kt` | Call `session.update()`, render camera via ARCore texture, call CubeRenderer, pass tracking state to HUD |
| `OverlayUI.kt` | Add tracking state + plane count lines |
| `build.gradle.kts` (app) | Add `com.google.ar:core:1.53.0` dependency |
| `AndroidManifest.xml` | Add `android.hardware.camera.ar` feature, `com.google.ar.core` meta-data |

### Removed files

| File | Reason |
|------|--------|
| `camera/PhoneCameraSource.kt` | Retired — ARCore manages camera |
| `camera/FrameSourceCallback.kt` | No longer needed (was only used by PhoneCameraSource) |

### Unchanged files

| File | Why kept |
|------|---------|
| `camera/VideoFileSource.kt` | Video replay mode |
| `core/Pose.kt` | Used in M2+ |
| `core/CameraIntrinsics.kt` | Kept for future use |
| `core/FramePacket.kt` | Used by VideoFileSource path |
| `render/ShaderUtil.kt` | Reused for cube shaders |
| `util/SXLog.kt` | Reused |

### Deferred to M2+

- `WorldSnapshot.kt` — not needed until panels consume pose
- `spatial/*` — panel/anchor/placement code
- `CoordinateTransform.kt` — not needed until panels

---

## Acceptance Criteria

1. Cube remains world-locked during normal walking in a textured indoor room
2. Relocalization works after looking away and back
3. Pose updates at camera cadence (30fps+)
4. Tracking status visible in HUD (`Track: TRACKING/PAUSED/STOPPED`)
5. Planes detected in indoor scenes (count logged and shown in HUD)
6. 30+ FPS while tracking is active
7. App resumes/pauses cleanly with ARCore session lifecycle
8. Video mode works via intent extra (no tracking, just renders video)

### Gate

Do not start panel work (M2) until the cube test passes in at least two indoor scenes.

---

## Verification Plan

### On-device testing

1. Build and install: `.\gradlew.bat installDebug`
2. Launch: `adb shell am start -n com.spatialx/.MainActivity`
3. Point at a textured indoor scene, wait for ARCore to initialize
4. Confirm cube appears ~2m ahead and stays world-locked
5. Walk around the cube — it should stay fixed in space
6. Look away, look back — cube should return to correct position (relocalization)
7. Check HUD: FPS >= 30, Track: TRACKING, Planes: >0

### Logcat checks

```powershell
adb logcat -s SX_AR SX_RENDER SX_LIFECYCLE | Select-Object -First 30
```

Expected:
- `SX_AR: ARCore session created`
- `SX_AR: Tracking state: TRACKING`
- `SX_AR: Planes detected: N`
- `SX_RENDER: Cube placed at ...`

### Pause/resume test

1. Press Home, wait 3 seconds, return
2. Confirm tracking resumes, cube is still world-locked
3. Repeat 3 times

### Video mode test

```powershell
adb shell am start -n com.spatialx/.MainActivity --es video_path "/sdcard/Download/test_video.mp4"
```

Confirm video plays, HUD shows `Src: video_file`, no tracking state displayed.
