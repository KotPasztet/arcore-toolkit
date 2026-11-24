# Test app

`io.github.nikonektik.arcoretoolkit` — a single APK with four screens.

## AR test (main screen)

* Full-screen `GLSurfaceView` with the ARCore camera texture as background.
* HUD: availability, session state, `Tracking`, number of planes, number of
  anchors, active profile.
* Tap places an object: plane hit-test → instant placement → fixed pose 1 m in
  front of the camera (fallback so you can test without planes).
* Controls:

| Control | What it does |
|---|---|
| profile spinner | selects a named profile on the server |
| **Apply profile & restart** | downloads `‹server›/<name>/1.57.<unix>.zip` through `ProfileDownloadActivity` and restarts the activity |
| **Custom tune...** | dialog with every profile field (intrinsics, distortion, rolling shutter, timestamps, depth, IMU scales/noise/bias) + presets + quick base test |
| **Profile server...** | sets the base URL (stored in shared preferences; empty by default) |
| **Device info / intrinsics** | see below |
| **Marker AR** | AprilTag prototype (Camera2 + OpenCV) |
| **Calibration** | camera + IMU recorder for Kalibr |

### Device info / intrinsics

Reads the Camera2 characteristics of the back camera and shows:

```
active array: 5632x4224
physical size: 5.640 x 4.230 mm
focal length: 3.620 mm
=> for 640x480: fx=410.8 fy=410.8 (HFOV 75.8 deg, VFOV 60.5 deg)
   arcore-profile patch base.pb -o out.pb --fx 410.8 --fy 410.8 --cx 320 --cy 240
```

This is the number you want in the profile: ARCore's CPU image is 640×480 and,
if the stream uses the full sensor, `fx = f_mm / sensor_width_mm × 640`.
The screen also lists the accelerometer/gyroscope sensors (vendor, rate) and the
factory `LENS_INTRINSIC_CALIBRATION`/`LENS_DISTORTION` if the vendor provides
them (most do not).

**Copy as text** copies the whole report, ready to paste into an issue.

### Custom tune parameters

The dialog is a thin UI over the server's custom endpoint:

`fx fy cx cy k0 k1 k2 rs tsa tb rss depth gsx gsy gsz gn an gbx gby gbz`
plus `base` (source profile) and `keep_extr`/`keep_imu`.

Two preset buttons apply known-good combinations; the **base** spinner applies
`base=<name>&keep_extr=1&keep_imu=1` — useful to find a phone whose camera is
close to yours before tuning.

## Marker AR prototype

A self-contained Camera2 + OpenCV activity, no ARCore involved:

* 640×480 `YUV_420_888` stream, converted to a Bitmap for display,
* ArUco `DICT_APRILTAG_36h11` detection,
* `solvePnP` with a 150 mm tag, error and distance shown on screen,
* a cube rendered on the tag.

It is the reference for the camera orientation (the sensor frame is rotated 90°
clockwise for portrait) and a quick way to check that a marker is readable
before debugging the shim.  Tag size is `0.150 × 0.99` (print scale).

## Calibration recorder

Records a Kalibr dataset:

* Camera2, mono **1280×960**, ~20 fps, JPEG frames + `frames.csv`;
* gyroscope + accelerometer at `SENSOR_DELAY_FASTEST` (~400 Hz) into `imu.csv`;
* `meta.txt` with camera characteristics (active array, physical size, focal
  lengths, timestamp source, readout timestamp).

Sessions land in `files/calib/<session>/`.  Pull them with:

```bash
adb exec-out run-as io.github.nikonektik.arcoretoolkit tar -c files/calib > calib.tar
```

## Building

`android/build.sh` does everything without Gradle:

1. downloads `com.google.ar:core:1.57.0` and `org.opencv:opencv:4.9.0` AARs into
   `android/deps/`;
2. `javac` (Java 8 target) against `android.jar` + the AARs;
3. `d8` → `classes.dex`;
4. `aapt2 link` → base APK;
5. adds `classes.dex` and the arm64-v8a native libraries;
6. `zipalign` + `apksigner` with a generated debug keystore.

Environment overrides: `ANDROID_SDK_ROOT`, `ANDROID_NDK_ROOT`, `AR_VERSION`,
`OPENCV_VERSION`, `OUT`, `KS`.

## Notes

* The app is arm64-v8a only.  Add other ABIs by copying the matching `jni`
  folders in `build.sh`.
* The UI is Russian/English mixed — the author's working language; the strings
  are plain Java, easy to localise.
