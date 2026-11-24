# ARCore profiles

Everything here is reverse-engineered from ARCore 1.5x.  Field names are
inferred from behaviour; field **numbers** are what matters.

## 1. Device key

ARCore identifies a device by the value of `ro.product.device` (`Build.DEVICE`):

```
key = base64url_nopad( md5( SALT || device ) )[:6]
SALT = 0c e8 8d 52 6f b7 40 d7
```

```bash
arcore-profile key duchamp HWMAR flame
# duchamp  pADuJF
# HWMAR    nJH8dB
# flame    Ub5OOR
```

ARCore 1.57 on some devices probes more than the plain device name, adding the
IMU and camera sensor names, e.g. for a Huawei P30 Lite:

```
HWMAR                                             -> nJH8dB
HWMAR_SFP_accelerometer-bmi160_SUNNY_SFP          -> HX2bDI
MAR-LX1MRU/HWMAR                                  -> Q75qu9
MAR-LX1MRU/HWMAR_SFP_accelerometer-bmi160_SUNNY_SFP -> yFGZd1
```

Because you cannot know which one wins, put **all** variants into the ZIP
(`--device` accepts a list, see §5).

## 2. Database format

`assets/packed_profiles/profiles.toc` is a protobuf:

```
repeated Entry {
  string key = 1;              // "<obf>:<suffix>"
  Value  value = 2;            // { 1: dat_index, 2: offset, 3: length }
}
```

`profiles_NNNNN.dat` are concatenated binary `DeviceProfile` protobufs, not
encrypted.  The suffix after `:` is a variant selector (API level, sensor
combination, …); ARCore probes several of them.

## 3. DeviceProfile fields

| Field | Type | Meaning |
|---|---|---|
| **1** | message, repeated | cameras (`camera[0]` is the main one) |
| **2** | message | parameter map; key **100** = IMU calibration |
| **3** | message, repeated | camera↔IMU extrinsics `{p, q}` |
| **4** | message, repeated | other blocks |

Camera (field 1):

| Field | Type | Meaning |
|---|---|---|
| 1 | string | camera id (`"0"`) |
| 3 | varint | rolling-shutter skew, ns |
| 5 | int64 | camera timestamp alignment, ns |
| 6 | varint | width (640) |
| 7 | varint | height (480) |
| 8 | message `{1: fx, 2: fy}` | focal length in pixels |
| 9 | message `{1: cx, 2: cy}` | principal point |
| 11 | message `{1: k0, 2: k1, 3: k2}` | radial distortion |
| 17 | varint | camera timebase trust level |
| 18 | varint | rolling-shutter data source |
| 27 | varint | depth API support mode |

IMU (field 2, key 100):

| Field | Type | Meaning |
|---|---|---|
| 1 | 3×double | gyro scale / misalignment |
| 2 | 3×double | gyro misalignment |
| 3 | 3×double | accel scale |
| 4 | 3×double | accel misalignment |
| 5 | 4×double | orientation quaternion |
| 6 | double | gyro noise density |
| 7 | double | gyro random walk |
| 8 | double | accel noise density |
| 9 | double | accel random walk |
| 10 | 3×double | gyro bias |
| 11 | 3×double | accel bias |

## 4. CLI

```bash
arcore-profile inspect profile.pb            # human readable
arcore-profile inspect profile.pb --json     # machine readable
arcore-profile inspect out.zip               # first .dat inside the ZIP
```

Patch the camera:

```bash
arcore-profile patch base.pb -o out.pb \
    --fx 410.8 --fy 410.8 --cx 320 --cy 240 \
    --k0 0.001 --k1 0 --k2 0 \
    --rs 26763333 --tsa -20000000 --tb 2 --rss 1 --depth 2
```

Copy the IMU block from another profile (e.g. keep a device with a proven IMU
calibration):

```bash
arcore-profile patch base.pb -o out.pb --fx 410.8 --imu-from donor.pb
```

Change only the IMU:

```bash
arcore-profile imu base.pb -o out.pb \
    --gyro-scale 1.0286,1.0151,1.002 --accel-scale 1.0,1.0,0.995 \
    --gyro-bias 0.0046,-0.0022,0.0015 \
    --gyro-noise 0.0005309 --accel-noise 0.0048836
```

Extract a profile from your own ARCore APK (unpack `assets/packed_profiles`
first):

```bash
unzip -q com.google.ar.core.apk 'assets/packed_profiles/*' -d arcore
arcore-profile extract --assets arcore/assets/packed_profiles \
    --device flame -o flame.pb
```

## 5. Building the ZIP ARCore downloads

```bash
arcore-profile zip out.pb -o out.zip --device HWMAR \
    --device HWMAR_SFP_accelerometer-bmi160_SUNNY_SFP \
    --device MAR-LX1MRU/HWMAR
# or with raw keys:
arcore-profile zip out.pb -o out.zip --key pADuJF
```

The ZIP contains:

```
profiles.toc        entries "<key>:<suffix>" for 0..35, Q, 10..15, 34
profiles_00000.dat  the profile
```

## 6. Installing without root

ARCore has an exported activity that downloads and unpacks a profile database
into its private storage:

```bash
adb shell am start \
  -n com.google.ar.core/com.google.ar.core.services.profiles.ProfileDownloadActivity \
  --es explicit-download-url "https://HOST/arcore/switch/<name>/1.57.262640283.zip"
```

Requirements:

* the last URL segment must parse as `major.minor.patch` (the ARCore version's
  `major.minor` must match);
* `Content-Type: application/zip`;
* HTTPS (targetSdk ≥ 28 blocks cleartext).

Check that it worked:

```bash
adb logcat | grep -aE "Device profile for|Using local profile database version"
# Device profile for HUAWEI/...: yFGZd1:10
# Using local profile database version: 1.57.262640900
```

The test app shows the intrinsics ARCore actually uses (`focal=...`), which is
the quickest way to confirm the profile is active.

## 7. Catalogue endpoint

The test app builds its profile browser from the server catalogue:

```
GET /arcore/switch/profiles
{
  "profiles": [
    {"name": "chopin_patched", "fx": 464.56, "fy": 464.56, "cx": 320, "cy": 240},
    ...
  ],
  "default_key": "pADuJF"
}
```

The intrinsics come from the actual `.pb` files, so the app can pick the closest
donor for the current camera and mark it with a star.

## 8. Server

`tools/server/switch_server.py` builds ZIPs per request:

```
GET /arcore/switch/<name>/<version>.zip[?dev=..|?key=..]
GET /arcore/switch/custom/<version>.zip?base=<name>&fx=..&fy=..&cx=..&cy=..
```

`profiles.json` maps names to glob patterns and can pin device keys:

```json
{
  "profiles": {
    "chopin_patched": "profiles/chopinpatched_*.pb",
    "hwmar": "profiles/hwmar_*.pb"
  },
  "keys": {
    "hwmar": ["HWMAR", "HWMAR_SFP_accelerometer-bmi160_SUNNY_SFP"]
  },
  "default_key": "pADuJF"
}
```

Custom parameters: `fx fy cx cy k0 k1 k2 rs tsa tb rss depth gsx gsy gsz gn an`.
`?base=` selects the source profile.

Serve it behind HTTPS, e.g. nginx:

```nginx
location /arcore/switch/ {
    proxy_pass http://127.0.0.1:8099;
    proxy_read_timeout 30s;
    add_header Cache-Control "no-store";
}
```

## 9. Practical notes

* ARCore probes the device key **and** sensor-qualified variants; include all.
* A "random" profile from a similar phone often works (the profile is a
  calibration hint, not a whitelist).  The intrinsics matter most: a 10 % focal
  error means a 10 % scale error for everything ARCore builds.
* The ARCore version in the URL only needs the same `major.minor`; using a
  higher patch number is fine and makes ARCore prefer the new database.
* Do not put proprietary profile files in a public repository — extract them
  from your own installation.
