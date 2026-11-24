**English** | [Русский](ru/04-calibration.md)

# Calibration

A profile's camera intrinsics and IMU calibration decide how well ARCore can
track.  Factory data is not available on most phones, so you measure it.

## 1. Print a target

```bash
python3 tools/calib/make_aprilgrid.py
# aprilgrid_a4_6x6_25mm.pdf / .png / .jpg
```

AprilGrid 6×6, tag 25 mm, spacing 0.3, A4 at 300 DPI.  The sheet has a 100 mm
scale bar: measure it and scale the target numbers by the result (printing is
usually ~99 %, so `tagSize = 0.02475` in the Kalibr config).

`apps/` also contains a single large marker generator
(`make_marker.py`, AprilTag 36h11 ID 0, 150 mm) for marker AR.

## 2. Record a session

In the app: **Calibration** → record ~60–90 s while moving the phone slowly in
front of the target: all orientations, distances 0.3–0.8 m, no motion blur.

The recorder writes:

```
files/calib/<session>/
├── frames/*.jpg
├── frames.csv      timestamp_ns,filename
├── imu.csv         timestamp_ns,type,gx,gy,gz,ax,ay,az
└── meta.txt        camera characteristics
```

Pull it:

```bash
adb exec-out run-as io.github.nikonektik.arcoretoolkit tar -c files/calib > calib.tar
tar -xf calib.tar
```

## 3. Convert to a ROS bag

```bash
pip install -r tools/calib/requirements.txt   # numpy, pillow, rosbags
python3 tools/calib/calib_to_bag.py files/calib/<session> -o session.bag
```

Topics: `/cam0/image_raw` (`bgr8`) and `/imu0` (gyro with the closest accel
sample, SI units).  Timestamps are the phone's `REALTIME` clock, shared by both
streams — that is what makes Kalibr's time-shift estimate meaningful.

## 4. Kalibr

```bash
# camera + IMU, using the printed target
kalibr_calibrate_imu_camera \
  --bag session.bag \
  --cam camchain.yaml \
  --imu imu.yaml \
  --target tools/calib/kalibr_target_aprilgrid.yaml
```

Minimal `camchain.yaml`:

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

Kalibr reports:

* `results-cam-*.txt` — intrinsics at the recording resolution, distortion,
  reprojection error;
* `results-imucam-*.txt` — camera↔IMU transform, time shift, residual errors;
* `imu-*.yaml` — calibrated noise densities and random walks.

## 5. Turn the results into a profile

1. **Intrinsics at 640×480**: if you recorded at 1280×960, halve `fx, fy, cx, cy`
   (the profile describes ARCore's 640×480 stream).
2. **Distortion**: ARCore uses three radial coefficients `k0, k1, k2`.  Kalibr
   gives `radtan` `k1, k2, p1, p2`; feed `k1, k2` and put the third coefficient to
   the next order (or zero) and verify empirically.
3. **Timeshift** (cam→IMU, seconds) → `tsa` field, nanoseconds, `-shift` or
   `+shift` depending on the sign convention; verify by tracking quality.
4. **IMU** → gyro/accel noise density and random walk (`gn`, `an`, ...).
5. **Rolling shutter**: the skew from the sensor datasheet/HAL if available.

```bash
arcore-profile patch base.pb -o device.pb \
    --fx 237.95 --fy 235.93 --cx 151.14 --cy 122.34 \
    --k0 0.1424 --k1 -0.2053 --k2 0.0043 \
    --tsa -10360000
arcore-profile imu device.pb -o device.pb \
    --gyro-noise 0.0005 --accel-noise 0.005
arcore-profile zip device.pb -o out.zip --device <ro.product.device>
```

## 6. OpenCV-only camera calibration

If you only need the camera (no IMU), `tools/calib/calib_opencv.py` calibrates a
folder of AprilGrid frames:

```bash
python3 tools/calib/calib_opencv.py files/calib/<session>/frames
```

It prints `K` and the distortion coefficients at the recording resolution; scale
by resolution for the 640×480 profile.

## 7. Interpreting the numbers

* Reprojection error < 1 px is good.  If it is higher, the target moved or the
  frames are blurry.
* Gyro residual < 0.01 rad/s, accel < 0.15 m/s² is typical for phones.
* A time shift of 5–15 ms is normal.  Getting it wrong by >10 ms visibly
  destabilises VIO during rotation.
