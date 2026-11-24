import io
import os
import struct
import zipfile

import pytest

from arcore_profile import core
from arcore_profile import DeviceProfile


def _cam(**kw):
    """Build a minimal camera sub-message."""
    def dbl(index, value):
        return [index, 1, struct.pack("<d", value)]
    msg = [
        [1, 2, b"0"],
        [6, 0, 640],
        [7, 0, 480],
        [8, 2, core.enc([dbl(1, kw.get("fx", 500.0)), dbl(2, kw.get("fy", 500.0))])],
        [9, 2, core.enc([dbl(1, kw.get("cx", 320.0)), dbl(2, kw.get("cy", 240.0))])],
        [11, 2, core.enc([dbl(1, 0.0), dbl(2, 0.0), dbl(3, 0.0)])],
        [3, 0, 30000000],
        [5, 0, (1 << 64) - 20000000],
        [17, 0, 2],
        [18, 0, 1],
        [27, 0, 2],
    ]
    return msg


def _imu():
    def vec(values):
        return core.enc([[i + 1, 1, struct.pack("<d", v)] for i, v in enumerate(values)])
    msg = [
        [1, 2, vec([1.0, 1.0, 1.0])],
        [2, 2, vec([0.0, 0.0, 0.0])],
        [3, 2, vec([1.0, 1.0, 1.0])],
        [4, 2, vec([0.0, 0.0, 0.0])],
        [5, 2, vec([0.0, 0.0, 0.0, 1.0])],
        [6, 1, struct.pack("<d", 0.0005)],
        [7, 1, struct.pack("<d", 0.0001)],
        [8, 1, struct.pack("<d", 0.005)],
        [9, 1, struct.pack("<d", 0.01)],
        [10, 2, vec([0.001, -0.002, 0.003])],
        [11, 2, vec([0.01, -0.02, 0.03])],
    ]
    return msg


def synthetic_profile() -> bytes:
    cam = _cam()
    imu_entry = core.enc([[1, 0, 100], [2, 2, core.enc(_imu())]])
    return core.enc([[1, 2, core.enc(cam)], [2, 2, imu_entry]])


def test_obf_known_keys():
    # Values verified against the ARCore 1.57 packed profile database.
    assert core.obf("flame") == "Ub5OOR"
    assert core.obf("marble") == "fJKExA"
    assert core.obf("chopin") == "Wg0hne"
    assert core.obf("vayu") == "pUVpQN"
    assert core.obf("duchamp") == "pADuJF"
    assert core.obf("HWMAR") == "nJH8dB"
    assert core.obf("HWMAR_SFP_accelerometer-bmi160_SUNNY_SFP") == "HX2bDI"
    assert core.obf("MAR-LX1MRU/HWMAR") == "Q75qu9"


def test_protobuf_roundtrip():
    msg = [[1, 0, 42], [2, 2, b"hello"], [3, 1, struct.pack("<d", 1.5)]]
    assert core.parse(core.enc(msg)) == msg


def test_negative_varint_is_int64():
    payload = core.varint(-20000000)
    value, _ = core.read_varint(payload, 0)
    assert core.signed(value) == -20000000


def test_profile_intrinsics_roundtrip():
    profile = DeviceProfile(synthetic_profile())
    intr = profile.intrinsics()
    assert intr["fx"] == 500.0 and intr["cx"] == 320.0
    profile.set_intrinsics(fx=410.8, fy=410.8, cx=321.0, cy=239.0, k0=0.1)
    again = DeviceProfile(profile.to_bytes()).intrinsics()
    assert again["fx"] == pytest.approx(410.8)
    assert again["cy"] == 239.0
    assert again["k0"] == pytest.approx(0.1)


def test_profile_imu_roundtrip():
    profile = DeviceProfile(synthetic_profile())
    imu = profile.imu_values()
    assert imu["gyro_scale"][1] == 1.0
    assert imu["gyro_noise"] == pytest.approx(0.0005)
    profile.set_imu(gyro_scale=[1.02, 1.01, 1.0], gyro_noise=0.0007,
                    gyro_bias=[0.1, 0.2, 0.3])
    again = DeviceProfile(profile.to_bytes()).imu_values()
    assert again["gyro_scale"][1] == pytest.approx(1.02)
    assert again["gyro_noise"] == pytest.approx(0.0007)
    assert again["gyro_bias"][3] == pytest.approx(0.3)


def test_camera_flags():
    profile = DeviceProfile(synthetic_profile())
    flags = profile.camera_flags()
    assert flags["rs"] == 30000000
    assert flags["tsa"] == -20000000
    assert flags["tb"] == 2
    profile.set_camera_flags(rs=26763333, tsa=-20000000, tb=2)
    again = DeviceProfile(profile.to_bytes()).camera_flags()
    assert again["rs"] == 26763333
    assert again["tsa"] == -20000000


def test_toc_and_zip():
    blob = synthetic_profile()
    keys = [core.obf("HWMAR")]
    data = core.build_zip(blob, keys)

    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        toc = core.parse_toc(zf.read("profiles.toc"))
        assert keys[0] in toc
        assert toc[keys[0]][3] == len(blob)
        assert zf.read("profiles_00000.dat") == blob


def test_extract_from_assets(tmp_path):
    blob = synthetic_profile()
    keys = [core.obf("HWMAR")]
    (tmp_path / "profiles.toc").write_bytes(core.build_toc(len(blob), keys))
    (tmp_path / "profiles_00000.dat").write_bytes(blob)
    out, device = core.extract_from_assets(str(tmp_path), ["HWMAR"])
    assert device == "HWMAR"
    assert out == blob
    with pytest.raises(KeyError):
        core.extract_from_assets(str(tmp_path), ["not-a-device"])
