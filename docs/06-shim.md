**English** | [Русский](ru/06-shim.md)

# ARCore shim

`tools/shim` builds a drop-in replacement for the ARCore SDK C library
(`libarcore_sdk_c.so`) that any ARCore app loads.  Two modes:

| Mode | What it does |
|---|---|
| **pass-through** | forwards every symbol to the real ARCore; only useful to replace an old/broken SDK library shipped inside an app |
| **marker** | forwards everything, but overrides camera/plane/anchor poses with AprilTag tracking |

## 1. How it is put together

```
app APK  lib/arm64-v8a/
├── libarcore_sdk_c.so        ← the shim (same SONAME, same exported symbols)
└── libarcore_sdk_c_real.so   ← the real ARCore SDK library, SONAME patched
```

* `gen_shim.py` parses the official `arcore_c_api.h`, reads the list of symbols
  the target app imports (`arcore_imports.txt`) and generates `shim.c` with
  one forwarder per symbol plus `real_*` function pointers.
* `postprocess.py` adds a `dladdr()`-based lookup so the shim finds
  `libarcore_sdk_c_real.so` next to itself (a plain `dlopen` by name would return
  the shim again), and wires the two internal symbols that are not in the public
  header (`ArCoreApk_getSymbolTable`, `ArSession_createWithFeaturesAndTrace`).
* `internal.S` is a trampoline for the second one.
* `vers.map` exports only `Ar*`.
* `overrides.inc` contains the hand-written ARCore overrides and includes
  `marker_tracking.inc` (detector worker, two-pose selection, filter,
  prediction); `passthrough.inc` is the empty variant.

The real library must have `SONAME = libarcore_sdk_c_real.so`, otherwise the
dynamic linker resolves the name back to the shim.  `extract-real-lib.sh` does
the extraction (from the ARCore SDK AAR or from any app that bundles it) and the
`patchelf` step.

## 2. Build

```bash
cd tools/shim
./extract-real-lib.sh ../../android/deps/arcore-1.57.0.aar
./build.sh                                   # marker shim, needs AprilTag
SHIM_INC=passthrough.inc SHIM_NO_OVERRIDES=1 OUT=libarcore_sdk_c_stock.so ./build.sh
```

`build.sh` environment: `NDK`, `API` (default 29), `OUT`, `SHIM_INC`,
`WITH_APRILTAG`, `DEFINES` (e.g. `-DTARGET_PKG=\"com.example.app\"`).

## 3. Package

```bash
# all-in-one Python patcher (recommended)
python3 tools/shim/patch_apk.py app.apk --build-shim --install

# or the shell script
SOURCE_APK=/path/to/app.apk \
REAL_LIB=libarcore_sdk_c_real.so \
OUT_APK=app-shim.apk \
./package_apk.sh
```

`patch_apk.py` removes the old shim/real library, adds the new pair for the
selected ABI (default `arm64-v8a`), strips old signatures, aligns and signs the
APK.  It can extract the real ARCore library from the source APK automatically;
for an already shimmed APK it uses `libarcore_sdk_c_real.so`.  `--install` runs
`adb install -r` on the selected device.

`package_apk.sh` does the same without automatic real-library extraction.
The signing key defaults to `android/debug.keystore`; a different key means the
app's data is reset on install.  Use `--ks/--key-alias` with the key the
currently installed app is signed with if you want an in-place update.

## 4. What the marker shim overrides

| Function | Behaviour |
|---|---|
| `ArSession_update` | real update, then queues the camera Y plane for the marker worker (detection never blocks the GL thread) |
| `ArCamera_getViewMatrix` / `ArCamera_getPose` | pose from the tag, filtered and predicted between detections |
| `ArCamera_getTrackingState` | `TRACKING` while the tag is visible, paused after `hold_ms` (350 ms) without it |
| `ArSession_getAllTrackables`, `ArTrackableList_*` | one synthetic plane when the tag is visible |
| `ArPlane_getCenterPose/getExtent/GetType` | plane pose (identity in the Y-up world), 1 m extents, horizontal-upward |
| `ArSession_acquireNewAnchor`, `ArAnchor_*` | static anchors |
| `ArPose_create/getMatrix` | **7-float** raw pose (quaternion + translation), not a matrix |
| `ArFrame_getAndroidSensorPose` | the same camera pose |
| `ArFrame_acquirePointCloud` | empty cloud |
| `ArSession_setDisplayGeometry` | remembered and used for the sensor→display rotation |

The marker pipeline is built for low latency rather than for a clean laboratory
sequence:

* detection runs on a **worker thread**; a slow frame is dropped instead of
  stalling rendering (`tools/shim/marker_tracking.inc`);
* `decimate` is adaptive (default `1 1`: full resolution for both tracking and
  search); raise it if the worker cannot keep up with the camera;
* the tag corners are undistorted with the calibrated distortion before PnP;
* the two planar PnP solutions are **both evaluated**; the one closest to the
  previous/predicted pose wins.  This is what stops the classic AprilTag
  "pose flip" when the tag is viewed almost edge-on;
* `hamming`, `decision_margin`, quad area and reprojection error gate the
  candidates; a weak tag is accepted only when tracking was already lost;
* one tag ID is locked while it is visible (`lock_tag=1`), so a second tag in
  the scene cannot steal the pose;
* One Euro filter on position/rotation plus constant-velocity prediction
  (`lead`) keeps the view smooth and responsive at render rate;
* after a short hold time (`hold_ms`, 350 ms) tracking pauses; the last pose is
  kept instead of snapping the world to the origin.

## 5. Coordinate frames

```
AprilTag (OpenCV)          GL camera                 World (after Y-up re-base)
X right                    X right                   Y' = -Z_tag   (up from table)
Y down                     Y up                      X' =  X_tag
Z into the surface         Z backwards (into screen) Z' =  Y_tag
```

The view matrix is built as `[F·R | F·t]` with `F = diag(1,-1,-1)`, then rotated
about the optical axis for the display orientation (`-90°` for rotation 0), then
re-based: `R' = R · Aᵀ`, `A = [[1,0,0],[0,0,-1],[0,1,0]]`.

## 6. Flags and logs

The shim reads optional files from the target app's data directory
(`/sdcard/Android/data/<package>/files/`):

| File | Meaning |
|---|---|
| `world_mode` | `1` = Y-up world (default), `0` = raw tag frame |
| `plane_mode` | `0` = identity plane (Y-up), `1..4` = legacy rotations |
| `freeze` | stop grabbing camera frames (A/B test) |
| `dist`, `dist_off` | distortion coefficients / disable correction |
| `tag_size` | printed black square, mm |
| `margin`, `hamming`, `min_area`, `max_px` | detection/candidate gates |
| `max_jump`, `max_ang` | outlier rejection against the predicted pose |
| `filter_off`, `euro` | disable / configure the One Euro filter (`f_min`, `beta_pos`, `beta_rot`) |
| `predict_off`, `lead`, `predict_ms` | disable / tune pose prediction and its maximum extrapolation |
| `latency_ms` | fallback camera pipeline latency when ARCore timestamps use an unknown clock base (default 25 ms) |
| `hold_ms`, `reset_ms` | how long to keep tracking without a detection / when to reset the filter |
| `decimate` | one or two numbers: tracking and search quad decimation (default `1 1`) |
| `threads`, `refine` | AprilTag worker threads and edge refinement |
| `tag_id`, `lock_tag` | pin a tag ID / keep the current tag while visible |
| `shim.log` | events and 2-second tracking metrics |
| `trace_on` | create this file to enable `trace.log`; tracing is off by default because it writes a line for every ARCore call |

`trace.log` is the main debugging tool when enabled: the last line before a hang
or crash is the call that never returned.  Create an empty `trace_on` file next
to `shim.log`, reproduce the problem, then remove it to restore performance.

## 7. Known pitfalls

* **Empty lists.** Returning a fake trackable for an out-of-range index makes the
  app pass it to a real ARCore function that dereferences it.  Always return
  `NULL` outside the list.
* **`ArPose_create` takes 7 floats**, not a 4×4 matrix.  Reading 16 gives
  coordinates like `-1.7e22` and a `std::length_error` crash downstream.
* **Camera roll.** The AR image is in sensor orientation; without the display
  rotation the whole world looks rotated 90°.
* **Y-up.** Games assume `+Y` is up; the tag frame's up is `−Z`.  Check
  `camPos.y > 0` in the log to confirm the re-base.
* **The real ARCore keeps running** (it supplies the camera image), so its VIO
  still costs CPU and battery.  A pass-through build is the way to use the real
  tracking when it works.
* **Dropped frames** are normal: the worker always consumes the newest queued
  frame.  A persistently high `drop=` in the metrics line means the detector is
  slower than the camera; raise `decimate` or lower `threads` only after checking
  that the tag is large enough.

## 8. Debugging workflow

```bash
adb install -r app-shim.apk
adb shell 'rm -f /sdcard/Android/data/<pkg>/files/shim.log'
# optional: enable per-call tracing while reproducing
adb shell 'touch /sdcard/Android/data/<pkg>/files/trace_on'
adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1
# reproduce, then:
adb shell 'tail -40 /sdcard/Android/data/<pkg>/files/shim.log'
adb shell 'tail -20 /sdcard/Android/data/<pkg>/files/trace.log'
adb shell 'rm -f /sdcard/Android/data/<pkg>/files/trace_on'
adb shell dumpsys activity exit-info <pkg>
```
