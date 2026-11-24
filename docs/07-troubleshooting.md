# Troubleshooting

## ADB

| Symptom | Fix |
|---|---|
| `device not found` | re-plug, `adb kill-server && adb start-server`, check the "Allow USB debugging" prompt |
| device disappears after a while | USB power saving / cable; enable "Wireless debugging" and use `adb connect` |
| port changes | mDNS: `avahi-browse -rt _adb-tls-connect._tcp` |
| `unauthorized` | accept the key on the device |

## Logs

```bash
adb logcat -c
adb logcat | grep -aE "ARCORE|ARCore|Minecraft|native|FATAL"

# per app
adb logcat --pid=$(adb shell pidof <package> | tr -d '\r')

# why did the process die
adb shell dumpsys activity exit-info <package>
```

`exit-info` reasons: `5` native crash (`status=6` is SIGABRT), `6` ANR,
`16` package updated (reinstall).

The test app writes to `ARCOREGAME`/`ARCORETEST` tags; the shim to
`ARCORE_SHIM` and to files in the app's data directory.

## App crashes right after launch (`No pending exception expected`)

```
Abort message: 'No pending exception expected: java.lang.IllegalStateException:
Not allowed to start service Intent { ... Firebase ... RegistrationIntentService }:
app is in background'
```

The screen was off (or the app was started in the background) and the app tried
to start a Firebase service; Android 10 throws, the exception stays pending and
ART aborts on the next JNI call.  Wake the device and launch from the launcher:

```bash
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell monkey -p <package> -c android.intent.category.LAUNCHER 1
```

## ANR (10–30 s freeze, "not responding")

Look for texture loading or world loading in the app log:

```
[ERROR PERF] Stalled the main thread by loading texture: ...
```

On a memory-starved phone this turns into an ANR.  Check:

```bash
adb shell free -m
```

Close background apps / reboot, and retry.  `adb bugreport` often fails on a
loaded device ("Failed to connect to dumpstatez service").

## ARCore says the device is unsupported

* `checkAvailability` → `UNSUPPORTED_DEVICE_NOT_CAPABLE`: no usable profile.
  Install one (see [02-profiles.md](02-profiles.md)).
* `Session` creation fails with `UnavailableDeviceNotCompatibleException`: the
  profile was found but the camera configuration does not match (camera id,
  stream sizes) or the session could not start the camera.
* Check the lookup:

```bash
adb logcat | grep -aE "Device profile for|Found nothing for|Using local profile"
```

The log lists every key ARCore tried; that is how you discover the
sensor-qualified variants.

## Bad tracking even with a profile

1. Check the intrinsics ARCore actually uses (test app prints `focal=...`).
   If it does not match your profile, the profile was not applied.
2. Verify the focal length from the camera characteristics
   (**Device info / intrinsics**).
3. Look at the VIO messages:

```bash
adb logcat | grep -aE "VIO|vio|RESOURCE_EXHAUSTED|desync|pose_manager"
```

`RESOURCE_EXHAUSTED: Behind by ...`, `VIO frequency low`, `Camera to IMU clock
offset` mean the timestamps/IMU calibration are wrong — calibrate
([04-calibration.md](04-calibration.md)).

4. Thermal throttling: at 90 °C+ or <10 % battery the camera and VIO degrade
   (frame rate drops from 30 to 7 fps).  Charge and cool down.

## Marker is not detected

* Is the whole tag inside the frame, with its white border?
* Reflections/gloss? Use matte paper and diffuse light.
* Too far? A 150 mm tag at 1.5 m is only ~45 px — marginal.
* Check `dets=` in `shim.log` or the `err`/distance line in `MarkerActivity`.

## Device-specific notes

See [devices.md](devices.md).
