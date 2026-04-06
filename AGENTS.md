# AGENTS.md

## Mission

Build **SpatialX v1**: a phone-first native Android spatial workspace for the Samsung Galaxy S25 Ultra that supports:

- stable **world-locked panels**
- **touch-based panel interaction**
- **surface snapping to walls and tables**
- reliable operation on the phone screen without glasses

This file is the highest-priority project steering for coding agents working in this repo.

---

## Instruction Priority

Follow this order when instructions conflict:

1. direct user requests
2. this `AGENTS.md`
3. project docs such as `spatialx-v1-roadmap.md`
4. Superpowers skills and workflows
5. default agent behavior

Use Superpowers because it is installed for this project, but do not let generic workflows override the project-specific constraints below.

---

## Required Project Reading

Before making non-trivial changes, read:

1. `spatialx-v1-roadmap.md`
2. `docs/HARDWARE_NOTES.md` if it exists
3. the relevant phase notes if they exist

Do not invent scope that is outside the roadmap.

---

## Product Boundary

### In scope for v1
- ARCore-managed camera input with 6DoF tracking
- video replay input (non-tracking mode)
- OpenGL ES renderer
- ARCore plane detection (walls + floors/tables)
- free world-locked panels (using ARCore Anchors)
- plane-locked anchors (using ARCore Anchors on Planes)
- touch interaction
- performance instrumentation
- replayable validation

### Explicitly out of scope for v1
Do **not** start these unless the user explicitly asks for them:

- hand tracking
- gesture input
- object detection / object labels
- arbitrary object-attached panels
- stereo / glasses rendering on the critical path
- XREAL Eye integration on the critical path
- Vulkan migration
- advanced occlusion work
- full browser / app embedding beyond simple panel content sources

If tempted, stop and finish v1 first.

---

## Core Engineering Principles

### 1) Phone-first is sacred
The app must be buildable, testable, and demoable on the phone alone.

Do not block progress on external hardware.

### 2) Stability beats feature count
A stable cube test is more important than a new subsystem.
A stable free panel is more important than plane snapping.
A stable wall snap is more important than hand gestures.

### 3) Keep the architecture boring where possible
Prefer the simplest design that preserves future growth.

For v1:
- OpenGL ES over Vulkan
- touch over hands
- planes over generic object anchoring
- immutable snapshots over scattered atomics
- narrow interfaces over clever abstractions

### 4) Rendering must not wait on ML
The render loop is the heartbeat. Protect it.

If needed, reduce ML cadence before touching render cadence.

### 5) Coordinate conversions are the main bug source
Treat every transform as suspicious until proven correct.

Always add:
- debug visualization
- unit tests for pure math
- explicit comments on coordinate conventions

---

## Required Technical Decisions

### Tracking and planes
Use **Google ARCore SDK** (`com.google.ar:core`) for 6DoF tracking and plane detection.
ARCore manages the camera internally. `PhoneCameraSource` is retired for AR mode.

### Renderer
Use **OpenGL ES** for v1.

Do not migrate to Vulkan unless profiling proves OpenGL ES is the bottleneck and the user asks for that migration.

### Frame ownership
ARCore owns the camera frame lifecycle in AR mode.
For non-AR paths (VideoFileSource), use retained/released FramePacket.

### Shared state
Prefer an immutable `WorldSnapshot` published atomically.
Do not let render code assemble an ad hoc state from unrelated timestamps.

### Anchors
Support exactly two anchor modes in v1:
- `FREE` (ARCore Anchor at a world pose)
- `PLANE_LOCKED` (ARCore Anchor attached to a detected Plane)

Do not design a generalized scene graph before it is needed.

### Interaction
All v1 interaction is touch-based.
No hand interaction unless explicitly requested.

---

## Workflow Expectations with Superpowers

Use Superpowers skills where they help, but adapt them to this hardware-heavy project.

### Expected workflow
- use brainstorming / planning skills for feature design and milestone breakdown
- use writing-plans or equivalent before larger implementation batches
- use code review / verification skills before declaring completion

### TDD guidance for this repo
Pure TDD applies strongly to:
- coordinate transforms
- plane math
- raycasts
- panel layout math
- tracking state machines
- smoothing logic
- panel manager behavior

For hardware-facing code, use **harness-first validation**:
- build a tiny probe
- run it on device
- capture logs / screenshots / video
- then add regression tests around extracted pure logic

Do not fake TDD where the dependency is a live camera, GPU surface, or native SLAM library. Instead, isolate pure logic and test that thoroughly.

### Verification before completion
Never claim success based only on code inspection.
For user-visible milestones, provide evidence:
- log output
- screenshots
- short video
- unit test results
- performance measurements

---

## Recommended Agent Roles

When delegating work to subagents / teammates, keep scopes narrow and avoid file overlap.

### 1) AR Integration Agent
Owns:
- `ar/*`
- ARCore Session lifecycle
- WorldSnapshot publishing
- pose and plane data extraction

Does not touch:
- panel placement math
- renderer shader logic except agreed integration seams

### 2) Render Agent
Owns:
- `render/*`
- camera background rendering
- panel rendering
- debug overlay rendering

Does not redefine:
- ARCore pose conventions
- placement policy

### 3) Spatial UI Agent
Owns:
- `spatial/*`
- `interaction/*`
- anchor logic
- panel lifecycle
- placement manager
- raycast logic

Does not rewrite:
- ARCore integration
- renderer internals beyond interface needs

### 4) Validation Agent
Owns:
- unit tests
- replay scenarios (VideoFileSource)
- debug overlays for diagnosis
- performance / thermal instrumentation
- docs for reproduction steps

---

## Milestone Order

Agents must preserve this order unless the user explicitly changes it:

1. foundation bring-up (M0 — done)
2. ARCore tracking + cube test (M1)
3. one free panel + touch interaction (M2)
4. wall/table snap using ARCore planes (M3)
5. reliability hardening (M4)
6. optional external hardware spike

Do not reorder for novelty.

---

## Definition of Done by Stage

### Foundation done
- live camera renders on phone
- replay video source works
- no camera leaks on pause/resume

### Tracking done
- ARCore session initializes and tracks
- cube test passes (world-locked wireframe cube)
- relocalization works well enough for demo
- tracking state is visible and logged
- planes detected and logged in indoor scenes

### Free panel done
- panel spawns ahead of user
- panel remains world-locked
- touch move / resize / delete works

### Surface snap done
- wall snap works
- table snap works
- panel remains attached through ordinary motion
- jitter is acceptable for demo use

### Reliability done
- replay scenario passes (VideoFileSource path)
- ARCore tracking loss degrades gracefully
- plane loss disables snapping but not free panels
- logs and notes are updated

---

## Required Documentation Updates

Whenever behavior, hardware findings, or performance assumptions change, update:

- `docs/HARDWARE_NOTES.md`
- the relevant phase notes
- `spatialx-v1-roadmap.md` if the plan materially changes

Do not let implementation drift far from documentation.

---

## Performance Rules

Target budgets:

- ARCore session.update(): < 15 ms
- panel rendering: < 8 ms
- total render frame budget: < 16.6 ms

ARCore handles camera, VIO tracking, and plane detection internally. The main per-frame cost is `session.update()`.

When performance degrades:
1. profile first
2. reduce optional overlay/debug work
3. simplify panel effects
4. only consider renderer migration last

---

## Error Handling Rules

- a failing subsystem should disable itself when possible instead of crashing the app
- ARCore tracking loss is expected and must be handled as a runtime state, not a fatal exception
- plane loss must not break free panel placement
- ARCore session errors should trigger graceful fallback (camera feed without tracking)

---

## Anti-Patterns to Avoid

Do not do these:

- start with Vulkan because it feels more "serious"
- add hands before touch UX feels good
- add object snapping before plane snapping is stable
- add new abstractions for features that do not exist yet
- claim completion without on-device evidence
- optimize blindly before profiling
- let one agent edit the same files as another without clear ownership
- broaden scope because a subsystem is fun

---

## Preferred Output Style for Coding Agents

When reporting progress:

- state what changed
- state how it was verified
- state known gaps / risks
- state whether roadmap scope changed

Keep it concrete. Avoid vague claims like "implemented spatial panel system" without evidence.

---

## Final Directive

The shortest path to success is:

**camera -> ARCore tracking -> cube test -> one free panel -> touch interaction -> wall/table snap (ARCore planes) -> hardening**

If unsure what to do next, do the next unfinished item in that chain.
