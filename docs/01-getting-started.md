# Getting started

The fastest way in: download the ready-made APK from GitHub Actions, install
it, apply a device profile. No JDK, no Android SDK, no building.

## What you need

* An Android 10+ phone. Nothing on the PC — unless you want ADB later for
  calibration export or bug reports ([docs/03-troubleshooting.md](03-troubleshooting.md)).

## Step 1: Install the app from a CI artifact

Every push to the repository is built by GitHub Actions, and every build
uploads a ready APK as a downloadable artifact (kept for 30 days). This is
always the newest version — easier than building from source.

1. Open the [Actions tab](https://github.com/KotPasztet/arcore-toolkit/actions).
2. Click the latest CI run with a green check.
3. Scroll down to the **Artifacts** section and download the artifact named
   `arcore-toolkit-debug-…`.
4. Unzip the downloaded file — inside is the APK, e.g. `app-debug.apk`.
5. Copy the APK to the phone and open it there. Android asks to allow
   installing from this source — allow it. Play Protect may warn; the APK is
   built automatically from this public repository and signed with a debug
   key.
6. Install and open **ARCore Toolkit**.

Signed releases also exist (see the GitHub *Releases* page when there is one),
but the CI artifact is always up to date.

## Step 2: First launch

1. Allow the camera permission.
2. If Google Play Services for AR (ARCore) is missing or too old, the app
   asks before doing anything: it can open Google Play, or download an ARCore
   APK over HTTPS, verify Google's signature and install it. Either way is
   fine.
3. The main screen should show `availability=SUPPORTED_INSTALLED`, then
   `SESSION_CREATED_OK` once a session starts.

On an unsupported device the session will not start yet — that is expected.
A profile comes next.

## The main screen at a glance

| Button | What it does |
|---|---|
| **Profiles…** | download and apply a device profile |
| **Device info** | camera and IMU report with the computed focal length |
| **Marker AR** | track a printed AprilTag (no ARCore involved) |
| **Calibration** | record a dataset for real calibration (advanced) |
| **Print the tag (PDF)** | save a printable AprilTag PDF to Downloads |

## Step 3: Apply a device profile

A profile is a small calibration file that ARCore downloads from a *profile
server*. The app ships with a default server already configured, so:

1. Tap **Profiles…**.
2. Tap **Auto: match this camera**. The app reads your camera's focal length,
   picks the closest profile on the server and has the server rebuild it with
   your camera's numbers. This needs the server to be reachable; if it is
   not, the dialog offers **Use default server** and **Set server URL…**.
   (Tapping any profile in the list downloads it as is.)
3. ARCore restarts, and the HUD should show `Tracking: TRACKING` once the
   camera sees some texture. Tap anywhere to place a cube.

If the camera refuses to start with the chosen profile, the app detects it
and offers **Re-apply …** to restore the last working one
([docs/03-troubleshooting.md](03-troubleshooting.md)).

For your own server, custom profiles and what the numbers mean, see
[docs/04-profiles.md](04-profiles.md).

## If tracking is still bad

1. Check the focal length the HUD prints against **Device info** — if they
   differ, the profile did not apply ([docs/04-profiles.md](04-profiles.md)).
2. Good profile, unstable tracking? Calibrate the camera and IMU properly
   ([docs/05-calibration.md](05-calibration.md), advanced).
3. Device cannot track at all? Print the tag and use marker tracking
   ([docs/02-marker-tracking.md](02-marker-tracking.md)); for other apps
   there is the shim ([docs/06-shim.md](06-shim.md), advanced).
4. Anything else: [docs/03-troubleshooting.md](03-troubleshooting.md).

## Building from source (optional)

You only need this to change the app's code. Install JDK 17 (or newer) and an
Android SDK, then:

```bash
./gradlew :android:assembleDebug   # APK lands in android/build/outputs/apk/debug/
adb install -r android/build/outputs/apk/debug/app-debug.apk
```

CI builds and uploads the same APK on every push, so downloading the artifact
is almost always enough.

## Legal

Research tooling for your own devices. ARCore, the ARCore SDK and device
profile data are Google's proprietary software; they are not distributed
here.
