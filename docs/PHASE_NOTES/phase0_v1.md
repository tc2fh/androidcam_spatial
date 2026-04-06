# Phase 0 — Foundation Bring-Up Notes

## Status
Implementation complete. Awaiting on-device verification.

## What Was Built
- Android project scaffold (Kotlin, compileSdk 35, minSdk 28)
- `FramePacket` with atomic retain/release lifecycle
- `PhoneCameraSource` via Camera2 API (rear camera, 1080p@30fps target)
- `VideoFileSource` via MediaExtractor + MediaCodec with native timing and looping
- `GLRenderer` with SurfaceTexture-based camera background (GL_TEXTURE_EXTERNAL_OES)
- `OverlayUI` debug HUD showing FPS + frame time
- `SXLog` structured logging with SX_* tag prefixes

## Architecture Decisions
- Zero-copy GPU path: camera frames go directly to SurfaceTexture -> GL texture
- FramePacket carries metadata; GPU texture is not copied for rendering
- View-based HUD overlay (not GL text) — simpler for M0, adequate for debug info
- Landscape-only orientation to match camera FOV and future spatial UI needs

## File Layout
```
app/src/main/java/com/spatialx/
  MainActivity.kt
  core/CameraIntrinsics.kt, Pose.kt, FramePacket.kt
  camera/FrameSourceCallback.kt, PhoneCameraSource.kt, VideoFileSource.kt
  render/GLRenderer.kt, ShaderUtil.kt
  ui/OverlayUI.kt
  util/SXLog.kt
```

## Open Items
- [ ] On-device verification (see docs/M0_VERIFICATION.md)
- [ ] Record demo video
- [ ] Fill in docs/HARDWARE_NOTES.md with S25 Ultra observations
- [ ] Gradle wrapper needs to be added (via Android Studio or `gradle wrapper`)

## Risks Identified
- SurfaceTexture lifecycle on pause/resume needs thorough testing — potential for camera deadlocks if GL context is lost
- Camera2 session creation is async; rapid pause/resume could race
- VideoFileSource native timing uses Thread.sleep which has ~1ms granularity; may cause minor frame jitter on high-fps videos
