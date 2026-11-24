**English** | [Русский](ru/devices.md)

# Device case studies

Two devices were used to develop the toolkit.  Both run ARCore without root;
neither is in Google's supported list.

## POCO X6 Pro (`duchamp`, 2311DRK48G)

* HyperOS 2.0.209.0, Android 15.
* Main camera: 64 MP OV64B, same sensor as the POCO F5 (`marble`).
* Profile key: `pADuJF`.  ARCore 1.57 has **no** profile for this device.

What worked:

* Sideloading a profile for `marble`/`chopin` through `ProfileDownloadActivity`
  → `availability=SUPPORTED_INSTALLED`, session created, camera and
  `tracking=TRACKING`.
* Patching the profile with the real intrinsics (464.56 px), `tsa=-20 ms`,
  `tb=2`, `rs=26.76 ms` and an IMU tuning (`viotune`) reduced the VIO errors
  (`plane_manager: refinement should not fail` disappeared) and lowered the
  camera↔IMU desync, but tracking stayed slow (~8 Hz) and jittery.
* The marker shim gave stable, deterministic tracking for the game that was
  being tested.

Lessons:

* The device has an IMU/time-sync problem, not only a missing profile.
* At 90 °C and 10 % battery the camera drops to ~7 fps and detection becomes
  unreliable; test with a charged, cool device.

## Huawei P30 Lite (`HWMAR`, MAR-LX1M)

* Android 10, Kirin 710, IMU Bosch BMI160.
* Main camera: 24 MP IMX576, `focal 3.62 mm`, `physical size 5.64×4.23 mm`
  (active array 5632×4224).

Findings:

* ARCore 1.57 probes four device strings on this phone:

  ```
  HWMAR                                    nJH8dB
  HWMAR_SFP_accelerometer-bmi160_SUNNY_SFP HX2bDI
  MAR-LX1MRU/HWMAR                         Q75qu9
  MAR-LX1MRU/HWMAR_SFP_...                 yFGZd1
  ```

  All four keys must be in the TOC or the lookup can miss.
* A profile from a completely different phone (POCO X3 GT) gave **working real
  VIO**: planes, anchors, stable tracking.  The profile is a calibration hint,
  not a whitelist.
* The correct focal for the 640×480 ARCore stream is
  `3.62 / 5.64 × 640 = 410.8 px`.  The value inherited from the donor profile
  (464.56) was 13 % too high, which scales every distance ARCore produces.
* The vendor exposes no factory `LENS_INTRINSIC_CALIBRATION`/`LENS_DISTORTION`
  and no `rollingShutterSkew` key, so distortion and readout time must be
  calibrated or guessed.

## Summary table

| | POCO X6 Pro | Huawei P30 Lite |
|---|---|---|
| Profile in ARCore 1.57 | no (`pADuJF`) | no (`nJH8dB` + variants) |
| Profile installed | `chopin_patched` (tuned) | donor + patched intrinsics |
| Availability | works | works |
| Real VIO | ~8 Hz, unstable | stable, planes/anchors |
| Chosen solution | marker shim | real ARCore (or shim) |
| Camera | 64 MP OV64B | 24 MP IMX576 |
| fx at 640×480 | 464.56 (profile) | 410.8 (computed) |
