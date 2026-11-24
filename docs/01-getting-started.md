**English** | [Русский](ru/01-getting-started.md)

# Getting started

## 1. Requirements

| Component | Version |
|---|---|
| Python | 3.9+ |
| JDK | 11+ (tested with 21) |
| Android SDK | `platforms/android-35`, `build-tools/35.0.0` |
| Android NDK | r25+ (r27c tested) |
| Device | Android 10+ with ADB, **no root** |

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_NDK_ROOT=$ANDROID_SDK_ROOT/android-ndk-r27c
```

## 2. Install the Python tools

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -e .
pytest -q
```

## 3. ADB

USB:

```bash
adb devices -l
```

Wi-Fi (Android 11+): enable *Wireless debugging*, then

```bash
adb pair <ip>:<pair-port>     # once, with the code from the dialog
adb connect <ip>:<port>
```

mDNS discovery without knowing the port:

```bash
avahi-browse -rt _adb-tls-connect._tcp
```

Android 10 has no wireless debugging UI; use `adb tcpip 5555` over USB and then
`adb connect <ip>:5555`.

## 4. Build and install the test app

```bash
cd android
./build.sh          # deps are downloaded into android/deps/
adb install -r arcore-toolkit.apk
```

Launch it from the launcher (or with `monkey`, see the troubleshooting doc — a
sleeping screen makes the app crash on some ROMs).

## 5. Install ARCore and a profile

1. Install the official ARCore APK (Google signature).  The app's manifest asks
   for `min_apk_version=262640283` (ARCore 1.57).
2. Start the profile server (see [02-profiles.md](02-profiles.md)) and push a
   profile to the device with `ProfileDownloadActivity`.
3. Open the test app: the first screen should show `availability=SUPPORTED_INSTALLED`,
   then `SESSION_CREATED_OK`, and the HUD should show `Tracking: TRACKING` once
   the camera sees some texture.

## 6. Typical workflow

```
camera characteristics ──► intrinsics ──► profile ──► ARCore session
        (Device info)        (fx, fy)      (server)        (test app)
                                                        └─► calibration (Kalibr)
                                                        └─► shim (marker AR)
```

* Start from a profile of a phone with a similar camera (focal length is the
  most important number).
* Use **Device info / intrinsics** in the app to compute the exact `fx/fy` for
  your camera and patch the profile.
* If ARCore still tracks badly, calibrate the camera and IMU with a printed
  target ([04-calibration.md](04-calibration.md)).
* If the device cannot track at all, use marker AR with the shim
  ([05-marker-ar.md](05-marker-ar.md), [06-shim.md](06-shim.md)).

## 7. Legal

This is research tooling for your own devices.  ARCore, the ARCore SDK and
device profile data are Google's proprietary software; they are not distributed
here.  Building the shim requires extracting the ARCore SDK library from an APK
you already have (or from the public Maven artifact) — that stays on your
machine.
