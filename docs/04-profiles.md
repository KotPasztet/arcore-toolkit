# Device profiles and the profile server

ARCore identifies a device by its `ro.product.device` and looks up a
calibration profile in a database packed inside the ARCore APK. Unknown
device → "unsupported". This page explains how the app gets a profile
onto the phone, how to run your own profile server, and what the numbers
mean. The reverse-engineered background is in
[docs/reverse-engineering.md](reverse-engineering.md).

## What a profile contains

A profile is a binary protobuf with camera and IMU calibration:

| Part | Content |
|---|---|
| Camera | focal length, principal point, distortion, rolling-shutter readout time, timestamp alignment — for ARCore's 640×480 stream |
| IMU | scale, bias, noise densities, random walks |
| Extrinsics | where the IMU sits relative to the camera |

ARCore looks the device up by a key: the first 6 characters of a salted MD5,
base64url-encoded (`base64url(md5(SALT ‖ device))[:6]` with
`SALT = 0c e8 8d 52 6f b7 40 d7`). It also probes sensor-qualified variants
of the device name, so a complete ZIP contains several keys.

Two practical consequences:

* A profile from a **similar phone often works** — the profile is a
  calibration hint, not a whitelist. The focal length matters most: a 10%
  error means a 10% scale error for everything ARCore builds.
* Wrong timestamps or IMU numbers make tracking wobble or drift, especially
  during motion.

## Getting a profile: the app way (no root, no PC)

The trick: ARCore can download a profile at runtime from an HTTPS URL, even
on a locked phone. The app drives that for you from the **Profiles…**
dialog:

| Action | What it does |
|---|---|
| **Auto: match this camera** | computes your camera's focal length, picks the closest profile on the server and requests a custom one with your intrinsics but the donor's IMU/timing values. Best first move. |
| tap a profile | downloads it as is |
| **Server URL…** | changes the profile server (default: `https://nikonekt.ru:9444/arcore/switch/`) |
| **Advanced tuning…** | edits individual parameters — see below |

The dialog shows each profile's focal length and marks the one closest to
your camera with a star. If ARCore rejects the applied profile, the app
offers **Re-apply …** to restore the last working one
([docs/03-troubleshooting.md](03-troubleshooting.md)).

**Advanced tuning** is a thin UI over the server's custom-profile endpoint:
leave a field empty to keep the base profile's value, tap **Apply**, and the
server rebuilds the profile and ARCore restarts. The sections map 1:1 to the
profile content above: base (donor) profile, camera, camera–IMU sync, lever
arm/rotation, IMU, depth. Two preset buttons apply known-good combinations.

**Device info** (main screen) computes the exact focal length ARCore expects
for the 640×480 stream from your camera's characteristics, e.g.
`focal 3.620 mm, sensor 5.640 × 4.230 mm → fx=410.8 fy=410.8`, and lists the
factory intrinsics if the vendor provides them (most do not). **Copy as
text** copies the whole report — useful for issues and for patching a
profile manually.

## Running your own profile server

`tools/server/switch_server.py` serves profiles and builds custom ZIPs on
request (plain Python, no dependencies). Endpoints:

| Endpoint | What it serves |
|---|---|
| `GET /arcore/switch/<name>/<version>.zip` | the named profile |
| `GET /arcore/switch/custom/<version>.zip?base=<name>&fx=…` | a profile rebuilt with your query parameters (`fx fy cx cy k0 k1 k2 rs tsa tb rss depth` plus IMU values; `keep_extr`/`keep_imu` keep the donor's) |
| `GET /arcore/switch/profiles` | the JSON catalogue the app's profile browser uses |
| `GET /arcore/apk` | optional ARCore runtime APK for the app's installer |

`profiles.json` maps profile names to glob patterns of `.pb` files in the
`--profiles-dir` (see `profiles.json.example`), and `arcore_apk` points at
your own ARCore APK. Serve it behind HTTPS, e.g. with nginx in front:

```nginx
location /arcore/ {
    proxy_pass http://127.0.0.1:8099;
    proxy_read_timeout 30s;
    add_header Cache-Control "no-store";
}
```

The download URL must end in something ARCore parses as
`major.minor.patch` with a matching ARCore `major.minor` (a higher patch
number is fine). Profile files are Google's proprietary data — extract them
from your own installation and do not commit them anywhere public.

## The CLI (for manual work)

`arcore-profile` (installed with `pip install -e .`) does the same things as
the server, from the command line:

| Command | What it does |
|---|---|
| `arcore-profile key DEVICE...` | print the profile key(s) for a device |
| `arcore-profile inspect FILE [--json]` | decode a `.pb` or `.zip` |
| `arcore-profile patch IN -o OUT --fx F --fy F --cx C --cy C` | patch camera fields |
| `arcore-profile patch IN -o OUT --imu-from donor.pb` | copy the IMU block from another profile |
| `arcore-profile imu IN -o OUT --gyro-noise N --accel-noise N` | change only IMU values |
| `arcore-profile zip PB -o ZIP --device NAME1 --device NAME2` | build the ZIP ARCore downloads |
| `arcore-profile extract --assets DIR --device NAME -o PB` | pull a profile out of an ARCore APK |

A full patch example with the numbers this project actually used:

```bash
arcore-profile patch base.pb -o out.pb \
    --fx 410.8 --fy 410.8 --cx 320 --cy 240 \
    --k0 0.001 --k1 0 --k2 0 \
    --rs 26763333 --tsa -20000000 --tb 2 --rss 1 --depth 2
```

To install a ZIP without the app, ARCore has an exported download activity:

```bash
adb shell am start \
  -n com.google.ar.core/com.google.ar.core.services.profiles.ProfileDownloadActivity \
  --es explicit-download-url "https://HOST/arcore/switch/<name>/1.57.262640283.zip"
```

Check that it worked — the log names the device each key was found for:

```bash
adb logcat | grep -aE "Device profile for|Using local profile database version"
```

The app's HUD also shows the focal length ARCore actually uses — the quickest
confirmation. Details of the ZIP format (`profiles.toc` +
`profiles_NNNNN.dat`) and the key algorithm are in
[docs/reverse-engineering.md](reverse-engineering.md).

## Case studies: the two test devices

Everything above was developed on two unsupported phones; both run ARCore
without root.

| | POCO X6 Pro (`duchamp`) | Huawei P30 Lite (`HWMAR`) |
|---|---|---|
| Profile key | `pADuJF` (absent from ARCore 1.57) | `nJH8dB` + 3 sensor-qualified variants |
| What worked | sideloaded a `chopin`/`marble` profile, patched intrinsics | donor profile from a POCO X3 GT → **fully working real VIO** |
| Result | real VIO stayed ~8 Hz and jittery → marker shim chosen | real ARCore usable; shim as plan B |
| fx at 640×480 | 464.56 (donor) | 410.8 (computed from 3.62 mm / 5.64 mm) |

Lessons worth reusing:

* The P30 Lite's donor profile worked as is, but the inherited focal length
  was 13% too high — patching the computed fx fixed the distance scale.
  Compute your own fx with **Device info**.
* The POCO X6 Pro had an IMU/time-sync problem on top of the missing
  profile; tuning IMU values helped, but stable tracking only came from the
  marker shim ([docs/06-shim.md](06-shim.md)).
* The P30 Lite probes four device strings (plain, sensor-qualified, and
  `model/device` combinations) — put all their keys in the ZIP or the lookup
  can miss.
* At 90 °C and 10% battery the camera drops to ~7 fps. Test charged and cool.
