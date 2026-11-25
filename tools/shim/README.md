# ARCore C API shim

| File | Purpose |
|---|---|
| `gen_shim.py` | generates `shim.c`: forwarders for every ARCore symbol an app imports |
| `postprocess.py` | patches the generated file (dladdr lookup, internal symbols) |
| `overrides.inc` | ARCore API overrides (fake planes, anchors, poses, capture hook) |
| `marker_tracking.inc` | asynchronous AprilTag tracking engine: single/hybrid multi-tag detection, sheet-frame fusion, two-pose ambiguity resolution, filtering and prediction |
| `passthrough.inc` | empty variant used for a pure pass-through shim |
| `internal.S` | trampoline for `ArSession_createWithFeaturesAndTrace` |
| `vers.map` | exports only `Ar*` |
| `arcore_imports.txt` | symbols imported by the target app (94 for the app this was built for) |
| `arcore_c_api.h` | official ARCore C API header, used only to derive signatures |
| `build.sh` | builds `libarcore_sdk_c.so` |
| `extract-real-lib.sh` | extracts the real SDK library and renames its SONAME |
| `package_apk.sh` | shell script: swaps the library inside an APK and signs it |
| `patch_apk.py` | Python patcher: swaps the library, signs the APK, optional adb install |

`arcore_c_api.h` is Google's public ARCore SDK header (Apache-2.0 as part of the
SDK distribution).  Everything else in this folder is first-party code.

See [../../docs/06-shim.md](../../docs/06-shim.md) for the architecture and the
list of overrides.
