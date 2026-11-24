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
* `overrides.inc` contains the hand-written overrides; `passthrough.inc` is the
  empty variant.

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
SOURCE_APK=/path/to/app.apk \
REAL_LIB=libarcore_sdk_c_real.so \
OUT_APK=app-shim.apk \
./package_apk.sh
```

`package_apk.sh` removes the original `libarcore_sdk_c.so`, adds the shim and the
real library, aligns and signs.  The signing key defaults to
`android/debug.keystore`; a different key means the app's data is reset on
install.

## 4. What the marker shim overrides

| Function | Behaviour |
|---|---|
| `ArSession_update` | real update + AprilTag detection every other frame |
| `ArCamera_getViewMatrix` / `ArCamera_getPose` | pose from the tag, with prediction between detections |
| `ArCamera_getTrackingState` | `TRACKING` while the tag is visible, paused after 1 s without it |
| `ArSession_getAllTrackables`, `ArTrackableList_*` | one synthetic plane when the tag is visible |
| `ArPlane_getCenterPose/getExtent/GetType` | plane pose (identity in the Y-up world), 1 m extents, horizontal-upward |
| `ArSession_acquireNewAnchor`, `ArAnchor_*` | static anchors |
| `ArPose_create/getMatrix` | **7-float** raw pose (quaternion + translation), not a matrix |
| `ArFrame_getAndroidSensorPose` | the same camera pose |
| `ArFrame_acquirePointCloud` | empty cloud |
| `ArSession_setDisplayGeometry` | remembered and used for the sensor→display rotation |

The shim also does the small things that make tracking usable:

* undistorts the tag corners with the calibrated distortion before `solvePnP`;
* rejects detections with `hamming != 0` or a low `decision_margin`;
* gates the reprojection error (`rms_px = sqrt(err/4)·fx`, default 2 px);
* One Euro filter on position/rotation + outlier rejection;
* predicts the pose between detections (`lead` gain) so the view matrix is smooth
  at render rate.

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
| `margin`, `max_px`, `max_jump`, `max_ang` | detection gates |
| `filter_off`, `euro` | disable / configure the One Euro filter |
| `predict_off`, `lead` | disable / tune pose prediction |
| `shim.log`, `trace.log` | events and every ARCore call (with thread id) |

`trace.log` is the main debugging tool: the last line before a hang or crash is
the call that never returned.

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

## 8. Debugging workflow

```bash
adb install -r app-shim.apk
adb shell 'rm -f /sdcard/Android/data/<pkg>/files/{shim.log,trace.log}'
adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1
# reproduce, then:
adb shell 'tail -40 /sdcard/Android/data/<pkg>/files/shim.log'
adb shell 'tail -20 /sdcard/Android/data/<pkg>/files/trace.log'
adb shell dumpsys activity exit-info <pkg>
```
