**English** | [Русский](ru/08-beginner-cases.md)

# Beginner cases: print a tag, install an APK, run AR

This page is a command-by-command walkthrough for someone who has never used
ADB or built an Android app.  Run every command from the repository root unless
a step says otherwise.

You need:

- Linux/macOS PC (Windows works too; use PowerShell equivalents);
- `git`, Python 3.9+, JDK 17, Android SDK + NDK (for the app/shim);
- a phone with USB debugging or wireless ADB enabled;
- a printer, or a print shop that prints PDF at 100%.

Check the phone first:

```bash
adb devices -l
```

The phone must show `device`, not `unauthorized`.

## 1. Print the AprilTag

```bash
python3 tools/calib/make_marker.py
```

This writes:

```
tools/calib/marker_a4_150mm.pdf
tools/calib/marker_a4_150mm.png
```

Print the PDF at **100% / Actual size** (not "fit to page").  On a CUPS
printer:

```bash
lpstat -p
lp -d PRINTER_NAME -o media=A4 tools/calib/marker_a4_150mm.pdf
```

Glue the sheet to rigid cardboard.  Measure the printed 100 mm scale bar:

- about **99 mm** — leave the default; the shim expects `0.1485 m`;
- exactly **100 mm** — the black square is 150 mm; tell the shim:

```bash
adb shell 'mkdir -p /sdcard/Android/data/<package>/files'
adb shell 'echo 150 > /sdcard/Android/data/<package>/files/tag_size'
```

The tag must be matte, well lit, in focus, with its white border visible.

## 2. Install ARCore and a profile

The easiest path is Google Play:

1. install **Google Play Services for AR** normally;
2. install a profile with the test app (step 3), or use the ARCore download
   activity described in [02-profiles.md](02-profiles.md).

The test app is the recommended place to choose a profile.  Start with
`chopin_patched` or **Auto: match this camera**.

## 3. Build and install the test app

```bash
cd android
./build.sh
adb install -r arcore-toolkit.apk
```

If the screen is asleep:

```bash
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
```

Launch it:

```bash
adb shell monkey -p io.github.nikonektik.arcoretoolkit \
  -c android.intent.category.LAUNCHER 1
```

On the phone, allow the camera permission, open **Profiles...**, select a
profile, then point the camera at the printed tag.  The HUD should change from
`Tracking: PAUSED` to `Tracking: TRACKING`.

Useful checks:

```bash
adb logcat -s ARCOREGAME
adb logcat -s ARCORE_SHIM
```

## 4. Build the marker shim

```bash
cd tools/shim
./build.sh
```

The result is `tools/shim/libarcore_sdk_c.so`.  A pass-through version can be
built with:

```bash
SHIM_INC=passthrough.inc SHIM_NO_OVERRIDES=1 \
  OUT=libarcore_sdk_c_stock.so ./build.sh
```

## 5. Patch an APK with the shim

You need the **original APK** of the app.  Using the APK installed on the phone
works only if it is a single APK (not a split bundle):

```bash
APP_PACKAGE=com.example.app
adb shell pm path "$APP_PACKAGE"
# copy the base.apk path from the output, then:
adb pull /data/app/.../base.apk app.apk
```

If the app is distributed as an `.apk`/`.xapk`, use the original `.apk` file.

Patch and install:

```bash
cd /path/to/arcore-toolkit
python3 tools/shim/patch_apk.py app.apk --build-shim --install
```

This builds the shim, replaces `lib/*/libarcore_sdk_c.so`, keeps the real
library as `libarcore_sdk_c_real.so`, signs the APK, and runs `adb install -r`.

If the install fails with a signature error, the patched APK was signed with a
different key than the installed app.  Either use the original signing key:

```bash
python3 tools/shim/patch_apk.py app.apk --build-shim \
  --ks /path/to/original.keystore --key-alias ALIAS --install
```

or uninstall the app first (**this deletes its data**):

```bash
adb uninstall "$APP_PACKAGE"
adb install app-shim.apk
```

## 6. Run the patched app

```bash
adb shell monkey -p "$APP_PACKAGE" -c android.intent.category.LAUNCHER 1
```

Point the camera at the tag.  If the app shows a black screen or no tracking:

```bash
adb logcat -s ARCORE_SHIM
adb shell 'tail -30 /sdcard/Android/data/<package>/files/shim.log'
```

Look for:

```
tracking on
metrics ... det=...
```

## 7. Quick troubleshooting

| Symptom | Check |
|---|---|
| `ARCore is not available` | install Google Play Services for AR and a profile |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | different signing key; use `--ks` or uninstall |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | the APK/shim has the wrong ABI; build `arm64-v8a` |
| Tag is not detected | print scale, `tag_size`, lighting, focus, white border, distance |
| Content jitters | keep the phone steady, improve lighting; tune `euro`/`lead` |
| Content follows the camera | check `latency_ms` and `lead` in the app files directory |
| App closes silently | open the crash screen if it appears; otherwise check `adb logcat` |
| Crash report | the app saves it under `files/crash/last_crash.txt` |

More details:

- profiles and the server: [02-profiles.md](02-profiles.md)
- app screens: [03-test-app.md](03-test-app.md)
- marker/tag sizes: [05-marker-ar.md](05-marker-ar.md)
- shim internals: [06-shim.md](06-shim.md)
