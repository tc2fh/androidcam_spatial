# Milestone 1 — On-Device Verification Guide

Target device: Samsung Galaxy S25 Ultra
Branch: `m1-arcore-tracking`
Build: `.\gradlew.bat installDebug`

---

## Setup

1. Connect S25 Ultra via USB with developer mode and USB debugging enabled.
2. Build and install:
   ```powershell
   .\gradlew.bat installDebug
   adb shell am start -n com.spatialx/.MainActivity
   ```

---

## Acceptance Criterion 1: Cube World-Locking

**What to verify:**
- App launches, ARCore initializes, and a green wireframe cube appears ~2m ahead.
- The cube stays fixed in world space while you walk around it.
- HUD shows `Track: TRACKING` and `FPS >= 30`.

**How to verify:**
1. Launch the app and point at a well-lit, textured indoor scene.
2. Wait a few seconds for ARCore to initialize (HUD should show `Track: TRACKING`).
3. The cube should appear in front of you.
4. Walk around the cube — it should stay fixed in place.
5. Walk a full circle around it.

**Logcat check:**
```powershell
adb logcat -s SX_AR SX_RENDER SX_LIFECYCLE | Select-Object -First 30
```

Expected:
- `SX_AR: ARCore session created`
- `SX_AR: ARCore session resumed`
- `SX_RENDER: GL surface created, mode=AR, texture=N`
- `SX_RENDER: Cube placed at (x.xx, y.xx, z.xx)`

---

## Acceptance Criterion 2: Relocalization

**What to verify:**
- Looking away from the cube and back returns it to the correct position.
- ARCore recovers tracking after brief loss.

**How to verify:**
1. With the cube visible, look down at the floor for 3 seconds.
2. Look back up — the cube should be in the same world position.
3. Cover the camera with your hand for 2 seconds (HUD should show `Track: PAUSED`).
4. Uncover — tracking should resume and the cube should reappear correctly.

---

## Acceptance Criterion 3: Plane Detection

**What to verify:**
- ARCore detects horizontal and vertical planes in indoor scenes.
- Plane count is visible in the HUD.

**How to verify:**
1. With the app running, slowly scan the room (move the phone side to side).
2. Watch the `Planes: N` line in the HUD — it should increase as you scan.
3. Point at a wall — vertical plane should be detected.
4. Point at a table/floor — horizontal plane should be detected.

**Logcat check:**
```powershell
adb logcat -s SX_AR | Select-String "Planes"
```

---

## Acceptance Criterion 4: Pause/Resume

**What to verify:**
- Pressing Home and returning restarts ARCore cleanly.
- The cube may relocalize to a slightly different position (ARCore map reset) — this is acceptable for M1.
- No crashes, ANRs, or camera errors.

**How to verify:**
1. Launch the app, confirm cube is visible and tracking.
2. Press the Home button.
3. Wait 3 seconds.
4. Return via recent apps.
5. Confirm camera feed resumes and HUD shows `Track: TRACKING`.
6. Repeat 3 times.

**Logcat check:**
```powershell
adb logcat -s SX_LIFECYCLE SX_AR
```

Expected sequence on each cycle:
```
SX_LIFECYCLE: onPause
SX_AR: ARCore session paused
SX_LIFECYCLE: onResume
SX_AR: ARCore session resumed
```

No lines like:
- `Camera not available`
- `ARCore not available`
- `Failed to create ARCore session`

---

## Acceptance Criterion 5: HUD Display

**What to verify:**
- The green debug overlay shows all M1 fields.

**Expected format:**
```
SpatialX M1
Src: arcore
FPS: 30.0
Frame: 5 ms
Track: TRACKING
Planes: 3
```

- FPS should be 30+ in a well-lit scene.
- Frame time should be < 16 ms.
- Track should show `TRACKING` when the scene is visible.
- Planes should be > 0 after scanning the room.

---

## Acceptance Criterion 6: Video Mode

**What to verify:**
- Launching with a video path plays the video without ARCore.
- No tracking state or plane count in the HUD.

**How to verify:**
1. Push a test video:
   ```powershell
   adb push test_video.mp4 /sdcard/Download/test_video.mp4
   ```
2. Launch in video mode:
   ```powershell
   adb shell am start -n com.spatialx/.MainActivity --es video_path "/sdcard/Download/test_video.mp4"
   ```
3. Confirm:
   - Video plays at original speed.
   - HUD shows `Src: video_file` with no `Track:` or `Planes:` lines.
   - No crash or error.

---

## Acceptance Criterion 7: Second Indoor Scene

**What to verify:**
- The cube test passes in a different room (M1 gate requirement).

**How to verify:**
1. Kill the app: `adb shell am force-stop com.spatialx`
2. Move to a different indoor room.
3. Relaunch: `adb shell am start -n com.spatialx/.MainActivity`
4. Repeat AC1 (cube appears, stays world-locked, FPS >= 30).

---

## Quick Smoke Test Checklist

Run through this in ~3 minutes:

- [ ] App installs and launches without crash
- [ ] ARCore initializes (Track: TRACKING)
- [ ] Green wireframe cube visible ~2m ahead
- [ ] Cube stays world-locked while walking
- [ ] HUD shows FPS >= 30
- [ ] HUD shows Frame < 16 ms
- [ ] HUD shows Planes > 0 after scanning
- [ ] Look away + look back: cube relocates correctly
- [ ] Home -> return: tracking resumes cleanly
- [ ] No logcat errors with `SX_` prefix tags
- [ ] Video mode launches without crash
- [ ] Cube test passes in a second room

---

## Debugging Common Issues

### App crashes on launch
- Check ARCore is installed: `adb shell pm list packages | Select-String arcore`
- If missing, install Google Play Services for AR from Play Store.
- Check logcat: `adb logcat -s SX_AR | Select-Object -First 10`

### Black screen, no camera
- Check camera permission: `adb shell dumpsys package com.spatialx | Select-String "CAMERA"`
- Grant manually: `adb shell pm grant com.spatialx android.permission.CAMERA`
- Check for `CameraNotAvailableException` in logcat.

### Cube not appearing
- Make sure the room is well-lit and has textured surfaces (not blank walls).
- Check HUD — if `Track: PAUSED`, ARCore hasn't initialized yet. Move the phone slowly.
- Check logcat for `Cube placed at` message.

### Cube drifts or jumps
- Normal for M1 — ARCore's map can adjust over time. Anchored placement comes in M2+.
- More textured environments produce more stable tracking.

### Low FPS
- Check for thermal throttling: touch the phone — if hot, let it cool.
- Close other apps.
- Check `Frame:` time in HUD — if > 16 ms, the GL thread is overloaded.

### Planes not detected
- Scan slowly, keeping the phone moving in a sweeping motion.
- ARCore needs 1-3 seconds of scanning to find initial planes.
- Good lighting and textured surfaces help.

---

## Architecture Notes for Debugging

### Key files
- `ar/ARSessionManager.kt` — Session lifecycle (create/resume/pause/destroy)
- `render/GLRenderer.kt` — Per-frame: `session.update()` -> camera background -> cube
- `render/CubeRenderer.kt` — Wireframe cube geometry and shaders
- `MainActivity.kt` — Wires ARSessionManager + GLRenderer + OverlayUI
- `ui/OverlayUI.kt` — HUD display

### Per-frame flow (AR mode)
```
onDrawFrame()
  -> session.setCameraTextureName() (first frame only)
  -> session.update() -> Frame
  -> frame.transformCoordinates2d() (when display geometry changes)
  -> draw camera background (fullscreen quad + OES texture)
  -> if TRACKING:
       camera.getViewMatrix() + getProjectionMatrix()
       placeCubeAhead() (first tracking frame only)
       cubeRenderer.draw(mvpMatrix)
  -> count tracked planes
  -> update timing (FPS, frame time)
```

### Log tags
| Tag | What it covers |
|-----|---------------|
| `SX_AR` | ARCore session lifecycle, errors |
| `SX_RENDER` | GL surface events, cube placement |
| `SX_LIFECYCLE` | Activity lifecycle (onCreate/onResume/onPause) |
| `SX_CAM` | Camera permission |
| `SX_VIDEO` | VideoFileSource events |
| `SX_HUD` | OverlayUI creation |

### Useful logcat filters
```powershell
# All SpatialX logs
adb logcat -s SX_AR SX_RENDER SX_LIFECYCLE SX_CAM SX_VIDEO SX_HUD

# Just ARCore + rendering
adb logcat -s SX_AR SX_RENDER

# Full unfiltered (last resort)
adb logcat | Select-String "SX_"
```
