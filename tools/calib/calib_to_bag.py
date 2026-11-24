#!/usr/bin/env python3
"""Convert a CalibrationActivity session (JPEG frames + imu.csv) to a ROS1 bag for Kalibr.

Topics:
  /cam0/image_raw  sensor_msgs/Image  bgr8
  /imu0            sensor_msgs/Imu    (gyro + paired accel, SI units)

Timestamps come from the phone clock (REALTIME, ns) and are shared by both streams.
"""
import argparse
import bisect
import csv
import os

import numpy as np
from PIL import Image

from rosbags.rosbag1 import Writer
from rosbags.typesys import Stores, get_typestore

MAX_PAIR_GAP_NS = 15_000_000  # 15 ms


def load_frames(session):
    rows = list(csv.reader(open(os.path.join(session, "frames.csv"))))
    out = []
    for row in rows:
        if len(row) < 3:
            continue
        out.append((int(row[1]), row[2]))
    out.sort()
    return out


def load_imu(session):
    gyro, accel = [], []
    for row in csv.reader(open(os.path.join(session, "imu.csv"))):
        if len(row) < 5:
            continue
        t = int(row[0])
        vals = (float(row[2]), float(row[3]), float(row[4]))
        if row[1] == "gyro":
            gyro.append((t, vals))
        elif row[1] == "accel":
            accel.append((t, vals))
    gyro.sort()
    accel.sort()
    return gyro, accel


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("session")
    ap.add_argument("bag")
    ap.add_argument("--max-frames", type=int, default=0)
    ap.add_argument("--mono", action="store_true", help="write mono8 instead of bgr8")
    args = ap.parse_args()

    session = os.path.abspath(args.session)
    frames = load_frames(session)
    if args.max_frames:
        frames = frames[: args.max_frames]
    gyro, accel = load_imu(session)

    ts = get_typestore(Stores.ROS1_NOETIC)
    Time = ts.types["builtin_interfaces/msg/Time"]
    Header = ts.types["std_msgs/msg/Header"]
    ImageMsg = ts.types["sensor_msgs/msg/Image"]
    ImuMsg = ts.types["sensor_msgs/msg/Imu"]
    Quat = ts.types["geometry_msgs/msg/Quaternion"]
    Vec3 = ts.types["geometry_msgs/msg/Vector3"]

    accel_ts = [t for t, _ in accel]
    imu_msgs = 0
    imu_skipped = 0
    for t, g in gyro:
        i = bisect.bisect_left(accel_ts, t)
        best = None
        for j in (i - 1, i):
            if 0 <= j < len(accel):
                d = abs(accel_ts[j] - t)
                if best is None or d < best[0]:
                    best = (d, accel[j][1])
        if best is None or best[0] > MAX_PAIR_GAP_NS:
            imu_skipped += 1
            continue
        a = best[1]
        stamp = Time(sec=t // 1_000_000_000, nanosec=t % 1_000_000_000)
        hdr = Header(seq=imu_msgs, stamp=stamp, frame_id="imu0")
        msg = ImuMsg(
            header=hdr,
            orientation=Quat(x=0.0, y=0.0, z=0.0, w=1.0),
            orientation_covariance=np.zeros(9, dtype=np.float64),
            angular_velocity=Vec3(x=float(g[0]), y=float(g[1]), z=float(g[2])),
            angular_velocity_covariance=np.zeros(9, dtype=np.float64),
            linear_acceleration=Vec3(x=float(a[0]), y=float(a[1]), z=float(a[2])),
            linear_acceleration_covariance=np.zeros(9, dtype=np.float64),
        )
        imu_raw.append((t, msg))
        imu_msgs += 1

    # interleave by time
    imu_raw.sort(key=lambda x: x[0])

    with Writer(args.bag) as writer:
        cimg = writer.add_connection("/cam0/image_raw", ImageMsg.__msgtype__, typestore=ts)
        cimu = writer.add_connection("/imu0", ImuMsg.__msgtype__, typestore=ts)

        ii = 0
        n = 0
        for t, name in frames:
            while ii < len(imu_raw) and imu_raw[ii][0] <= t:
                mt, msg = imu_raw[ii]
                writer.write(cimu, mt, ts.serialize_ros1(msg, ImuMsg.__msgtype__))
                ii += 1
            path = os.path.join(session, "frames", name)
            if args.mono:
                gray = Image.open(path).convert("L")
                w, h = gray.size
                data = np.asarray(gray, dtype=np.uint8)
                encoding = "mono8"
                step = w
            else:
                rgb = Image.open(path).convert("RGB")
                w, h = rgb.size
                data = np.asarray(rgb, dtype=np.uint8)[:, :, ::-1].copy()  # BGR
                encoding = "bgr8"
                step = w * 3
            stamp = Time(sec=t // 1_000_000_000, nanosec=t % 1_000_000_000)
            msg = ImageMsg(
                header=Header(seq=n, stamp=stamp, frame_id="cam0"),
                height=h,
                width=w,
                encoding=encoding,
                is_bigendian=0,
                step=step,
                data=data.reshape(-1),
            )
            writer.write(cimg, t, ts.serialize_ros1(msg, ImageMsg.__msgtype__))
            n += 1
        for mt, msg in imu_raw[ii:]:
            writer.write(cimu, mt, ts.serialize_ros1(msg, ImuMsg.__msgtype__))

    print(f"bag={args.bag} frames={n} imu={imu_msgs} imu_skipped={imu_skipped}")


if __name__ == "__main__":
    imu_raw = []
    main()
