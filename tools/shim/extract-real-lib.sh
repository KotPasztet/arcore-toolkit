#!/usr/bin/env bash
# Extract the real ARCore SDK C library and rename its SONAME.
#
#   ./extract-real-lib.sh <arcore-sdk.aar | app.apk> [out.so]
#
# Sources that ship libarcore_sdk_c.so:
#   * the ARCore SDK AAR (com.google.ar:core, path jni/<abi>/libarcore_sdk_c.so);
#   * any app that bundles the SDK (path lib/<abi>/libarcore_sdk_c.so).
#
# The shim is installed as libarcore_sdk_c.so, so it must dlopen() the real
# library under a different name; the dynamic linker resolves by SONAME, hence
# the SONAME patch.
set -euo pipefail
cd "$(dirname "$0")"

SOURCE=${1:?usage: extract-real-lib.sh <arcore-sdk.aar | app.apk> [out.so]}
OUT=${2:-libarcore_sdk_c_real.so}
ABI=${ABI:-arm64-v8a}

command -v patchelf >/dev/null || { echo "patchelf is required" >&2; exit 1; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

extracted=""
for path in "jni/$ABI/libarcore_sdk_c.so" "lib/$ABI/libarcore_sdk_c.so"; do
    if unzip -q -j "$SOURCE" "$path" -d "$TMP" 2>/dev/null; then
        extracted="$TMP/libarcore_sdk_c.so"
        break
    fi
done

[ -n "$extracted" ] || { echo "libarcore_sdk_c.so not found in $SOURCE" >&2; exit 1; }

cp "$extracted" "$OUT"
patchelf --set-soname libarcore_sdk_c_real.so "$OUT"
readelf -d "$OUT" | grep -i soname
ls -l "$OUT"
