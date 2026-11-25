#!/usr/bin/env python3
"""Patch an APK with the AprilTag shim and optionally install it.

The script replaces ``lib/<abi>/libarcore_sdk_c.so`` with the shim, keeps the
real ARCore library next to it as ``libarcore_sdk_c_real.so``, strips old
signatures, aligns and signs the result.

Examples
--------
    # build the shim first, then patch and install
    python3 tools/shim/patch_apk.py app.apk --build-shim --install

    # an already shimmed APK: extract its _real library automatically
    python3 tools/shim/patch_apk.py app-shim.apk -o app-shim-new.apk

    # stable key (must match the currently installed app for adb install -r)
    python3 tools/shim/patch_apk.py app.apk --ks game/test.keystore \
        --key-alias test --install --serial 192.168.0.159:5555

The APK must already contain ``lib/<abi>/libarcore_sdk_c.so``.  For an original
app that is the real ARCore SDK library; for an already shimmed APK the real
library is under ``libarcore_sdk_c_real.so`` and is preferred.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOL_DIR = Path(__file__).resolve().parent
DEFAULT_SHIM = TOOL_DIR / "libarcore_sdk_c.so"
DEFAULT_KS = ROOT / "android" / "debug.keystore"

SIG_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")
SO_PATTERN = re.compile(r"^lib/([^/]+)/libarcore_sdk_c(?:_real)?\.so$")

ELF_MACHINES = {
    "armeabi-v7a": (1, 40),
    "arm64-v8a": (2, 183),
    "x86": (1, 3),
    "x86_64": (2, 62),
}


def run(cmd: list[str], cwd: Path | None = None, what: str = "") -> None:
    if what:
        print("+", what)
    subprocess.run(cmd, cwd=cwd, check=True)


def elf_machine(data: bytes) -> tuple[int, int]:
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise SystemExit("not an ELF shared library")
    ei_class = data[4]
    little = data[5] != 2
    machine = int.from_bytes(data[18:20], "little" if little else "big")
    return ei_class, machine


def check_abi(path: Path, abi: str, what: str) -> None:
    expected = ELF_MACHINES.get(abi)
    if expected is None:
        raise SystemExit(f"unknown ABI {abi!r}; known: {', '.join(ELF_MACHINES)}")
    actual = elf_machine(path.read_bytes()[:64])
    if actual != expected:
        raise SystemExit(
            f"{what} {path} is not {abi} (ELF class/machine {actual}, expected {expected})"
        )


def find_build_tools() -> Path:
    candidates = []
    if os.environ.get("BUILD_TOOLS"):
        candidates.append(Path(os.environ["BUILD_TOOLS"]))
    sdk_root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    sdks = [Path(sdk_root)] if sdk_root else []
    sdks.extend([Path("/opt/android-sdk"), Path.home() / "Android" / "Sdk"])
    for sdk in sdks:
        if not sdk.is_dir():
            continue
        candidates.append(sdk / "build-tools" / "35.0.0")
        bt = sdk / "build-tools"
        if bt.is_dir():
            candidates.extend(sorted(bt.iterdir(), reverse=True))
    for path in candidates:
        if (path / "zipalign").is_file() and (path / "apksigner").is_file():
            return path
    found = shutil.which("zipalign")
    if found:
        return Path(found).parent
    raise SystemExit("Android build-tools not found (set BUILD_TOOLS or ANDROID_SDK_ROOT)")


def find_tool(name: str, override: str | None = None) -> str:
    if override:
        return override
    found = shutil.which(name)
    if not found:
        raise SystemExit(f"{name} not found in PATH")
    return found


def abis_in_apk(zf: zipfile.ZipFile) -> list[str]:
    abis = set()
    for name in zf.namelist():
        m = SO_PATTERN.match(name)
        if m:
            abis.add(m.group(1))
    return sorted(abis)


def read_entry(zf: zipfile.ZipFile, name: str) -> bytes | None:
    try:
        return zf.read(name)
    except KeyError:
        return None


def looks_like_shim(data: bytes) -> bool:
    return b"libarcore_sdk_c_real.so" in data or b"ARCORE_SHIM" in data


def extract_real_lib(zf: zipfile.ZipFile, abi: str, work: Path,
                     explicit: Path | None) -> Path:
    if explicit is not None:
        data = explicit.read_bytes()
        if looks_like_shim(data):
            raise SystemExit(
                f"{explicit} looks like a shim, not the real ARCore library"
            )
        target = work / f"real-{abi}.so"
        target.write_bytes(data)
        return target

    names = [
        f"lib/{abi}/libarcore_sdk_c_real.so",
        f"jni/{abi}/libarcore_sdk_c_real.so",
        f"lib/{abi}/libarcore_sdk_c.so",
        f"jni/{abi}/libarcore_sdk_c.so",
    ]
    for name in names:
        data = read_entry(zf, name)
        if data is None:
            continue
        if name.endswith("libarcore_sdk_c.so") and looks_like_shim(data):
            raise SystemExit(
                f"{name} looks like a shim, not the real ARCore library. "
                "Use an original APK or pass --real-lib."
            )
        target = work / f"real-{abi}.so"
        target.write_bytes(data)
        return target
    raise SystemExit(f"no ARCore C library found for {abi} in the source APK")


def strip_signature(name: str) -> bool:
    upper = name.upper()
    if upper == "META-INF/MANIFEST.MF":
        return True
    return upper.startswith("META-INF/") and upper.endswith(SIG_SUFFIXES)


def write_patched_apk(source: Path, out: Path, patched: dict[str, tuple[Path, Path]]) -> None:
    # patched: abi -> (shim, real)
    replaced = set()
    for abi in patched:
        replaced.add(f"lib/{abi}/libarcore_sdk_c.so")
        replaced.add(f"lib/{abi}/libarcore_sdk_c_real.so")

    with zipfile.ZipFile(source) as zin, zipfile.ZipFile(out, "w") as zout:
        for info in zin.infolist():
            if info.filename in replaced or strip_signature(info.filename):
                continue
            data = zin.read(info)
            new_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            # Preserve the source compression method.  Some games keep their
            # native libraries uncompressed (extractNativeLibs=false); others
            # compress them.  Changing that would either break loading or blow
            # up the APK size.
            new_info.compress_type = info.compress_type
            new_info.external_attr = info.external_attr
            zout.writestr(new_info, data)

        for abi, (shim, real) in patched.items():
            for name, path in (
                (f"lib/{abi}/libarcore_sdk_c.so", shim),
                (f"lib/{abi}/libarcore_sdk_c_real.so", real),
            ):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_STORED
                info.external_attr = 0o644 << 16
                zout.writestr(info, path.read_bytes())


def ensure_keystore(ks: Path, alias: str, ks_pass: str, key_pass: str) -> None:
    if ks.is_file():
        return
    keytool = find_tool("keytool")
    ks.parent.mkdir(parents=True, exist_ok=True)
    print(f"+ creating debug keystore {ks}")
    run([
        keytool, "-genkeypair", "-keystore", str(ks), "-alias", alias,
        "-storepass", ks_pass, "-keypass", key_pass,
        "-dname", "CN=ARCore Toolkit", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000",
    ])


def select_device(adb: str, serial: str | None) -> str:
    if serial:
        return serial
    try:
        output = subprocess.check_output([adb, "devices"], text=True)
    except subprocess.CalledProcessError as exc:
        raise SystemExit(f"adb devices failed: {exc}") from exc
    devices = [
        line.split("\t")[0]
        for line in output.splitlines()
        if "\tdevice" in line and not line.startswith("List of")
    ]
    if not devices:
        raise SystemExit("no adb device connected")
    if len(devices) > 1:
        raise SystemExit("multiple adb devices; pass --serial (" + ", ".join(devices) + ")")
    return devices[0]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Replace libarcore_sdk_c.so in an APK with the AprilTag shim and sign it."
    )
    parser.add_argument("source", type=Path, help="source .apk")
    parser.add_argument("-o", "--output", type=Path, help="output .apk")
    parser.add_argument("--shim", type=Path, default=DEFAULT_SHIM,
                        help=f"shim library (default: {DEFAULT_SHIM})")
    parser.add_argument("--real-lib", type=Path,
                        help="real ARCore SDK library (default: extract from the APK)")
    parser.add_argument("--abi", action="append", default=[],
                        help="ABI to patch (repeatable; default: auto, prefers arm64-v8a)")
    parser.add_argument("--build-shim", action="store_true",
                        help="run tools/shim/build.sh before patching")
    parser.add_argument("--ks", type=Path, default=DEFAULT_KS, help="signing keystore")
    parser.add_argument("--ks-pass", default="android")
    parser.add_argument("--key-pass", default="android")
    parser.add_argument("--key-alias", default="debug")
    parser.add_argument("--patchelf", default=None, help="patchelf binary")
    parser.add_argument("--build-tools", default=None, help="Android build-tools directory")
    parser.add_argument("--adb", default=None, help="adb binary")
    parser.add_argument("--serial", default=None, help="adb device serial")
    parser.add_argument("--install", action="store_true", help="install with adb install -r")
    parser.add_argument("--no-verify", action="store_true", help="skip apksigner verify")
    args = parser.parse_args(argv)

    source = args.source.resolve()
    if not source.is_file():
        raise SystemExit(f"source APK not found: {source}")
    if source.suffix.lower() != ".apk":
        raise SystemExit("source must be a .apk")

    if args.build_shim:
        run([str(TOOL_DIR / "build.sh")], cwd=TOOL_DIR, what="build shim")
    shim = args.shim.resolve()
    if not shim.is_file():
        raise SystemExit(f"shim not found: {shim} (use --build-shim or --shim)")

    real_explicit = args.real_lib.resolve() if args.real_lib else None
    if real_explicit is not None and not real_explicit.is_file():
        raise SystemExit(f"real library not found: {real_explicit}")

    bt = Path(args.build_tools).resolve() if args.build_tools else find_build_tools()
    zipalign = bt / "zipalign"
    apksigner = bt / "apksigner"
    for tool in (zipalign, apksigner):
        if not tool.is_file():
            raise SystemExit(f"missing {tool}")

    patchelf = find_tool("patchelf", args.patchelf)
    adb = find_tool("adb", args.adb) if args.install else None

    out = args.output.resolve() if args.output else source.with_name(source.stem + "-shim.apk")
    if out == source:
        raise SystemExit("output must be different from the source APK")

    with zipfile.ZipFile(source) as zf:
        detected = abis_in_apk(zf)
        if not detected:
            raise SystemExit("no lib/<abi>/libarcore_sdk_c.so in the source APK")
        abis = args.abi or (["arm64-v8a"] if "arm64-v8a" in detected else [detected[0]])
        for abi in abis:
            if abi not in detected:
                raise SystemExit(f"source APK has no {abi} ARCore library")
        print("ABIs in APK:", ", ".join(detected))
        print("patching:", ", ".join(abis))

        for abi in abis:
            check_abi(shim, abi, "shim")
            if real_explicit is not None:
                check_abi(real_explicit, abi, "real library")

        with tempfile.TemporaryDirectory(prefix="arcore-patch-") as tmp:
            work = Path(tmp)
            patched: dict[str, tuple[Path, Path]] = {}
            for abi in abis:
                real = extract_real_lib(zf, abi, work, real_explicit)
                check_abi(real, abi, "real library")
                run([patchelf, "--set-soname", "libarcore_sdk_c_real.so", str(real)],
                    what=f"patch SONAME ({abi})")
                patched[abi] = (shim, real)

            unsigned = work / "unsigned.apk"
            aligned = work / "aligned.apk"
            print(f"+ writing {unsigned.name}")
            write_patched_apk(source, unsigned, patched)
            run([str(zipalign), "-p", "-f", "4", str(unsigned), str(aligned)],
                what="zipalign")

            mirror_ks = args.ks
            if not mirror_ks.is_absolute():
                mirror_ks = (Path.cwd() / mirror_ks).resolve()
            ensure_keystore(mirror_ks, args.key_alias, args.ks_pass, args.key_pass)
            out.parent.mkdir(parents=True, exist_ok=True)
            run([
                str(apksigner), "sign",
                "--ks", str(mirror_ks),
                "--ks-key-alias", args.key_alias,
                "--ks-pass", f"pass:{args.ks_pass}",
                "--key-pass", f"pass:{args.key_pass}",
                "--out", str(out), str(aligned),
            ], what="apksigner")

    if not args.no_verify:
        run([str(apksigner), "verify", "--print-certs", str(out)], what="verify APK")

    print(f"\npatched APK: {out}")
    if args.install:
        serial = select_device(adb, args.serial)
        print(f"+ adb -s {serial} install -r {out}")
        try:
            subprocess.run([adb, "-s", serial, "install", "-r", str(out)], check=True)
        except subprocess.CalledProcessError:
            raise SystemExit(
                "install failed. If it is a signature mismatch, use --ks/--key-alias "
                "for the key the app is currently signed with, or uninstall the app first."
            )
    else:
        print(f"install with: adb install -r {out}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
