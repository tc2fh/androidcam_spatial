# SpatialX v1 Roadmap

## Purpose

Build a **phone-first native Android spatial workspace** on the Samsung Galaxy S25 Ultra that can:

1. show one or more **world-locked panels**,
2. let the user **spawn, move, resize, and delete** panels with touch,
3. let the user **snap panels to walls and tables** using detected planes,
4. remain stable while the user walks, turns, and relocalizes.

This roadmap intentionally narrows scope from the original long-range plan. It keeps the same core principles—phone-first development, hardware abstraction, non-blocking rendering, video replay for testing, and explicit handling of coordinate-system risk—but cuts features that are not required for a convincing first product.

---

## v1 Product Promise

**"Create floating panels on your phone that stay fixed in space or snap to walls/tables in your environment."**

That is v1.

It is enough to prove the engine, the interaction model, and the placement model.

---

## Non-Goals for v1

Do **not** build these before the v1 demo is boringly stable:

- hand tracking and hand gestures
- generic object-attached panels
- object labeling
- stereo rendering for glasses
- XREAL Eye integration as part of the critical path
- full Vulkan renderer migration
- depth-based occlusion as a required feature
- multi-window browser/app embedding beyond a simple panel content source

These are valid v2+ features, but they must not block v1.

---

## Key Architectural Decisions

### 1) Use ARCore for tracking and plane detection
Use **Google ARCore SDK** (`com.google.ar:core`) for 6DoF tracking and plane detection.

ARCore manages the camera internally and provides:
- 6DoF pose via hardware-accelerated VIO
- horizontal and vertical plane detection
- anchors for world-locked placement
- hit testing against detected geometry

Camera sources for v1:
- ARCore Session (primary — tracking + planes)
- `VideoFileSource` (replay testing without tracking)

Core abstractions remain:
- `Pose`
- `WorldSnapshot` (published atomically each frame)
- `FramePacket` (for non-ARCore paths)

### 2) Use OpenGL ES for v1
Use **OpenGL ES only** through the full v1 demo.

Rationale:
- lower bring-up cost
- easier camera background path via `SurfaceTexture`
- enough performance for camera background + simple panels on S25 Ultra
- avoids early renderer churn

Rule: **do not migrate to Vulkan during v1 unless profiling proves OpenGL ES is the bottleneck.**

### 3) Make frame ownership explicit
Do not pass raw camera frames to asynchronous consumers without lifetime control.

Introduce a first-class frame container such as:

```kotlin
data class FramePacket(
    val frameId: Long,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val format: ImageFormat,
    val gpuTextureId: Int?,
    val yuvPlanes: YuvPlanes?,
    val intrinsics: CameraIntrinsics,
    val retain: () -> Unit,
    val release: () -> Unit
)
```

The render path may consume the GPU texture directly. Async ML consumers must either:
- retain/release the packet, or
- copy into a worker-owned buffer.

### 4) Publish a coherent world snapshot
Do not let the renderer stitch together unrelated `AtomicReference`s from different moments.

Use a versioned snapshot model:

```kotlin
data class WorldSnapshot(
    val version: Long,
    val cameraPose: Pose,
    val trackingState: TrackingState,
    val planes: List<Plane>,
    val panels: List<SpatialPanel>,
    val timestampNs: Long,
    val poseTimestampNs: Long,
    val planesTimestampNs: Long
)
```

Publish the latest immutable snapshot atomically.

### 5) Add plane-locked anchors as a first-class concept
v1 needs two anchor modes:

```kotlin
enum class AnchorMode {
    FREE,
    PLANE_LOCKED
}
```

A `PLANE_LOCKED` anchor stores:
- `planeId`
- `localPositionOnPlane`
- `offsetFromPlaneMeters`
- `alignmentMode`
- `lastValidatedTimestampNs`

This is the main architectural addition required for wall/table snapping.

### 6) Touch-first interaction only
All v1 interaction is through the phone touchscreen.

Required interactions:
- tap to select
- tap empty space to deselect
- drag to move panel
- pinch to resize panel
- button or gesture to spawn panel
- placement mode toggle: `FREE` vs `SNAP_TO_SURFACE`

No hand input in v1.

---

## v1 Success Criteria

v1 is complete when all of the following are true:

1. Camera feed renders smoothly on phone.
2. SLAM produces stable world locking in a textured indoor room.
3. A cube test passes reliably.
4. A panel can be spawned 2 m ahead and stays world-locked.
5. A panel can be moved and resized via touch.
6. A panel can be snapped to the dominant wall.
7. A panel can be snapped to a table / horizontal surface.
8. Panels remain usable after brief tracking loss and relocalization.
9. The app records repeatable demo videos and can replay test videos.
10. v1 demo runs without thermal collapse during a short session.

---

## Milestones

## Milestone 0 — Foundation Bring-Up

### Goal
Show live phone camera on screen with a debug HUD and repeatable test input sources.

### Deliverables
- Android project skeleton
- `PhoneCameraSource`
- `VideoFileSource`
- `GLRenderer`
- camera background via `SurfaceTexture`
- FPS + timing HUD
- structured logging (`SX_*`)

### Required files / modules
- `app/src/main/java/com/spatialx/core/*`
- `camera/PhoneCameraSource.kt`
- `camera/VideoFileSource.kt`
- `camera/FramePacket.kt`
- `render/GLRenderer.kt`
- `ui/OverlayUI.kt`

### Acceptance criteria
- live rear camera feed at 30+ FPS
- app resumes/pauses without leaks or camera deadlocks
- video file playback works with native timing
- HUD shows FPS and frame timestamps

### Exit artifacts
- recorded phone demo video
- first `docs/PHASE_NOTES/phase0_v1.md`
- first `docs/HARDWARE_NOTES.md` entries

---

## Milestone 1 — ARCore Tracking

### Goal
Get stable 6DoF tracking before building any spatial UI.

### Architecture change (2026-04-06)
Replaced ORB-SLAM3 with **Google ARCore SDK** (`com.google.ar:core`).

Rationale:
- Single Gradle dependency, no native cross-compilation
- Hardware-accelerated VIO on S25 Ultra at 60fps
- Built-in plane detection (walls + floors/tables) eliminates need for custom depth model
- Works offline for core tracking and planes
- S25 Ultra is a fully supported ARCore device with Depth API

Trade-off: ARCore takes over camera management. `PhoneCameraSource` is retired for AR mode. `VideoFileSource` remains for non-tracking replay.

### Scope
- ARCore Session integration with existing GLSurfaceView
- camera background rendering via ARCore's texture
- 6DoF pose extraction (`Camera.getDisplayOrientedPose()`)
- tracking state display in HUD
- cube test (wireframe cube world-locked at 2m)
- `WorldSnapshot` with pose + tracking state + planes
- ARCore lifecycle management (pause/resume/error handling)

### Required behavior
- renderer calls `session.update()` on GL thread per frame
- ARCore failure degrades gracefully (show camera feed without tracking)
- tracking state is always visible in HUD

### Acceptance criteria
- cube remains world-locked during normal walking
- relocalization works after looking away and back
- pose updates at camera cadence (30fps+)
- tracking status is visible in HUD
- 30+ FPS while tracking is active
- planes detected in indoor scenes (logged, not yet used for placement)

### Gate
**Do not start panel work until the cube test passes in at least two indoor scenes.**

---

## Milestone 2 — One Free Panel + Touch Interaction

### Goal
Prove the full spatial UI loop with one free-floating panel.

### Scope
- `Anchor` with `FREE` mode (using ARCore Anchors for stability)
- `SpatialPanel`
- `PanelRenderer`
- simple content sources
- touch selection/move/resize
- spawn / delete panel

### Initial panel content
Keep this minimal:
- clock
- system info
- text note
- solid color test panel

Optional stretch for late v1:
- `ScreenMirrorPanelContent` using `MediaProjection`

### Acceptance criteria
- spawn a panel 2 m in front of the camera
- panel stays fixed while moving around it
- user can select, move, resize, and delete it
- content redraw only happens when content changes
- 60 FPS target on simple scenes, acceptable fallback 45+ if thermally constrained

### Gate
**Do not add plane snapping until the free panel feels stable and easy to manipulate.**

---

## Milestone 3 — Snap to Wall / Table

### Goal
Allow a panel to attach to a detected surface using ARCore's built-in plane detection.

### Architecture note
ARCore provides `HORIZONTAL_AND_VERTICAL` plane finding natively. No custom depth model (Depth Anything V2), PlaneExtractor, or PlaneSmoother is needed for v1. ARCore planes provide center pose, extents, polygon boundary, and type (horizontal upward/downward, vertical).

### New systems
- `PlacementManager`
- `AnchorMode.PLANE_LOCKED` (using ARCore Anchors attached to Planes)
- `SpatialRaycast` (using ARCore `Frame.hitTest()`)
- placement reticle / surface preview

### Placement UX
Two modes:

1. **Free placement**
   - tap "Add Panel"
   - panel appears 2 m ahead facing user

2. **Snap to surface**
   - enter placement mode
   - center reticle or touch point raycasts against detected planes via `Frame.hitTest()`
   - nearest valid plane hit is previewed
   - tap to place

### Surface placement rules
- wall panels align flush to vertical plane
- table panels align parallel to horizontal plane
- default to a 1–3 cm offset from surface to avoid z-fighting
- clamp panel size to plane extents when possible
- reject clearly unstable planes (low confidence or recently subsumed)

### Acceptance criteria
- user can snap a panel to a wall
- user can snap a panel to a table/desk
- panel remains attached when user moves around
- plane refreshes do not cause visible jumping in normal use
- fallback behavior is graceful if the plane disappears briefly

### Gate
This is the v1 headline feature. When this milestone is stable, the product is demoable.

---

## Milestone 4 — Reliability, Replay, and Demo Hardening

### Goal
Make the build survive real usage.

### Scope
- thermal instrumentation
- replayable test videos (VideoFileSource for regression, without ARCore tracking)
- coordinate-debug visualizations
- crash-safe feature disabling
- state persistence for panels

### Required hardening
- every coordinate conversion gets a debug path
- every subsystem can be toggled independently
- ARCore tracking loss degrades gracefully
- plane loss disables plane snapping but not free panels

### Acceptance criteria
- 5-minute test session without app death
- usable performance after several spawn/move/snap cycles
- replay videos catch regressions in transforms or placement
- logs clearly identify timing and failure boundaries

---

## Optional Milestone 6 — Early External Device Spike

This is **not** part of the v1 critical path.

### Goal
De-risk future glasses/UVC support without polluting the core roadmap.

### Scope
- verify external display routing
- verify UVC webcam bring-up
- record notes in `docs/HARDWARE_NOTES.md`

### Rule
Do not let glasses or Eye integration delay the phone-only v1 ship.

---

## Data Model Sketch

```kotlin
enum class PlaneType { HORIZONTAL, VERTICAL, UNKNOWN }
enum class AlignmentMode { FACE_USER, FLUSH_TO_PLANE }
enum class TrackingState { NOT_INITIALIZED, OK, RECENTLY_LOST, LOST }

data class Plane(
    val id: String,
    val type: PlaneType,
    val centerWorld: Vector3,
    val normalWorld: Vector3,
    val extentsMeters: Vector2,
    val confidence: Float,
    val timestampNs: Long
)

data class Anchor(
    val id: String,
    val mode: AnchorMode,
    val worldPosition: Vector3,
    val worldRotation: Quaternion,
    val planeId: String? = null,
    val localPositionOnPlane: Vector2? = null,
    val offsetFromPlaneMeters: Float = 0.02f,
    val alignmentMode: AlignmentMode = AlignmentMode.FACE_USER,
    val confidence: Float = 1f
)

data class SpatialPanel(
    val id: String,
    val anchorId: String,
    val content: PanelContent,
    val widthMeters: Float,
    val heightMeters: Float,
    val isSelected: Boolean = false
)
```

---

## Recommended Repo Shape for v1

```text
app/src/main/java/com/spatialx/
  core/
    Pose.kt
    CameraIntrinsics.kt
    FramePacket.kt
    WorldSnapshot.kt
  ar/
    ARSessionManager.kt
    ARWorldSnapshotPublisher.kt
  camera/
    VideoFileSource.kt
  spatial/
    Anchor.kt
    SpatialPanel.kt
    PanelManager.kt
    PlacementManager.kt
    SpatialRaycast.kt
    CoordinateTransform.kt
  render/
    GLRenderer.kt
    CameraBackgroundRenderer.kt
    PanelRenderer.kt
    DebugOverlayRenderer.kt
  interaction/
    TouchHandler.kt
  ui/
    OverlayUI.kt
    SettingsActivity.kt
```

Keep `hands/`, `detection/`, and stereo-specific modules out of the initial active roadmap.

---

## Performance Targets

These are practical v1 targets, not promises of maximum hardware usage.

| Component | Target |
|---|---:|
| ARCore session.update() | < 15 ms |
| Panel rendering | < 8 ms |
| Total render frame budget | < 16.6 ms |

Note: ARCore handles camera capture, VIO tracking, and plane detection internally. The main per-frame cost visible to us is `session.update()`.

Rules:
- `session.update()` is blocking — keep other GL thread work minimal
- stale planes are acceptable; stale pose is not
- if thermal pressure rises, reduce optional rendering before touching ARCore

---

## Validation Checklist

## Tracking validation
- [ ] ARCore session initializes in a textured room
- [ ] cube test passes (world-locked wireframe cube)
- [ ] relocalization passes after look-away
- [ ] pose remains believable during slow walk and turn
- [ ] planes detected and logged in indoor scenes

## Free panel validation
- [ ] panel spawns ahead of user
- [ ] panel stays world-locked
- [ ] touch select works
- [ ] drag works
- [ ] resize works
- [ ] delete works

## Surface snap validation
- [ ] wall candidate detected
- [ ] table candidate detected
- [ ] preview reticle lines up with expected surface
- [ ] wall snap works
- [ ] table snap works
- [ ] no obvious z-fighting
- [ ] no obvious panel swimming in normal motion

## Reliability validation
- [ ] replay test video produces stable results
- [ ] subsystem toggles work
- [ ] app survives ARCore tracking loss
- [ ] plane loss disables snapping but not free panels
- [ ] no camera resource leaks

---

## Biggest Risks and How v1 Handles Them

### Coordinate-system bugs
Still the #1 risk.

Mitigation:
- isolate all transforms in `CoordinateTransform.kt`
- unit test every transform path
- render debug axes, rays, plane normals, and anchor frames

### Thermal throttling
Mitigation:
- ARCore is hardware-optimized, but sustained use still generates heat
- log thermal status continuously
- reduce optional rendering work if thermal pressure rises

### Plane jitter from ARCore
Mitigation:
- ARCore handles temporal smoothing internally
- keep a small wall offset (1-3 cm) to avoid z-fighting
- prefer stable placement over exactness
- reject recently subsumed or low-confidence planes

### Renderer churn
Mitigation:
- lock v1 to OpenGL ES
- no Vulkan migration during core milestones

### Scope creep
Mitigation:
- hands, objects, glasses, and true object snapping are explicitly out of v1 scope

---

## Post-v1 Expansion Path

Only after v1 is stable:

1. `ScreenMirrorPanelContent` via `MediaProjection`
2. glasses output and stereo
3. hand gestures
4. object detection + labels
5. object-adjacent placement
6. true object-locked panels
7. renderer migration if profiling justifies it

---

## Final Rule

**The shortest path to a compelling demo is:**

camera -> render -> ARCore tracking -> cube test -> one free panel -> touch interaction -> wall/table snap (ARCore planes) -> hardening

Everything else is optional until that path works.
