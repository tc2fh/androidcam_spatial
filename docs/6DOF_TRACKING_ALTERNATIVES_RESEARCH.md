# 6DoF Visual Tracking Alternatives Research

**Date:** 2026-04-06
**Target device:** Samsung Galaxy S25 Ultra (Snapdragon 8 Elite, ARM64)
**Rendering:** Custom OpenGL ES 3.0 (no Unity)
**Constraint:** On-device only, no cloud processing

---

## Executive Summary

After researching all viable options, here is the recommendation ranking for SpatialX v1:

| Rank | Option | Verdict |
|------|--------|---------|
| 1 | **ARCore (raw Session API)** | Best path. Works on S25 Ultra, provides 6DoF + planes + depth. Custom OpenGL rendering is fully supported without Sceneform/Unity. |
| 2 | **ORB-SLAM3 on Android** | Proven Android ports exist. Heavy build effort, GPLv3 license, slow init, no plane detection. Good fallback if ARCore is rejected. |
| 3 | **Stella VSLAM** | Maintained OpenVSLAM fork with Android support. But ~2fps on Galaxy S20 — unresolved perf issue makes it non-viable without major optimization. |
| 4 | **Roll-your-own with OpenCV** | Feasible for basic VO using OpenCV 4.11 + KleidiCV ARM acceleration. No loop closure, no plane detection, significant custom work. |
| 5 | **LARVIO / SchurVINS / LEVIO** | Lightweight VIO systems designed for constrained hardware. No existing Android ports — would require cross-compilation and integration from scratch. |
| 6 | **EasyAR** | Commercial SDK with SLAM. Adds vendor dependency, monthly cost, and may conflict with custom renderer. |

**Bottom line:** ARCore via raw Session + custom OpenGL is almost certainly the right choice for v1. It is the only option that provides production-quality 6DoF + plane detection + depth on the S25 Ultra with minimal integration effort. The alternatives only make sense if you need to avoid the ARCore dependency entirely (e.g., for devices ARCore doesn't support, or to eliminate the Google Play Services requirement).

---

## 1. ORB-SLAM3 on Android

### Status: Feasible but painful

**What it is:** The gold-standard open-source visual SLAM system from the University of Zaragoza. Supports monocular, stereo, RGB-D, and visual-inertial modes. Multi-map support, loop closure, relocalization.

**Android ports exist:**
- [Abonaventure/ORB_SLAM3_AR-for-Android](https://github.com/Abonaventure/ORB_SLAM3_AR-for-Android) — builds an Android AR app with ORB-SLAM3 via JNI
- [CristianoRoman/ORB_SLAM3_AR-for-Android-master](https://github.com/CristianoRoman/ORB_SLAM3_AR-for-Android-master) — fork/variant

**Dependencies (all must cross-compile for ARM64):**
- OpenCV4Android (available via Maven or prebuilt)
- Eigen3 (header-only, straightforward)
- g2o (graph optimization — C++, needs NDK build)
- DBoW2 (bag-of-words for loop closure — C++, needs NDK build)
- Sophus (Lie group library — header-only with Eigen)
- Boost (partial, for threading)
- OpenSSL (for the Android port specifically)

**Build effort:** HIGH. The existing Android ports bundle all deps in CMakeLists.txt but require:
- Android Studio 2021.1.1.22+
- NDK cross-compilation of all native C++ dependencies
- Camera calibration file per device (PARAconfig.yaml)
- Vocabulary file (ORBvoc.bin, ~40MB) copied to device storage
- Editing CMakeLists.txt paths for your environment
- Per the [2024 build guide](https://blog.cobular.com/building-orbslam-2024/), even building on desktop Linux is a multi-hour fight with dependency versions

**Performance on mobile:**
- Slow initialization — requires rich-texture scene to boot up (UI shows "SLAM NOT INITIALIZED" for seconds)
- High CPU usage, typically 15-25fps on flagship phones in monocular mode
- Visual-inertial mode improves robustness but adds IMU integration complexity
- Memory usage: 200-400MB typical

**Plane detection:** NO. ORB-SLAM3 builds a sparse point cloud. Plane fitting would be a separate step you'd need to implement (e.g., RANSAC on the map points).

**License:** GPLv3. **This is the major issue.** Any app shipping ORB-SLAM3 must be open-sourced under GPL, or you must negotiate a commercial license with the University of Zaragoza (orbslam@unizar.es). Commercial licenses are available but at undisclosed cost.

**Maintenance:** The [official repo](https://github.com/UZ-SLAMLab/ORB_SLAM3) has not had significant updates since 2021. The Android port repos are community efforts with sporadic updates.

### Verdict
Technically proven on Android. But the build complexity is significant, the GPLv3 license is a blocker for closed-source apps, initialization is slow, and there's no plane detection. Worth considering only if ARCore is completely off the table and you're willing to either open-source or pay for a commercial license.

---

## 2. Samsung AR SDK / Samsung XR SDK

### Status: Does not exist as a phone-targeted SDK

**What Samsung offers (as of April 2026):**
- **Samsung Galaxy XR headset** (launched October 2025) — runs Android XR, Samsung's first XR device
- **Android XR SDK** — this is actually Google's SDK, not Samsung's. It targets XR headsets and smart glasses, NOT phones
- **3D Capture** — Samsung added spatial photo/video capture to Galaxy S25 Ultra via the Camera Assistant app, but this is for content capture, not real-time 6DoF tracking
- **Samsung AR Emoji / AR Doodle** — consumer features, no developer SDK

**Android XR SDK details:**
- Google's [Jetpack XR SDK](https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/anchors) provides spatial anchors, 6DoF tracking, hand tracking — but exclusively for XR headsets/glasses
- Developer Preview 3 released December 2025
- Requires Android XR OS — does NOT run on standard Android phones
- Samsung confirmed Android XR smart glasses coming in 2026

**Key finding:** There is NO Samsung-specific AR SDK for Galaxy phones. Samsung phones use ARCore for AR capabilities, same as all other Android phones. Samsung does not provide a separate/competing tracking SDK.

### Verdict
Dead end for phone-based development. Samsung relies entirely on Google's ARCore for phone AR. The Android XR SDK is headset-only and irrelevant for SpatialX v1 on a phone.

---

## 3. Lightweight Visual Odometry Libraries

### 3a. VINS-Mobile
- **Platform:** iOS only. No official Android port exists.
- **Status:** Abandoned. Last meaningful update ~2018.
- **Verdict:** Not viable for Android.

### 3b. DSO (Direct Sparse Odometry)
- **What:** Photometric (direct) visual odometry from TU Munich.
- **Android port:** None found. All development targets desktop Linux.
- **Dependencies:** Eigen, Boost, libzip, Pangolin (for viz). Would need significant porting effort.
- **License:** GPLv3
- **No IMU fusion, no loop closure** in the base system (LDSO adds loop closure).
- **Verdict:** No Android path. Academic system, not maintained for mobile.

### 3c. LSD-SLAM
- **Android port:** [omair18/LSD-SLAM-Android](https://github.com/omair18/LSD-SLAM-Android) exists but is very old (2016-era).
- **Status:** Superseded by DSO and ORB-SLAM3. Not maintained.
- **Verdict:** Obsolete. Do not use.

### 3d. LARVIO (Lightweight Accurate Robust VIO)
- **What:** EKF-based monocular visual-inertial odometry using MSCKF. [GitHub](https://github.com/PetWorm/LARVIO)
- **Key strength:** Successfully deployed on ARM (Jetson Nano, Jetson TX2) at real-time without GPU. Lightweight.
- **Dependencies:** Eigen, Boost, SuiteSparse, Ceres, OpenCV
- **Android port:** None. Would need NDK cross-compilation.
- **Features:** Online IMU-camera calibration, static/dynamic init, ORB-assisted optical flow
- **Plane detection:** No
- **License:** Not explicitly stated (check repo)
- **Verdict:** Promising for constrained devices. The ARM deployment on Jetson proves it can run on mobile-class hardware. But no ready-made Android integration — you'd be doing the porting yourself.

### 3e. SchurVINS (ByteDance, CVPR 2024)
- **What:** Filter-based VIO using Schur complement decomposition. [GitHub](https://github.com/bytedance/SchurVINS)
- **Key claim:** Outperforms state-of-the-art in both accuracy AND computational complexity on EuRoC/TUM-VI benchmarks.
- **Built by:** ByteDance (commercial pedigree)
- **Android port:** None. C++ with ROS dependencies.
- **Plane detection:** No
- **License:** Check repo (BSD-style likely given ByteDance)
- **Verdict:** Most interesting recent lightweight VIO system. But no mobile deployment path yet.

### 3f. LEVIO (ETH Zurich, 2025/2026)
- **What:** VIO pipeline for ultra-low-power platforms. [GitHub](https://github.com/ETH-PBL/levio)
- **Key achievement:** 20 FPS on RISC-V SoC consuming <100mW. Pure C implementation.
- **Target:** Micro-drones, smart glasses — extremely resource-constrained devices
- **Android port:** None, but pure C makes porting feasible
- **Plane detection:** No
- **License:** Open source (check repo for specifics)
- **Verdict:** Designed for devices FAR more constrained than a Galaxy S25 Ultra. The pure C implementation and tiny footprint are appealing, but it's designed for low-resolution sensors on drones, not phone cameras. May sacrifice accuracy for power.

### 3g. OpenVINS
- **What:** MSCKF-based visual-inertial estimator from University of Delaware. [GitHub](https://github.com/rpng/open_vins)
- **Widely used** in robotics research. Well documented.
- **Dependencies:** Eigen, Boost, OpenCV, Ceres. Heavy ROS dependency for full system.
- **Android port:** None.
- **Plane detection:** No
- **License:** GPLv3
- **Verdict:** Excellent research platform but ROS-coupled and GPLv3. Not practical for a shipping Android app.

---

## 4. MediaPipe / ML Kit

### Status: Does NOT provide 6DoF camera tracking

**What MediaPipe offers:**
- **Pose Landmarker** — detects human body landmarks (33 keypoints) in 2D and 3D. This is BODY pose, not CAMERA pose.
- **Face Mesh, Hand Tracking, Object Detection** — all about detecting things IN the image
- **No spatial/camera tracking** — MediaPipe has no module for estimating the camera's 6DoF pose in world space

**What ML Kit offers:**
- Face detection, barcode scanning, text recognition, image labeling
- **No spatial tracking whatsoever**

**Key distinction:** MediaPipe/ML Kit answer "where are objects in the image?" — NOT "where is the camera in the world?" These are fundamentally different problems.

### Verdict
Not applicable. Neither MediaPipe nor ML Kit provides camera pose estimation or spatial tracking. They solve a completely different problem.

---

## 5. OpenCV Visual Odometry on Android

### Status: Feasible for basic VO, but limited

**What's available:**
- OpenCV 4.11 with [KleidiCV ARM acceleration](https://newsroom.arm.com/blog/arm-kleidicv-opencv-integration) — up to 4x faster image processing on ARM (blur, filter, resize, rotation)
- Available via Maven Central for Android since OpenCV 4.9.0
- `cv::solvePnP` and `cv::solvePnPRansac` for pose estimation
- ORB/AKAZE feature detection and matching
- Optical flow (Lucas-Kanade) for feature tracking
- [mvo_android](https://github.com/sunzuolei/mvo_android) — reference monocular VO implementation on Android

**What you'd build yourself:**
1. Feature detection (ORB or FAST)
2. Feature tracking (KLT optical flow or descriptor matching)
3. Essential matrix estimation + decomposition for relative pose
4. PnP for absolute pose once you have a 3D map
5. Windowed bundle adjustment (would need Ceres or g2o)
6. Scale estimation (monocular scale is ambiguous without IMU)

**Performance with KleidiCV:**
- Image processing: 4x speedup on ARM with KleidiCV enabled by default in 4.11
- Feature detection/matching: fast enough for real-time on S25 Ultra
- But: full VO pipeline including optimization would still be CPU-heavy
- Reported performance: ~2fps for full SLAM pipelines using OpenCV alone (per stella_vslam Android experience)

**Critical limitations:**
- **No loop closure** — drift accumulates without it
- **No IMU fusion** — you'd need to implement VIO yourself
- **Monocular scale ambiguity** — without IMU or known landmarks, you can't determine metric scale
- **No plane detection** — would need separate RANSAC on sparse points
- **No relocalization** — if tracking is lost, you start over

**License:** Apache 2.0 (permissive, commercial-friendly)

### Verdict
OpenCV provides the building blocks but NOT a complete tracking solution. Building a production-quality 6DoF tracker from OpenCV primitives is essentially building your own SLAM system — months of work, and the result would be inferior to ORB-SLAM3 or ARCore. Only makes sense if you need a very specific, minimal tracking solution and are willing to accept significant drift.

---

## 6. Newer Open-Source SLAM Libraries (2023-2025)

### 6a. Stella VSLAM (maintained OpenVSLAM fork)
- **Repo:** [stella-cv/stella_vslam](https://github.com/stella-cv/stella_vslam)
- **Last updated:** February 2026 (actively maintained)
- **What:** Full visual SLAM with loop closure, relocalization, modular design
- **Camera support:** Perspective, fisheye, equirectangular
- **Android support:** Claimed "can be implemented in smartphones with minimal effort"
- **Reality:** [~2fps on Samsung Galaxy S20](https://github.com/stella-cv/stella_vslam/discussions/245). Performance issue is unresolved as of the last discussion update. Profiling with simpleperf showed only system call bottlenecks — unclear root cause.
- **IMU support:** Not integrated (open issue #235)
- **Plane detection:** No
- **License:** 2-clause BSD. **This is excellent for commercial use.**
- **Verdict:** The best license of any full SLAM system, but the 2fps Android performance makes it unusable for real-time AR. If the performance issue were solved, this would be the top open-source choice.

### 6b. RTAB-Map
- **What:** Appearance-based loop closure + full SLAM. Supports visual, lidar, hybrid.
- **Platforms:** Primarily ROS/desktop. Has been used on mobile robots.
- **Android app:** RTAB-Map has an Android app, but it requires depth cameras (Tango-era hardware or external depth sensors)
- **Phone camera support:** Limited — designed for RGB-D, not monocular phone cameras
- **Dependencies:** Very heavy (PCL, g2o, GTSAM, OpenCV, etc.)
- **License:** BSD
- **Verdict:** Overkill and wrong sensor target for a phone app.

### 6c. OKVIS2 / OKVIS2-X (ETH Zurich)
- **What:** State-of-the-art VI-SLAM with loop closure, dense volumetric mapping
- **OKVIS2-X (2025):** Adds dense depth, LiDAR, GNSS sensor fusion
- **Android port:** None. Research C++ codebase targeting desktop/robot platforms.
- **License:** Check repo
- **Verdict:** Cutting-edge research but no mobile deployment path.

### 6d. pySLAM (2025)
- **What:** Python-based visual SLAM framework
- **Verdict:** Python on Android is not practical for real-time SLAM. Academic tool only.

### 6e. EasyAR SDK
- **What:** Commercial AR SDK with SLAM, image tracking, 3D object tracking
- **6DoF:** Yes, includes motion tracking / SLAM
- **Android support:** Yes, claims support for 60-70% of Android devices (1B+ devices globally, 30-60% more than ARCore)
- **Custom rendering:** Unclear how well it integrates with custom OpenGL ES pipelines
- **Pricing:** Starting at $39/month. Free basic tier available.
- **Plane detection:** Not confirmed in search results
- **License:** Commercial/proprietary
- **Verdict:** A viable commercial option if ARCore doesn't work for some reason. But adds vendor dependency, ongoing cost, and potential conflicts with custom rendering. The claim of wider device support than ARCore is interesting but unverified.

---

## ARCore Reminder: Why It's Still the Best Option

Since the research question was about alternatives, here's a quick summary of why ARCore remains the strongest choice for SpatialX v1:

**ARCore with custom OpenGL ES rendering (no Sceneform, no Unity):**
- The `Session` class is the entry point — you call `session.update()` each frame to get the camera pose
- You bind the camera texture via `GLES20.glGenTextures` + `GL_TEXTURE_EXTERNAL_OES`
- You get a `Frame` with `Camera.getPose()` providing full 6DoF
- `Frame.getUpdatedTrackables(Plane.class)` gives you detected planes
- Depth API available on 87%+ of devices
- **The S25 Ultra is fully supported**
- No build complexity — it's an AAR from Maven
- No licensing issues — free for commercial use
- 60fps pose updates
- Relocalization, environmental understanding, light estimation built in

**What you DON'T need:**
- Sceneform (deprecated rendering layer — you bypass it entirely)
- Unity
- AR Foundation
- Any Google rendering code

**The raw ARCore Java API is designed for exactly your use case:** custom rendering with 6DoF pose data.

---

## Comparison Matrix

| Feature | ARCore | ORB-SLAM3 | Stella VSLAM | OpenCV VO | LARVIO | EasyAR |
|---------|--------|-----------|--------------|-----------|--------|--------|
| 6DoF pose | Yes | Yes | Yes | Partial* | Yes | Yes |
| Plane detection | Yes | No | No | No | No | Unknown |
| Depth | Yes | No | No | No | No | Unknown |
| Loop closure | Yes | Yes | Yes | No | No | Unknown |
| IMU fusion | Yes | Yes (VI mode) | No | No | Yes | Unknown |
| Android ready | Yes | Community ports | ~2fps issue | DIY | No port | Yes |
| Build complexity | Trivial (Maven) | Very High | High | Medium | High | Medium |
| License | Free commercial | GPLv3** | BSD | Apache 2.0 | Check | Commercial |
| Maintenance | Google-backed | Stale (2021) | Active | N/A | Research | Active |
| S25 Ultra tested | Yes | No | No | Partial | No | Unknown |
| FPS on mobile | 60 | 15-25 | ~2 | Varies | Unknown | Unknown |

*OpenCV VO gives relative pose only without significant additional infrastructure
**Commercial license available from Univ. of Zaragoza

---

## Recommendation for SpatialX v1

1. **Use ARCore raw Session API** with your custom OpenGL ES 3.0 renderer. This is not a compromise — it is genuinely the best technical option. The `Session.update()` -> `Camera.getPose()` pipeline gives you exactly what you need with zero build complexity.

2. **Design the FrameSource/PoseSource abstraction** so that the SLAM backend is swappable. If you later need to support non-ARCore devices, or want to experiment with ORB-SLAM3 or a custom VIO, the abstraction lets you do that without rewriting the renderer.

3. **If ARCore must be avoided:** ORB-SLAM3's Android port is the most proven alternative, but budget 1-2 weeks for build integration and resolve the GPLv3 license question early.

4. **Keep an eye on:** SchurVINS (ByteDance) and LEVIO (ETH Zurich) as future lightweight alternatives that could be ported to Android. Both represent the cutting edge of efficient VIO and could become viable mobile options in 1-2 years.

---

## Sources

- [Building ORB-SLAM3 in 2024](https://blog.cobular.com/building-orbslam-2024/)
- [ORB-SLAM3 Android port](https://github.com/Abonaventure/ORB_SLAM3_AR-for-Android)
- [ORB-SLAM3 official repo](https://github.com/UZ-SLAMLab/ORB_SLAM3)
- [ORB-SLAM3 Android issue #77](https://github.com/UZ-SLAMLab/ORB_SLAM3/issues/77)
- [Stella VSLAM](https://github.com/stella-cv/stella_vslam)
- [Stella VSLAM Android performance discussion](https://github.com/stella-cv/stella_vslam/discussions/245)
- [LARVIO](https://github.com/PetWorm/LARVIO)
- [SchurVINS (CVPR 2024)](https://github.com/bytedance/SchurVINS)
- [LEVIO](https://github.com/ETH-PBL/levio)
- [OpenVINS](https://github.com/rpng/open_vins)
- [OKVIS2-X](https://arxiv.org/abs/2510.04612)
- [KleidiCV + OpenCV 4.11 ARM acceleration](https://newsroom.arm.com/blog/arm-kleidicv-opencv-integration)
- [OpenCV Android + KleidiCV tutorial](https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/android_opencv_kleidicv/)
- [mvo_android (OpenCV monocular VO on Android)](https://github.com/sunzuolei/mvo_android)
- [Android XR SDK Developer Blog](https://android-developers.googleblog.com/2025/05/updates-to-android-xr-sdk-developer-preview.html)
- [Samsung Galaxy XR](https://www.samsung.com/us/xr/galaxy-xr/galaxy-xr/)
- [ARCore Jetpack XR anchors](https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/anchors)
- [MediaPipe Pose Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android)
- [EasyAR SLAM](https://www.easyar.com/slam.html)
- [RTAB-Map paper (2024)](https://arxiv.org/abs/2403.06341)
- [RD-VIO: Robust VIO for Mobile AR](https://arxiv.org/html/2310.15072v3)
- [VINS-Mobile](https://github.com/HKUST-Aerial-Robotics/VINS-Mobile)
- [ARCore Session API](https://developers.google.com/ar/reference/java/com/google/ar/core/Session)
- [Samsung 3D Capture for S25 Ultra](https://9to5google.com/2025/09/11/samsung-galaxy-3d-capture-spatial-photos-android-xr-headset/)
