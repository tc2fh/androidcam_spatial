# Milestone 0 — On-Device Verification Guide

Target device: Samsung Galaxy S25 Ultra
Build: `./gradlew installDebug` (requires Gradle wrapper — see Setup below)

---

## Setup

1. **Add Gradle wrapper** (if not present):
   ```powershell
   # From project root, with Gradle 8.11+ installed:
   gradle wrapper --gradle-version 8.11.1
   ```
   Or open the project in Android Studio, which will auto-generate the wrapper.

2. **Connect S25 Ultra** via USB with developer mode and USB debugging enabled.

3. **Build and install**:
   ```powershell
   .\gradlew.bat installDebug
   adb shell am start -n com.spatialx/.MainActivity
   ```

---

## Acceptance Criterion 1: Live Rear Camera Feed at 30+ FPS

**What to verify:**
- App launches and shows the rear camera feed fullscreen in landscape orientation.
- The HUD overlay in the top-left corner displays FPS >= 30.

**How to verify:**
1. Launch the app on the S25 Ultra.
2. Point the phone at a well-lit scene.
3. Read the "FPS:" line on the green debug HUD.
4. Confirm FPS is stable at 30+.

**Logcat check:**
```powershell
adb logcat -s SX_CAM SX_RENDER | Select-Object -First 20
```
Expected:
- `SX_CAM: Camera opened: <id>`
- `SX_CAM: Capture session started, 1920x1080`
- `SX_CAM: Intrinsics: fx=... fy=... cx=... cy=...`
- `SX_RENDER: GL surface created, texture=<id>`

---

## Acceptance Criterion 2: Pause/Resume Without Leaks or Deadlocks

**What to verify:**
- Pressing Home and returning to the app restarts the camera cleanly.
- No camera errors, ANRs, or black screen after resume.
- No `ImageReader` or camera resource leaks.

**How to verify:**
1. Launch the app, confirm camera feed is showing.
2. Press the Home button.
3. Wait 3 seconds.
4. Return to the app via recent apps.
5. Confirm the camera feed resumes and FPS returns to 30+.
6. Repeat 3–5 times rapidly.

**Logcat check:**
```powershell
adb logcat -s SX_LIFECYCLE SX_CAM
```
Expected sequence on each cycle:
```
SX_LIFECYCLE: onPause
SX_CAM: Camera stopped
SX_LIFECYCLE: onResume
SX_CAM: Camera opened: <id>
SX_CAM: Capture session started, 1920x1080
```

No lines like:
- `Camera error:`
- `CameraDevice was already closed`
- `Surface was abandoned`

**Leak check:**
```powershell
# After several pause/resume cycles:
adb shell dumpsys media.camera | Select-String -Pattern "open cameras"
```
Should show 0 or 1 open camera (only while app is in foreground).

---

## Acceptance Criterion 3: Video File Playback with Native Timing

**What to verify:**
- A test .mp4 file plays back through the same rendering pipeline.
- Playback respects native frame timing (not fast-forwarded).
- Looping works without errors.

**How to verify:**

1. Push a test video to the device:
   ```powershell
   adb push test_video.mp4 /sdcard/Download/test_video.mp4
   ```

2. To test VideoFileSource, temporarily modify `MainActivity.kt` to use `VideoFileSource` instead of `PhoneCameraSource`:
   ```kotlin
   // In onCreate, replace cameraSource setup with:
   val videoSource = VideoFileSource()
   renderer.onSurfaceTextureAvailable = { surfaceTexture ->
       videoSource.start(surfaceTexture, "/sdcard/Download/test_video.mp4")
   }
   // Update overlayUI.sourceLabel = "video_file"
   ```

3. Build and install. Confirm:
   - Video plays at original speed (not sped up or slowed down).
   - HUD shows FPS matching the video's frame rate (~30 FPS for a 30fps video).
   - Video loops back to the beginning when it ends.

**Logcat check:**
```powershell
adb logcat -s SX_VIDEO
```
Expected:
- `SX_VIDEO: Started: <W>x<H> mime=video/avc path=...`
- `SX_VIDEO: Looping video` (when loop point is reached)

---

## Acceptance Criterion 4: HUD Shows FPS and Frame Timestamps

**What to verify:**
- A green debug overlay appears in the top-left corner.
- It displays: source label, FPS, and frame render time in milliseconds.
- Values update approximately every 500ms.

**How to verify:**
1. Launch the app.
2. Confirm the overlay shows:
   ```
   SpatialX M0
   Src: phone_camera
   FPS: 30.0
   Frame: 2 ms
   ```
3. Confirm the FPS value is reasonable (30–60) and the Frame time is reasonable (<16 ms).
4. Move the phone around — FPS should remain stable; frame time may fluctuate slightly.

---

## Quick Smoke Test Checklist

Run through this in ~2 minutes:

- [ ] App installs and launches without crash
- [ ] Camera feed visible fullscreen (landscape)
- [ ] HUD overlay visible with FPS >= 30
- [ ] HUD frame time < 16 ms
- [ ] Home → return: camera resumes cleanly
- [ ] Lock screen → unlock: camera resumes cleanly
- [ ] No logcat errors with `SX_` prefix tags
- [ ] Camera intrinsics logged on startup

---

## Known Limitations (M0)

- VideoFileSource requires a code change to switch sources (no runtime toggle yet).
- VideoFileSource intrinsics are zeroed (real calibration comes in M1 with SLAM).
- No WorldSnapshot or tracking state — those are M1.
- The HUD is a simple Android View overlay, not GL-rendered. This is intentional for M0 simplicity.

---

## Next Steps After Verification

Once all criteria pass:
1. Record a short phone demo video showing the camera feed + HUD.
2. Fill in `docs/HARDWARE_NOTES.md` with S25 Ultra observations (camera latency, thermal notes, GL capabilities).
3. Fill in `docs/PHASE_NOTES/phase0_v1.md` with lessons learned.
4. Proceed to Milestone 1 (Tracking).
