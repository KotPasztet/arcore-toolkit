# Troubleshooting

Most problems fall into a few groups: ADB not talking, ARCore refusing a
profile, bad tracking, or a crash. Work top to bottom.

## ADB basics

ADB is only needed for logs, calibration export and bug reports — not for
everyday use of the app. Enable USB debugging in *Developer options*.

| Symptom | Fix |
|---|---|
| `device not found` | re-plug, `adb kill-server && adb start-server`, check the "Allow USB debugging" prompt |
| device disappears after a while | USB power saving / cable; enable "Wireless debugging" and use `adb connect` |
| `unauthorized` | accept the key on the device |

```bash
adb devices -l   # the phone must show "device", not "unauthorized"
```

Wireless ADB on Android 11+: *Settings → Developer options → Wireless
debugging*, then `adb pair <ip>:<pair-port>` once and `adb connect <ip>:<port>`.

## Reading logs

```bash
adb logcat | grep -aE "ARCORE|ARCore|native|FATAL"     # everything AR-related
adb logcat --pid=$(adb shell pidof io.github.nikonektik.arcoretoolkit | tr -d '\r')   # app only
adb shell dumpsys activity exit-info <package>          # why a process died
```

`exit-info` reasons: `5` native crash, `6` ANR, `16` package updated. The
test app logs under the `ARCOREGAME`/`ARCORETEST` tags; the shim logs under
`ARCORE_SHIM` and to files in the target app's data directory
([docs/06-shim.md](06-shim.md)).

## The app crashed

The app does not die silently: it shows an **"ARCore Toolkit crashed"**
screen with a Copy button and the saved report path. Include the report file
when reporting a problem — pull it with:

```bash
adb exec-out run-as io.github.nikonektik.arcoretoolkit cat files/crash/last_crash.txt
```

A crash mentioning `Not allowed to start service Intent ... app is in
background` means the screen was off (or the app was started in the
background) when a Firebase service was triggered; Android 10 throws and the
process aborts. Wake the device and launch from the launcher:

```bash
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell monkey -p io.github.nikonektik.arcoretoolkit -c android.intent.category.LAUNCHER 1
```

## ARCore says the device is unsupported

* `UNSUPPORTED_DEVICE_NOT_CAPABLE` — no usable profile; install one
  ([docs/04-profiles.md](04-profiles.md)).
* Session creation fails with `UnavailableDeviceNotCompatibleException` — a
  profile was found but the camera configuration does not match.
* Watch the lookup live — the log lists every key ARCore tried, which is how
  you discover the sensor-qualified variants you need in the ZIP:

```bash
adb logcat | grep -aE "Device profile for|Found nothing for|Using local profile"
```

## Profile rejected by ARCore

`SESSION_CREATED_OK` followed by a `FatalException` in `resume()`: a profile
from a phone with a different camera pipeline can create a session but fail
to start the camera. The app detects this, shows **"Profile rejected by
ARCore"** and offers **Re-apply …** to restore the last working profile.

To recover manually, push a known-good profile. The first command stops
ARCore so it will accept a fresh download; the second starts its hidden
profile-download activity with your URL:

```bash
adb shell am force-stop com.google.ar.core
adb shell am start --activity-clear-task \
  -n com.google.ar.core/com.google.ar.core.services.profiles.ProfileDownloadActivity \
  --es explicit-download-url "https://HOST/arcore/switch/<known-good>/<version>.zip"
```

`ProfileDownloadActivity` reuses its task: a plain `am start` when it is
already open does **not** pass the new URL — `force-stop` and/or
`--activity-clear-task` fixes that.

## Bad tracking even with a profile

1. Check the intrinsics ARCore actually uses — the app's HUD prints
   `focal=…`. If it does not match your profile, the profile was not applied
   ([docs/04-profiles.md](04-profiles.md)).
2. Verify the focal length from the camera characteristics in
   **Device info**.
3. Look at the VIO messages:

```bash
adb logcat | grep -aE "VIO|vio|RESOURCE_EXHAUSTED|desync|pose_manager"
```

`RESOURCE_EXHAUSTED: Behind by …`, `VIO frequency low`, `Camera to IMU clock
offset` mean the timestamps or IMU calibration are wrong — calibrate
([docs/05-calibration.md](05-calibration.md)).

4. Thermal throttling: at 90 °C+ or below 10% battery the camera and VIO
   degrade (30 fps drops to ~7 fps). Charge and cool down, then retest.

## ANR (10–30 s freeze, "not responding")

`[ERROR PERF] Stalled the main thread by loading texture` in the log plus a
memory-starved phone is the usual cause. Close background apps or reboot
(`adb shell free -m` to check), then retry.

## Marker is not detected

* Whole tag in frame, white border visible?
* Reflections or gloss? Use matte paper and diffuse light.
* Too far? A 150 mm tag at 1.5 m is only ~45 px — get closer or print bigger
  ([docs/02-marker-tracking.md](02-marker-tracking.md)).
* Check `dets=` in `shim.log`, or the `ID / dist / err` line on the Marker AR
  screen.
* Printed size not measured? A wrong **Tag size (mm)** breaks the distance
  scale (not detection), but measure it anyway
  ([docs/02-marker-tracking.md](02-marker-tracking.md)).

## Device-specific notes

What worked (and what did not) on the two phones this toolkit was built on:
see the case studies in [docs/04-profiles.md](04-profiles.md). The deep
background is in [docs/reverse-engineering.md](reverse-engineering.md).
