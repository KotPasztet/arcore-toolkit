# Calibration (advanced)

You only need this when auto-match gives a profile but tracking is still
unstable. A profile's camera intrinsics and IMU calibration decide how well
ARCore can track; on most phones the factory numbers are hidden, so you
measure them: record a dataset with the app's **Calibration** screen, run it
through [Kalibr](https://github.com/ethz-asl/kalibr), and patch the results
into the profile. The flow: print target → record → convert to a ROS bag →
Kalibr → patch profile.

## 1. Print a target

For Kalibr you want the AprilGrid (not the single tag from the app's
**Print the tag (PDF)** button). Generate it with Python — the script writes
`aprilgrid_a4_6x6_25mm.pdf` / `.png` / `.jpg`:

```bash
python3 tools/calib/make_aprilgrid.py
```

AprilGrid 6×6, tag 25 mm, spacing 0.3, A4 at 300 DPI, with a 100 mm scale
bar. Measure the bar: printing is usually ~99%, so the tag size becomes
`0.02475 m` (25 mm × 0.99) in the Kalibr config. Glue the printout to a
rigid flat backing.

## 2. Record a session

In the app: open **Calibration**, follow the on-screen instructions, tap
**Start**, keep still for the first 5 s, then move smoothly for 60–90 s
around the target: all orientations, distances 0.3–0.8 m, target filling
more than half the frame, no motion blur. Tap **Stop**.

The recorder writes:

```
files/calib/<session>/
├── frames/*.jpg    1280×960, ~20 fps
├── frames.csv      timestamp_ns,filename
├── imu.csv         timestamp_ns,type,gx,gy,gz,ax,ay,az   (~400 Hz)
└── meta.txt        camera characteristics
```

Pull it to the PC (first command packs the folder, second unpacks it):

```bash
adb exec-out run-as io.github.nikonektik.arcoretoolkit tar -c files/calib > calib.tar
tar -xf calib.tar
```

## 3. Convert to a ROS bag

```bash
pip install -r tools/calib/requirements.txt   # numpy, pillow, rosbags
python3 tools/calib/calib_to_bag.py files/calib/<session> -o session.bag
```

Topics: `/cam0/image_raw` and `/imu0`. Both streams use the phone's REALTIME
clock — that is what makes Kalibr's time-shift estimate meaningful.

## 4. Run Kalibr

```bash
kalibr_calibrate_imu_camera \
  --bag session.bag \
  --cam camchain.yaml \
  --imu imu.yaml \
  --target tools/calib/kalibr_target_aprilgrid.yaml
```

Minimal `camchain.yaml` (Kalibr refines the starting guess):

```yaml
cam0:
  camera_model: pinhole
  intrinsics: [470, 470, 320, 240]
  distortion_model: radtan
  distortion_coeffs: [0.0, 0.0, 0.0, 0.0]
  resolution: [1280, 960]
  rostopic: /cam0/image_raw
```

`imu.yaml`:

```yaml
imu0:
  model: calibrated
  topic: /imu0
  update_rate: 400.0
  accelerometer_noise_density: 0.005
  accelerometer_random_walk: 0.01
  gyroscope_noise_density: 0.0005
  gyroscope_random_walk: 0.0001
  rostopic: /imu0
```

Kalibr reports intrinsics and distortion (`results-cam-*.txt`), the
camera↔IMU transform and time shift (`results-imucam-*.txt`), and calibrated
IMU noise (`imu-*.yaml`).

## 5. Turn the results into a profile

1. **Intrinsics**: recorded at 1280×960 → halve `fx, fy, cx, cy` for the
   profile (ARCore's stream is 640×480).
2. **Distortion**: ARCore uses radial `k0, k1, k2`; Kalibr gives radtan
   `k1, k2, p1, p2` — feed `k1, k2` and verify empirically.
3. **Time shift** (cam→IMU, seconds) → `tsa` in nanoseconds; mind the sign
   convention and verify by tracking quality.
4. **IMU** → noise densities and random walks; **rolling shutter** → from
   the sensor datasheet/HAL if available.

```bash
arcore-profile patch base.pb -o device.pb \
    --fx 237.95 --fy 235.93 --cx 151.14 --cy 122.34 \
    --k0 0.1424 --k1 -0.2053 --k2 0.0043 \
    --tsa -10360000
arcore-profile imu device.pb -o device.pb --gyro-noise 0.0005 --accel-noise 0.005
arcore-profile zip device.pb -o out.zip --device <ro.product.device>
```

Then serve `out.zip` from your profile server
([docs/04-profiles.md](04-profiles.md)).

## Camera-only calibration (no Kalibr)

If you only need the intrinsics, `tools/calib/calib_opencv.py` calibrates a
folder of AprilGrid frames and prints `K` and distortion at the recording
resolution — scale by resolution for the 640×480 profile:

```bash
python3 tools/calib/calib_opencv.py files/calib/<session>/frames
```

## Interpreting the numbers

* Reprojection error < 1 px is good; higher means the target moved or frames
  are blurry.
* Gyro residual < 0.01 rad/s, accel < 0.15 m/s² is typical for phones.
* A time shift of 5–15 ms is normal; getting it wrong by >10 ms visibly
  destabilises VIO during rotation.
