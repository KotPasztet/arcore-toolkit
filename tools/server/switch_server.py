#!/usr/bin/env python3
"""Profile switch server: build ARCore profile ZIPs on request.

ARCore can download a profile database at runtime through its exported
``ProfileDownloadActivity``.  The URL must end with ``major.minor.patch`` and
serve ``Content-Type: application/zip``.  This server builds such a ZIP from
local profile files, so a profile can be swapped from the phone without root.

Endpoints
---------
GET /arcore/switch/<name>/<version>.zip[?dev=<device>[,<device>...]|?key=<key>[,<key>...]]
GET /arcore/switch/custom/<version>.zip?base=<name>&fx=..&fy=..&cx=..&cy=..
                                     &k0=..&k1=..&k2=..&rs=..&tsa=..&tb=..
                                     &rss=..&depth=..[&dev=..|&key=..]
GET /arcore/apk[.apk]                 optional ARCore runtime APK for the app

Configuration (JSON)
--------------------
{
  "profiles": {
    "chopin_patched": "profiles/chopinpatched_*.pb",
    "hwmar": "profiles/hwmar_*.pb"
  },
  "keys": {
    "hwmar": ["HWMAR", "HWMAR_SFP_accelerometer-bmi160_SUNNY_SFP"]
  },
  "arcore_apk": "arcore.apk",
  "default_key": "pADuJF"
}

``keys`` is optional: it pins a named profile to specific device names (ARCore
probes several variants of the device string, so a list helps).  Otherwise use
``?dev=`` or ``?key=`` in the URL.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import socketserver
import sys
import zipfile
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlparse

try:
    from arcore_profile import catalog as profile_catalog
    from arcore_profile import core
except ImportError:  # running from a checkout without `pip install -e .`
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))
    from arcore_profile import catalog as profile_catalog
    from arcore_profile import core

DEFAULTS = {
    "fx": 464.56,
    "fy": 464.56,
    "cx": 320.0,
    "cy": 240.0,
    "k0": 0.001,
    "k1": 0.0,
    "k2": 0.0,
    "rs": 26763333,
    "tsa": -20000000,
    "tb": 2,
    "rss": 1,
    "depth": 2,
}


def load_config(path: str) -> dict:
    if not path:
        return {"profiles": {}, "keys": {}, "arcore_apk": "", "default_key": "pADuJF"}
    with open(path, encoding="utf-8") as fh:
        cfg = json.load(fh)
    cfg.setdefault("profiles", {})
    cfg.setdefault("keys", {})
    cfg.setdefault("arcore_apk", "")
    cfg.setdefault("default_key", "pADuJF")
    return cfg


class ProfileStore:
    def __init__(self, directory: str, config: dict):
        self.directory = os.path.abspath(directory)
        self.config = config

    def find(self, name: str) -> str | None:
        pattern = self.config["profiles"].get(name)
        if not pattern:
            return None
        matches = sorted(glob.glob(os.path.join(self.directory, pattern)))
        return matches[0] if matches else None

    def bytes_for(self, name: str) -> bytes | None:
        path = self.find(name)
        if not path:
            return None
        with open(path, "rb") as fh:
            return fh.read()

    def arcore_apk_path(self) -> str | None:
        value = (self.config.get("arcore_apk") or "").strip()
        if not value:
            return None
        if not os.path.isabs(value):
            value = os.path.join(self.directory, value)
        return value if os.path.isfile(value) else None

    def catalog(self) -> list[dict]:
        out = []
        for name in self.config["profiles"]:
            path = self.find(name)
            if not path:
                continue
            info = profile_catalog.profile_info(name, path)
            if info:
                out.append(info)
        return out

    def keys_for(self, name: str, devices, keys) -> list[str]:
        if keys:
            return keys
        if devices:
            return core.obf_many(devices)
        pinned = self.config["keys"].get(name)
        if pinned:
            return core.obf_many(pinned)
        return [self.config.get("default_key", "pADuJF")]

    def build_custom(self, query: dict, name: str, devices=None,
                     keys=None) -> tuple[bytes | None, list[str]]:
        base_name = query.get("base", name if name in self.config["profiles"] else None)
        if not base_name:
            return None, []
        blob = self.bytes_for(base_name)
        if blob is None:
            return None, []
        profile = core.DeviceProfile(blob)

        def num(key, cast=float):
            value = query.get(key)
            return cast(value) if value not in (None, "") else None

        profile.set_intrinsics(
            fx=num("fx"), fy=num("fy"), cx=num("cx"), cy=num("cy"),
            k0=num("k0"), k1=num("k1"), k2=num("k2"),
        )
        profile.set_camera_flags(
            rs=num("rs", int), tsa=num("tsa", int), tb=num("tb", int),
            rss=num("rss", int), depth=num("depth", int),
        )
        imu_scales = {}
        for key in ("gsx", "gsy", "gsz"):
            value = num(key)
            if value is not None:
                imu_scales[key] = value
        if imu_scales:
            imu = profile.imu_values()
            if imu:
                gyro = [imu["gyro_scale"].get(i, 1.0) for i in (1, 2, 3)]
                for idx, key in enumerate(("gsx", "gsy", "gsz")):
                    if key in imu_scales:
                        gyro[idx] = imu_scales[key]
                profile.set_imu(
                    gyro_scale=gyro,
                    gyro_noise=num("gn"),
                    accel_noise=num("an"),
                )
        return profile.to_bytes(), self.keys_for(base_name, devices, keys)


class Handler(BaseHTTPRequestHandler):
    store: ProfileStore = None  # set in main()
    verbose = True

    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _serve(self, send_body: bool = True) -> None:
        parsed = urlparse(self.path)
        if parsed.path in ("/", "/healthz"):
            self._json(200, {"status": "ok"})
            return
        if parsed.path in ("/arcore/profiles", "/arcore/switch/profiles"):
            self._json(200, {
                "profiles": self.store.catalog(),
                "default_key": self.store.config.get("default_key", "pADuJF"),
            })
            return
        if parsed.path in ("/arcore/apk", "/arcore/apk/latest", "/arcore/apk/latest.apk"):
            path = self.store.arcore_apk_path()
            if not path:
                self._json(404, {"error": "arcore_apk is not configured on the server"})
                return
            size = os.path.getsize(path)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", 'attachment; filename="arcore.apk"')
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            if send_body:
                with open(path, "rb") as fh:
                    shutil.copyfileobj(fh, self.wfile)
            if self.verbose:
                print(f"served ARCore APK ({size}B)", flush=True)
            return
        match = re.match(
            r"^/arcore/switch/([A-Za-z0-9_.-]+)/([0-9]+(?:\.[0-9]+){1,3})\.zip$",
            parsed.path,
        )
        if not match:
            self._json(404, {"error": "bad path"})
            return
        name, version = match.group(1), match.group(2)
        query = {k: v[0] for k, v in parse_qs(parsed.query).items()}
        devices = [d for d in (query.get("dev") or "").split(",") if d]
        raw_keys = [k for k in (query.get("key") or "").split(",") if k]

        if "base" in query or name == "custom":
            blob, keys = self.store.build_custom(query, name, devices, raw_keys)
        else:
            blob = self.store.bytes_for(name)
            keys = self.store.keys_for(name, devices, raw_keys) if blob else []
        if blob is None:
            self._json(404, {"error": f"unknown profile {name!r}"})
            return
        if not keys:
            self._json(400, {"error": "cannot determine the device key; pass ?dev= or ?key="})
            return

        data = core.build_zip(blob, keys)
        self.send_response(200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if send_body:
            self.wfile.write(data)
        if self.verbose:
            print(f"served {name} as {version} for {','.join(keys)} "
                  f"({len(blob)}B profile)", flush=True)

    def do_GET(self) -> None:  # noqa: N802
        self._serve(True)

    def do_HEAD(self) -> None:  # noqa: N802
        self._serve(False)

    def log_message(self, fmt, *args) -> None:
        if self.verbose:
            print("%s - %s" % (self.address_string(), fmt % args), flush=True)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="ARCore profile switch server")
    parser.add_argument("--profiles-dir", default="profiles",
                        help="directory with .pb profile files")
    parser.add_argument("--config", default="profiles.json",
                        help="JSON config mapping names to glob patterns")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    config = load_config(args.config)
    Handler.store = ProfileStore(args.profiles_dir, config)
    Handler.verbose = not args.quiet

    socketserver.ThreadingTCPServer.allow_reuse_address = True
    with socketserver.ThreadingTCPServer((args.host, args.port), Handler) as httpd:
        print(f"profile switch server on http://{args.host}:{args.port}", flush=True)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
