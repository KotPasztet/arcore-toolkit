#!/usr/bin/env bash
# Repack an APK with the shim and sign it.
#
#   SOURCE_APK=/path/to/app.apk REAL_LIB=libarcore_sdk_c_real.so \
#       OUT_APK=app-shim.apk ./package_apk.sh
#
# The target APK must ship lib/<abi>/libarcore_sdk_c.so; that file is replaced
# by our shim and the original library is added under a new name
# (libarcore_sdk_c_real.so) so the shim's dlopen() finds it.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)

SOURCE_APK=${SOURCE_APK:?set SOURCE_APK}
SHIM=${SHIM:-libarcore_sdk_c.so}
REAL_LIB=${REAL_LIB:?set REAL_LIB (see extract-real-lib.sh)}
OUT_APK=${OUT_APK:-app-shim.apk}
ABI=${ABI:-arm64-v8a}
KS=${KS:-$ROOT/android/debug.keystore}
KS_PASS=${KS_PASS:-android}
KEY_PASS=${KEY_PASS:-android}
KEY_ALIAS=${KEY_ALIAS:-debug}
BT=${BUILD_TOOLS:-/opt/android-sdk/build-tools/35.0.0}

[ -f "$SHIM" ] || { echo "shim not found: $SHIM" >&2; exit 1; }
[ -f "$REAL_LIB" ] || { echo "real library not found: $REAL_LIB" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/lib/$ABI"

cp "$SOURCE_APK" "$WORK/app.apk"
zip -q -d "$WORK/app.apk" "lib/$ABI/libarcore_sdk_c.so" || true
cp "$SHIM" "$WORK/lib/$ABI/libarcore_sdk_c.so"
cp "$REAL_LIB" "$WORK/lib/$ABI/libarcore_sdk_c_real.so"

(cd "$WORK" && zip -q -u app.apk "lib/$ABI/libarcore_sdk_c.so" "lib/$ABI/libarcore_sdk_c_real.so")

if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias "$KEY_ALIAS" \
        -storepass "$KS_PASS" -keypass "$KEY_PASS" \
        -dname "CN=ARCore Toolkit" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

"$BT/zipalign" -p -f 4 "$WORK/app.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
    --out "$OUT_APK" "$WORK/aligned.apk"

ls -l "$OUT_APK"
