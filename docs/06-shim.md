# The ARCore shim (advanced)

Plan B for devices whose normal AR breaks: a drop-in replacement for the
ARCore SDK C library (`libarcore_sdk_c.so`) that any ARCore app loads. The
shim forwards everything to the real library and — in its marker mode —
overrides camera, plane and anchor poses with tracking of the printed
AprilTag ([docs/02-marker-tracking.md](02-marker-tracking.md)).

| Mode | What it does |
|---|---|
| **pass-through** | forwards every symbol to the real ARCore; useful to replace an old/broken SDK library shipped inside an app |
| **marker** | forwards everything, but overrides poses with AprilTag tracking |

This page covers the whole pipeline: build the shim, patch it into an APK,
configure it at runtime and debug it. File-by-file details live in
[tools/shim/README.md](../tools/shim/README.md).

## How it works

The patched APK carries two libraries under `lib/arm64-v8a/`:

```
libarcore_sdk_c.so        ← the shim (same SONAME, same exported symbols)
libarcore_sdk_c_real.so   ← the real ARCore SDK library, SONAME patched
```

* `gen_shim.py` parses the official `arcore_c_api.h`, reads the symbols the
  target app imports (`arcore_imports.txt`) and generates `shim.c` with one
  forwarder per symbol.
* `postprocess.py` adds a `dladdr()`-based lookup so the shim finds
  `libarcore_sdk_c_real.so` next to itself (a plain `dlopen` by name would
  return the shim again), and wires two internal symbols that are not in the
  public header.
* `overrides.inc` holds the ARCore overrides and includes
  `marker_tracking.inc` (detector worker, two-pose selection, filter,
  prediction); `passthrough.inc` is the empty variant. `vers.map` exports
  only `Ar*`.

The real library must have `SONAME = libarcore_sdk_c_real.so`, otherwise the
dynamic linker resolves the name back to the shim — `extract-real-lib.sh`
does the extraction and the `patchelf` step.

## Build the shim

You need the Android NDK (r25+), `patchelf` and python3; AprilTag sources
are vendored in `third_party/apriltag`. First get a real ARCore SDK library
— from the SDK AAR (`com.google.ar:core` on Google's Maven) or from any app
APK that bundles it. Then, from `tools/shim/`:

```bash
./extract-real-lib.sh /path/to/arcore-sdk.aar   # extracts + fixes SONAME
./build.sh                                      # marker shim → libarcore_sdk_c.so
```

Pass-through variant:

```bash
SHIM_INC=passthrough.inc SHIM_NO_OVERRIDES=1 OUT=libarcore_sdk_c_stock.so ./build.sh
```

`build.sh` environment: `NDK` (or `ANDROID_NDK_ROOT`), `API` (default 29),
`OUT`, `SHIM_INC`, `WITH_APRILTAG`, `DEFINES`.

## Patch it into an APK

The all-in-one Python patcher builds the shim if asked, replaces the library
pair for the selected ABI (default `arm64-v8a`), strips old signatures,
aligns, signs and installs:

```bash
python3 tools/shim/patch_apk.py app.apk --build-shim --install
```

For an already-shimmed APK it reuses the existing `libarcore_sdk_c_real.so`;
`package_apk.sh` does the same without automatic real-library extraction.
The signing key defaults to `android/debug.keystore`; a different key than
the installed app's means Android treats it as a new app and resets its
data. Pass `--ks/--key-alias` with the currently installed key if you want
an in-place update.

## What the marker shim overrides

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

The marker pipeline is built for low latency:

* detection runs on a **worker thread**; a slow frame is dropped instead of
  stalling rendering;
* the two planar PnP solutions are **both evaluated**; the one closest to
  the previous/predicted pose wins — this stops the classic AprilTag pose
  flip near edge-on views;
* `hamming`, `decision_margin`, quad area and reprojection error gate the
  candidates; one tag ID is locked while visible;
* a One Euro filter plus constant-velocity prediction keeps the view smooth
  at render rate; after `hold_ms` without a detection tracking pauses and
  the last pose is kept.

## Coordinate frames

```
AprilTag (OpenCV)          GL camera                 World (after Y-up re-base)
X right                    X right                   Y' = -Z_tag   (up from table)
Y down                     Y up                      X' =  X_tag
Z into the surface         Z backwards (into screen) Z' =  Y_tag
```

The view matrix is built as `[F·R | F·t]` with `F = diag(1,-1,-1)`, rotated
about the optical axis for the display orientation, then re-based.

## Runtime configuration

The shim reads optional files from the target app's data directory
(`/sdcard/Android/data/<package>/files/`). The most useful ones:

| File | Meaning |
|---|---|
| `tag_size` | printed black square, mm (measure your print, see [docs/02-marker-tracking.md](02-marker-tracking.md)) |
| `world_mode` | `1` = Y-up world (default), `0` = raw tag frame |
| `plane_mode` | `0` = identity plane (Y-up), `1..4` = legacy rotations |
| `dist`, `dist_off` | distortion coefficients / disable correction |
| `margin`, `hamming`, `min_area`, `max_px` | detection/candidate gates |
| `max_jump`, `max_ang` | outlier rejection against the predicted pose |
| `filter_off`, `euro` | disable / configure the One Euro filter |
| `predict_off`, `lead`, `predict_ms` | disable / tune pose prediction |
| `latency_ms` | fallback camera latency when ARCore timestamps use an unknown clock base (default 25 ms) |
| `hold_ms`, `reset_ms` | tracking hold time / filter reset time |
| `decimate`, `threads`, `refine` | AprilTag decimation, worker threads, edge refinement |
| `tag_id`, `lock_tag` | pin a tag ID / keep the current tag while visible |
| `freeze` | stop grabbing camera frames (A/B test) |
| `shim.log` | events and 2-second tracking metrics |
| `trace_on` | create this file to enable `trace.log` (off by default — it logs every ARCore call) |

`trace.log` is the main debugging tool: the last line before a hang or crash
is the call that never returned. Create an empty `trace_on`, reproduce, then
remove it.

## Known pitfalls

* **Empty lists.** Returning a fake trackable for an out-of-range index makes
  the app pass it to a real ARCore function — always return `NULL`.
* **`ArPose_create` takes 7 floats**, not a 4×4 matrix; reading 16 gives
  garbage coordinates and a downstream crash.
* **Camera roll.** The AR image is in sensor orientation; without display
  rotation the world looks rotated 90°.
* **Y-up.** Games assume `+Y` is up; the tag frame's up is `−Z`. Check
  `camPos.y > 0` in the log to confirm the re-base.
* **The real ARCore keeps running** (it supplies the camera image), so its
  VIO still costs CPU and battery. Use a pass-through build when real
  tracking works.
* **Dropped frames are normal**: the worker always consumes the newest
  frame. A persistently high `drop=` means the detector is slower than the
  camera — raise `decimate` or check that the tag is large enough.

## Debugging workflow

```bash
adb install -r app-shim.apk                          # install the patched app
adb shell 'rm -f /sdcard/Android/data/<pkg>/files/shim.log'
adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1   # launch
adb shell 'tail -40 /sdcard/Android/data/<pkg>/files/shim.log'    # after reproducing
adb shell dumpsys activity exit-info <pkg>           # why the process died
```
