# Reverse engineering ARCore 1.57

This is the original research log that the toolkit grew from: how the device
profile database is laid out, how the key is obfuscated, and how a profile can
be installed without root.  Field names are inferred; the field numbers are what
the tools use.

## Target

- Package: `com.google.ar.core` ("Google Play Services for AR")
- Version: **1.57.262640283** (versionCode 262640283)
- minSdk 29, targetSdk 37
- APK SHA-256: `ae3ba8901ced29dee1314da7d9854a87d5dee681f23343c6b18c89fe3d0ceae8`
- Signer (apksigner): `CN=Android, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US`
  - cert SHA-256: `6e913f65ea53321ead6932ff777e1b7f94bbba7e2b0dafdd8494b100101e3797`
  - => original Google signature, APKPure did not re-sign it.
- Source: APKPure via `apkeep -d apk-pure` (xapk). Treat APKPure with suspicion in general; signature above is the check.

## Layout

```
apk/…xapk                 raw download
xapk/                     extracted base.apk + config splits
extract/                  assets/, lib/, classes*.dex, AndroidManifest.xml
apktool/                  decoded manifest/resources (no smali)
jadx/                     decompiled Java
profiles_out/             extracted binary DeviceProfiles (protobuf)
obf.py                    key oracle / profile extractor
```

## APK contents (highlights)

- `lib/arm64-v8a/libarcore_c.so` — 36 MB, the runtime (stripped, obfuscated)
- `lib/arm64-v8a/libhardware_buffer_stream_jni.so` — 18 KB
- `assets/packed_profiles/profiles.toc` — 79 KB protobuf index
- `assets/packed_profiles/profiles_00000.dat … profiles_00050.dat` — **51 files**
- `assets/device_profile_max_compatibility.textproto` — generic profile, references
  `github.com/tomthecarrot/arcore-for-all/issues/139`
- ML models: depth, semantics, face, deeplight, etc.
- 3 dex: 65 KB + 5.2 MB + 385 KB

## Device-profile DB format

`profiles.toc` is a protobuf: repeated `field 1` entries, each:

```
1 {
  1: "<obf>:<suffix>"        # obf key (sometimes packed as uint64 in field 13)
  2 { 1: <dat_index> 2: <offset> 3: <length> }
}
```

- 3383 entries, 3173 unique 6-char keys
- `profiles_NNNNN.dat` = concatenated **binary DeviceProfile** protobufs (not encrypted)
- 51 dat files, ~45 KB each

## Key obfuscation (cracked)

The native JNI export
`Java_com_google_ar_core_services_profiles_ProfileDownloadHelper_obfuscateDevice`
(0x11f3200) does:

```
obf(device) = base64url_nopad( MD5( SALT || device ) )[:6]
SALT = 0c e8 8d 52 6f b7 40 d7
```

Confirmed by disassembly:
- `EVP_DigestInit_ex(&ctx_, EVP_md5(), nullptr) == 1`
- update(salt, 8); update(device_name, len); final (16-byte digest)
- base64url alphabet `A–Za–z0–9-_`, no padding
- take first **6** chars

`device` = `Build.DEVICE` = `ro.product.device`.

## Validation (supported devices found in DB)

| device (ro.product.device) | obf | in DB |
|---|---|---|
| flame (Pixel 4) | Ub5OOR | yes |
| blueline (Pixel 3) | ox1uFp | yes |
| crosshatch (Pixel 3 XL) | 7l8C9M | yes |
| coral (Pixel 4 XL) | za-jzj | yes |
| marble (POCO F5) | fJKExA | yes |
| alioth (POCO F3) | k-Shfr | yes |
| vayu (POCO X3 Pro) | pUVpQN | yes |
| sweet (Redmi Note 10 Pro) | OCG0Cn | yes |
| chopin (POCO X3 GT) | Wg0hne | yes |
| toco / cepheus / violet / begonia / karna / redwood / munch | — | yes |
| **duchamp (POCO X6 Pro)** | **pADuJF** | **ABSENT** |

`duchamp`, `duchamp_global`, `2311DRK48G` — none present (raw byte search, independent of parser).
=> ARCore 1.57 ships **no profile** for POCO X6 Pro, as expected.

The suffix after `:` is metadata (correlates loosely with Android version, e.g. `marble:13`,
`alioth:11`); lookups match the 6-char prefix.

## Extracted profiles (camera 0, 640×480)

| device | focal (px) | principal | distortion k0,k1,k2 | RS readout |
|---|---|---|---|---|
| marble (POCO F5) | 463.3 / 464.2 | 321.64 / 239.97 | 0.1548, −0.4610, 0.5188 | 30.9 ms |
| alioth (POCO F3) | 478.2 / 476.7 | 319.76 / 239.41 | 0.1528, −0.4842, 0.4745 | 32.7 ms |
| vayu (POCO X3 Pro) | 468.5 / 470.0 | 318.52 / 239.67 | 0.1639, −0.4177, 0.3474 | 32.7 ms |
| flame (Pixel 4) | 498.7 / 498.7 | 322.21 / 237.08 | 0.0855, −0.1757, 0.1031 | 32.2 ms |

POCO X6 Pro and POCO F5 both use a 64 MP **OV64B** main sensor (1/2", 0.7 µm)
+ 8 MP + 2 MP. So `marble` is the closest certified calibration to start from.

## Custom profile mechanism (still present in 1.57)

Native `hasCustomDeviceProfile()` = `access("/data/data/com.google.ar.core/files/custom_device_profile.textproto", F_OK)`.
If present, ARCore loads it before the packed DB (documented by ARCore team in
`tomthecarrot/arcore-for-all#139`, Dec 2018; still referenced by the shipped asset).

Requires writing to the app-private dir => **root** on modern Android
(the old `adb backup`/restore trick is effectively dead on Android 12+).
SELinux context may also need fixing.

## OTA profile URL

```java
"https://www.gstatic.com/arcore/profiles/latest.zip?v=" +
    new ctt(context).b() + "&d=" + obfuscateDevice(Build.DEVICE)
```

`ctt.b()` = "major.minor" of the ARCore version (e.g. `1.57`).
Plain `latest.zip` currently returns 404; the server likely validates `v`/`d`.
`ProfileDownloadActivity` / `ProfileDownloadJobService` / `ArCoreContentProvider`
implement the download + `device_profile` content provider.
