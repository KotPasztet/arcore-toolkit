"""ARCore device-profile primitives.

Everything here was reverse-engineered from ARCore 1.57 (`com.google.ar.core`)
and is based on observation, not on published documentation:

* the device key is ``base64url(md5(SALT || ro.product.device))[:6]``;
* ``profiles.toc`` is a protobuf ``repeated Entry { string key; Value value; }``
  where ``Value = {1: dat_index, 2: offset, 3: length}`` and the key is
  ``<obf>:<suffix>``;
* ``profiles_NNNNN.dat`` files are concatenated binary ``DeviceProfile``
  protobufs (not encrypted).

Field numbers inside ``DeviceProfile`` are documented in ``docs/02-profiles.md``.
"""
from __future__ import annotations

import base64
import hashlib
import io
import os
import struct
import zipfile
from typing import Iterable, Sequence

SALT = bytes.fromhex("0ce88d526fb740d7")

# Suffixes ARCore probes after the device key.  0..35 covers the Android API
# levels it maps, the rest are variants seen in the shipped TOC.
SUFFIXES = [str(i) for i in range(36)] + [
    "Q", "14", "34", "13", "15", "12", "11", "10",
]


# --------------------------------------------------------------------------
# device key
# --------------------------------------------------------------------------

def obf(device: str) -> str:
    """ARCore profile key for ``ro.product.device``."""
    digest = hashlib.md5(SALT + device.encode()).digest()
    return base64.urlsafe_b64encode(digest).decode().rstrip("=")[:6]


def obf_many(devices: Iterable[str]) -> list[str]:
    seen: list[str] = []
    for dev in devices:
        key = obf(dev.strip())
        if key and key not in seen:
            seen.append(key)
    return seen


# --------------------------------------------------------------------------
# minimal protobuf
# --------------------------------------------------------------------------

def read_varint(buf: bytes, i: int) -> tuple[int, int]:
    shift = 0
    value = 0
    while True:
        b = buf[i]
        i += 1
        value |= (b & 0x7F) << shift
        if not (b & 0x80):
            return value, i
        shift += 7


def varint(n: int) -> bytes:
    if n < 0:
        n += 1 << 64
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def parse(buf: bytes) -> list[list]:
    """Parse protobuf into ``[[field, wire_type, value], ...]``.

    ``value`` is int for varint, bytes for length-delimited/fixed fields.
    """
    out: list[list] = []
    i = 0
    while i < len(buf):
        tag, i = read_varint(buf, i)
        field, wire = tag >> 3, tag & 7
        if wire == 0:
            value, i = read_varint(buf, i)
            out.append([field, 0, value])
        elif wire == 1:
            out.append([field, 1, buf[i:i + 8]])
            i += 8
        elif wire == 2:
            length, i = read_varint(buf, i)
            out.append([field, 2, buf[i:i + length]])
            i += length
        elif wire == 5:
            out.append([field, 5, buf[i:i + 4]])
            i += 4
        else:
            raise ValueError(f"unsupported wire type {wire}")
    return out


def enc(fields: Sequence[Sequence]) -> bytes:
    out = bytearray()
    for field, wire, value in fields:
        out += varint((field << 3) | wire)
        if wire == 0:
            out += varint(value)
        elif wire == 1:
            out += value
        elif wire == 2:
            out += varint(len(value)) + value
        elif wire == 5:
            out += value
        else:
            raise ValueError(wire)
    return bytes(out)


def signed(v: int) -> int:
    """Interpret an unsigned varint as int64."""
    return v - (1 << 64) if v >= (1 << 63) else v


# --------------------------------------------------------------------------
# field helpers
# --------------------------------------------------------------------------

def find(msg: Sequence, field: int, wire: int | None = None):
    for item in msg:
        if item[0] == field and (wire is None or item[1] == wire):
            return item
    return None


def get_varint(msg, field, default=None):
    item = find(msg, field, 0)
    return item[2] if item else default


def set_varint(msg, field, value) -> None:
    item = find(msg, field, 0)
    if item:
        item[2] = value
    else:
        msg.append([field, 0, value])


def get_fixed64(msg, field, default=None):
    item = find(msg, field, 1)
    return struct.unpack("<d", item[2])[0] if item else default


def set_fixed64(msg, field, value) -> None:
    item = find(msg, field, 1)
    if item:
        item[2] = struct.pack("<d", value)
    else:
        msg.append([field, 1, struct.pack("<d", value)])


def get_indexed(msg, field, index, default=None):
    item = find(msg, field, 2)
    if not item:
        return default
    for sub in parse(item[2]):
        if sub[0] == index and sub[1] == 1:
            return struct.unpack("<d", sub[2])[0]
    return default


def set_indexed(msg, field, index, value) -> None:
    item = find(msg, field, 2)
    if not item:
        item = [field, 2, b""]
        msg.append(item)
    sub = parse(item[2])
    for entry in sub:
        if entry[0] == index and entry[1] == 1:
            entry[2] = struct.pack("<d", value)
            break
    else:
        sub.append([index, 1, struct.pack("<d", value)])
    item[2] = enc(sub)


def get_indexed_all(msg, field) -> dict[int, float]:
    item = find(msg, field, 2)
    if not item:
        return {}
    return {
        e[0]: struct.unpack("<d", e[2])[0]
        for e in parse(item[2])
        if e[1] == 1
    }


def set_indexed_many(msg, field, values: dict[int, float]) -> None:
    for index, value in values.items():
        set_indexed(msg, field, index, value)


# --------------------------------------------------------------------------
# DeviceProfile
# --------------------------------------------------------------------------

class DeviceProfile:
    """Binary ARCore device profile with typed accessors."""

    def __init__(self, data: bytes):
        self.data = data
        self.top = parse(data)

    # -- cameras ---------------------------------------------------------
    def cameras(self) -> list[list]:
        return [item for item in self.top if item[0] == 1 and item[1] == 2]

    def camera(self, cam_id: str = "0") -> list:
        for item in self.cameras():
            cam = parse(item[2])
            if get_camera_name(cam) == cam_id:
                return cam
        raise KeyError(f"camera {cam_id!r} not found")

    def _store_camera(self, cam: list) -> None:
        for item in self.top:
            if item[0] == 1 and item[1] == 2:
                parsed = parse(item[2])
                name_item = find(parsed, 1, 2)
                name = name_item[2].decode("latin1", "replace") if name_item else ""
                if name == get_camera_name(cam):
                    item[2] = enc(cam)
                    return
        raise KeyError("camera not found")

    # -- IMU -------------------------------------------------------------
    def imu(self) -> list | None:
        for item in self.top:
            if item[0] != 2 or item[1] != 2:
                continue
            entry = parse(item[2])
            key = get_varint(entry, 1)
            value = find(entry, 2, 2)
            if key == 100 and value:
                return parse(value[2])
        return None

    def _store_imu(self, imu: list) -> None:
        for item in self.top:
            if item[0] != 2 or item[1] != 2:
                continue
            entry = parse(item[2])
            if get_varint(entry, 1) == 100:
                value = find(entry, 2, 2)
                value[2] = enc(imu)
                item[2] = enc(entry)
                return
        raise KeyError("IMU block not found")

    # -- high level ------------------------------------------------------
    def intrinsics(self, cam_id: str = "0") -> dict:
        cam = self.camera(cam_id)
        return {
            "fx": get_indexed(cam, 8, 1),
            "fy": get_indexed(cam, 8, 2),
            "cx": get_indexed(cam, 9, 1),
            "cy": get_indexed(cam, 9, 2),
            "k0": get_indexed(cam, 11, 1),
            "k1": get_indexed(cam, 11, 2),
            "k2": get_indexed(cam, 11, 3),
        }

    def set_intrinsics(self, fx=None, fy=None, cx=None, cy=None,
                       k0=None, k1=None, k2=None, cam_id: str = "0") -> None:
        cam = self.camera(cam_id)
        for field, index, value in (
            (8, 1, fx), (8, 2, fy), (9, 1, cx), (9, 2, cy),
            (11, 1, k0), (11, 2, k1), (11, 3, k2),
        ):
            if value is not None:
                set_indexed(cam, field, index, float(value))
        self._store_camera(cam)

    def camera_flags(self, cam_id: str = "0") -> dict:
        cam = self.camera(cam_id)
        return {
            "width": get_varint(cam, 6),
            "height": get_varint(cam, 7),
            "rs": get_varint(cam, 3),
            "tsa": signed(get_varint(cam, 5)) if get_varint(cam, 5) is not None else None,
            "tb": get_varint(cam, 17),
            "rss": get_varint(cam, 18),
            "depth": get_varint(cam, 27),
        }

    def set_camera_flags(self, rs=None, tsa=None, tb=None, rss=None,
                         depth=None, cam_id: str = "0") -> None:
        cam = self.camera(cam_id)
        for field, value in ((3, rs), (5, tsa), (17, tb), (18, rss), (27, depth)):
            if value is not None:
                set_varint(cam, field, int(value))
        self._store_camera(cam)

    def imu_values(self) -> dict:
        imu = self.imu()
        if imu is None:
            return {}
        return {
            "gyro_scale": get_indexed_all(imu, 1),
            "gyro_misalign": get_indexed_all(imu, 2),
            "accel_scale": get_indexed_all(imu, 3),
            "accel_misalign": get_indexed_all(imu, 4),
            "orientation": get_indexed_all(imu, 5),
            "gyro_noise": get_fixed64(imu, 6),
            "gyro_random_walk": get_fixed64(imu, 7),
            "accel_noise": get_fixed64(imu, 8),
            "accel_random_walk": get_fixed64(imu, 9),
            "gyro_bias": get_indexed_all(imu, 10),
            "accel_bias": get_indexed_all(imu, 11),
        }

    def set_imu(self, gyro_scale=None, accel_scale=None, gyro_bias=None,
                accel_bias=None, gyro_noise=None, gyro_random_walk=None,
                accel_noise=None, accel_random_walk=None) -> None:
        imu = self.imu()
        if imu is None:
            raise KeyError("IMU block not found")
        if gyro_scale is not None:
            set_indexed_many(imu, 1, dict(enumerate(gyro_scale, 1)))
        if accel_scale is not None:
            set_indexed_many(imu, 3, dict(enumerate(accel_scale, 1)))
        if gyro_bias is not None:
            set_indexed_many(imu, 10, dict(enumerate(gyro_bias, 1)))
        if accel_bias is not None:
            set_indexed_many(imu, 11, dict(enumerate(accel_bias, 1)))
        for field, value in (
            (6, gyro_noise), (7, gyro_random_walk),
            (8, accel_noise), (9, accel_random_walk),
        ):
            if value is not None:
                set_fixed64(imu, field, float(value))
        self._store_imu(imu)

    def to_bytes(self) -> bytes:
        return enc(self.top)

    def dump(self) -> dict:
        return {
            "intrinsics": self.intrinsics(),
            "camera": self.camera_flags(),
            "imu": self.imu_values(),
            "cameras": [get_camera_name(parse(item[2])) for item in self.cameras()],
        }


def get_camera_name(cam: Sequence) -> str:
    item = find(cam, 1, 2)
    return item[2].decode("latin1", "replace") if item else ""


# --------------------------------------------------------------------------
# profiles.toc
# --------------------------------------------------------------------------

def parse_toc(data: bytes) -> dict[str, dict[int, int]]:
    """Return ``{key_prefix: {1: dat_index, 2: offset, 3: length}}``."""
    out: dict[str, dict[int, int]] = {}
    i = 0
    while i < len(data):
        tag, i = read_varint(data, i)
        if tag != 0x0A:
            break
        length, i = read_varint(data, i)
        entry = parse(data[i:i + length])
        i += length
        key_item = find(entry, 1, 2)
        value_item = find(entry, 2, 2)
        if not key_item or not value_item:
            continue
        raw = key_item[2]
        if raw[:1] == b"\x68":           # key packed as uint64 (field 13)
            value, _ = read_varint(raw, 1)
            key = struct.pack("<Q", value).decode("latin1")
        else:
            key = raw.decode("latin1")
        values = {}
        for sub in parse(value_item[2]):
            if sub[1] == 0:
                values[sub[0]] = sub[2]
        out[key.split(":")[0]] = values
    return out


def toc_entry(key: str, length: int, dat_index: int = 0, offset: int = 0) -> bytes:
    value = (
        varint((1 << 3) | 0) + varint(dat_index)
        + varint((2 << 3) | 0) + varint(offset)
        + varint((3 << 3) | 0) + varint(length)
    )
    body = (
        varint((1 << 3) | 2) + varint(len(key)) + key.encode()
        + varint((2 << 3) | 2) + varint(len(value)) + value
    )
    return varint((1 << 3) | 2) + varint(len(body)) + body


def build_toc(length: int, keys: Sequence[str], suffixes: Sequence[str] | None = None) -> bytes:
    out = bytearray()
    for key in keys:
        for suffix in (suffixes or SUFFIXES):
            out += toc_entry(f"{key}:{suffix}", length)
    return bytes(out)


def build_zip(profile: bytes, keys: Sequence[str]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("profiles.toc", build_toc(len(profile), keys))
        zf.writestr("profiles_00000.dat", profile)
    return buf.getvalue()


def read_profile(path: str) -> DeviceProfile:
    """Read a .pb profile or the first profile inside a .zip."""
    if path.endswith(".zip"):
        with zipfile.ZipFile(path) as zf:
            dat = sorted(n for n in zf.namelist() if n.endswith(".dat"))[0]
            return DeviceProfile(zf.read(dat))
    with open(path, "rb") as fh:
        return DeviceProfile(fh.read())


# --------------------------------------------------------------------------
# extraction from an unpacked ARCore APK
# --------------------------------------------------------------------------

def extract_from_assets(assets_dir: str, devices: Sequence[str]) -> tuple[bytes, str]:
    """Pull a profile for one of ``devices`` out of ARCore's packed profiles.

    ``assets_dir`` must be the unpacked ``assets/packed_profiles`` folder of the
    ARCore APK (``profile.toc`` + ``profiles_NNNNN.dat``).
    """
    with open(os.path.join(assets_dir, "profiles.toc"), "rb") as fh:
        toc = parse_toc(fh.read())
    keys = obf_many(devices)
    for device, key in zip(devices, keys):
        entry = toc.get(key)
        if entry is None:
            continue
        dat_path = os.path.join(assets_dir, f"profiles_{entry.get(1, 0):05d}.dat")
        with open(dat_path, "rb") as fh:
            blob = fh.read()
        offset = entry.get(2, 0)
        length = entry.get(3, 0)
        return blob[offset:offset + length], device
    raise KeyError(
        "no profile for " + ", ".join(devices) + " (keys: " + ", ".join(keys) + ")"
    )
