"""Command line interface: ``arcore-profile``."""

from __future__ import annotations

import argparse
import json
import sys

from . import core


def _floats(values):
    if values is None:
        return None
    return [float(v) for v in values]


def _parse_csv3(text):
    if text is None:
        return None
    parts = [p for p in text.replace(";", ",").split(",") if p.strip()]
    if len(parts) != 3:
        raise argparse.ArgumentTypeError("expected three comma-separated numbers")
    return [float(p) for p in parts]


def cmd_key(args) -> int:
    for device in args.device:
        print(f"{device}\t{core.obf(device)}")
    return 0


def cmd_inspect(args) -> int:
    profile = core.read_profile(args.profile)
    dump = profile.dump()
    if args.json:
        print(json.dumps(dump, indent=2, ensure_ascii=False, default=str))
        return 0

    intr = dump["intrinsics"]
    cam = dump["camera"]
    print(f"cameras: {', '.join(dump['cameras'])}")
    print("camera[0]:")
    for key in ("fx", "fy", "cx", "cy", "k0", "k1", "k2"):
        print(f"  {key:<3} = {intr[key]}")
    print("  flags: " + ", ".join(f"{k}={v}" for k, v in cam.items()))
    imu = dump["imu"]
    if imu:
        print("imu:")
        for key, value in imu.items():
            if isinstance(value, dict):
                value = {k: round(v, 6) for k, v in sorted(value.items())}
            elif isinstance(value, float):
                value = round(value, 8)
            print(f"  {key}: {value}")
    return 0


def _patch(args) -> int:
    profile = core.read_profile(args.base)
    profile.set_intrinsics(
        fx=args.fx, fy=args.fy, cx=args.cx, cy=args.cy,
        k0=args.k0, k1=args.k1, k2=args.k2,
    )
    profile.set_camera_flags(
        rs=args.rs, tsa=args.tsa, tb=args.tb, rss=args.rss, depth=args.depth,
    )
    if args.imu_from:
        base = core.read_profile(args.imu_from)
        imu = base.imu_values()
        profile.set_imu(
            gyro_scale=[imu["gyro_scale"].get(i) for i in (1, 2, 3)],
            accel_scale=[imu["accel_scale"].get(i) for i in (1, 2, 3)],
            gyro_bias=[imu["gyro_bias"].get(i) for i in (1, 2, 3)],
            accel_bias=[imu["accel_bias"].get(i) for i in (1, 2, 3)],
            gyro_noise=imu["gyro_noise"],
            gyro_random_walk=imu["gyro_random_walk"],
            accel_noise=imu["accel_noise"],
            accel_random_walk=imu["accel_random_walk"],
        )
    with open(args.out, "wb") as fh:
        fh.write(profile.to_bytes())
    print(f"wrote {args.out} ({len(profile.to_bytes())} bytes)")
    return 0


def cmd_patch(args) -> int:
    return _patch(args)


def cmd_imu(args) -> int:
    profile = core.read_profile(args.base)
    profile.set_imu(
        gyro_scale=_parse_csv3(args.gyro_scale),
        accel_scale=_parse_csv3(args.accel_scale),
        gyro_bias=_parse_csv3(args.gyro_bias),
        accel_bias=_parse_csv3(args.accel_bias),
        gyro_noise=args.gyro_noise,
        gyro_random_walk=args.gyro_random_walk,
        accel_noise=args.accel_noise,
        accel_random_walk=args.accel_random_walk,
    )
    with open(args.out, "wb") as fh:
        fh.write(profile.to_bytes())
    print(f"wrote {args.out} ({len(profile.to_bytes())} bytes)")
    return 0


def cmd_zip(args) -> int:
    profile = core.read_profile(args.profile)
    keys = list(args.key or [])
    for device in args.device or []:
        keys.append(core.obf(device))
    if not keys:
        raise SystemExit("pass --device and/or --key")
    data = core.build_zip(profile.to_bytes(), keys)
    with open(args.out, "wb") as fh:
        fh.write(data)
    print(f"wrote {args.out} ({len(data)} bytes), keys: {', '.join(keys)}")
    return 0


def cmd_extract(args) -> int:
    blob, device = core.extract_from_assets(args.assets, args.device)
    with open(args.out, "wb") as fh:
        fh.write(blob)
    print(f"extracted {device} -> {args.out} ({len(blob)} bytes)")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="arcore-profile",
        description="Inspect, patch and package ARCore device profiles.",
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("key", help="print the profile key for device names")
    p.add_argument("device", nargs="+", help="ro.product.device values")
    p.set_defaults(func=cmd_key)

    p = sub.add_parser("inspect", help="print a profile (.pb or .zip)")
    p.add_argument("profile")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_inspect)

    p = sub.add_parser("patch", help="change camera intrinsics/flags")
    p.add_argument("base")
    p.add_argument("-o", "--out", required=True)
    p.add_argument("--fx", type=float)
    p.add_argument("--fy", type=float)
    p.add_argument("--cx", type=float)
    p.add_argument("--cy", type=float)
    p.add_argument("--k0", type=float)
    p.add_argument("--k1", type=float)
    p.add_argument("--k2", type=float)
    p.add_argument("--rs", type=int, help="rolling shutter skew, ns")
    p.add_argument("--tsa", type=int, help="camera timestamp alignment, ns")
    p.add_argument("--tb", type=int, help="timebase trust level")
    p.add_argument("--rss", type=int, help="rolling shutter data source")
    p.add_argument("--depth", type=int)
    p.add_argument("--imu-from", help="copy the IMU block from another profile")
    p.set_defaults(func=cmd_patch)

    p = sub.add_parser("imu", help="change IMU calibration")
    p.add_argument("base")
    p.add_argument("-o", "--out", required=True)
    p.add_argument("--gyro-scale", help="x,y,z")
    p.add_argument("--accel-scale", help="x,y,z")
    p.add_argument("--gyro-bias", help="x,y,z")
    p.add_argument("--accel-bias", help="x,y,z")
    p.add_argument("--gyro-noise", type=float)
    p.add_argument("--gyro-random-walk", type=float)
    p.add_argument("--accel-noise", type=float)
    p.add_argument("--accel-random-walk", type=float)
    p.set_defaults(func=cmd_imu)

    p = sub.add_parser("zip", help="build a profile ZIP ARCore can download")
    p.add_argument("profile")
    p.add_argument("-o", "--out", required=True)
    p.add_argument("--device", action="append", help="ro.product.device (repeatable)")
    p.add_argument("--key", action="append", help="raw 6-char key (repeatable)")
    p.set_defaults(func=cmd_zip)

    p = sub.add_parser("extract", help="extract a profile from unpacked ARCore assets")
    p.add_argument("--assets", required=True,
                   help="path to assets/packed_profiles of the ARCore APK")
    p.add_argument("--device", action="append", required=True)
    p.add_argument("-o", "--out", required=True)
    p.set_defaults(func=cmd_extract)
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
