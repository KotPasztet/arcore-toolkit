#!/usr/bin/env bash
# Repack Apace (com.kotpasztet.apace) with the ARCore shim and sign it.
#
# Input : apace.apk (original, any signature), shim/libarcore_sdk_c.so,
#         shim_pack/lib/arm64-v8a/libarcore_sdk_c_real.so (real lib, SONAME patched)
# Output: apace_shim_signed.apk
#
# Notes:
#  * The real lib is renamed AND its SONAME must be libarcore_sdk_c_real.so,
#    otherwise dlopen("libarcore_sdk_c_real.so") / the dynamic linker would
#    resolve back to our shim.
#  * Re-signing with a different key means Apace data is reset on reinstall.
set -euo pipefail
cd "$(dirname "$0")/.."

BT=${BT:-/opt/android-sdk/build-tools/35.0.0}
KS=${KS:-game/test.keystore}
SHIM=${SHIM:-shim/libarcore_sdk_c.so}
OUT=${OUT_APK:-apace_shim_signed.apk}

cp "$SHIM" shim_pack/lib/arm64-v8a/libarcore_sdk_c.so

rm -f apace_shim.apk apace_shim_aligned.apk "$OUT"
cp apace.apk apace_shim.apk
zip -q -d apace_shim.apk lib/arm64-v8a/libarcore_sdk_c.so
(cd shim_pack && zip -q -u ../apace_shim.apk \
    lib/arm64-v8a/libarcore_sdk_c.so \
    lib/arm64-v8a/libarcore_sdk_c_real.so)

"$BT/zipalign" -p -f 4 apace_shim.apk apace_shim_aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT" apace_shim_aligned.apk

ls -l "$OUT"
