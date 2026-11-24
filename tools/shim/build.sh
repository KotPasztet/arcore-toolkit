#!/usr/bin/env bash
# Build the ARCore C API shim.
#
#   ./build.sh                                   # marker override shim
#   SHIM_INC=passthrough.inc SHIM_NO_OVERRIDES=1 \
#       OUT=libarcore_sdk_c_stock.so ./build.sh  # pure pass-through shim
#
# Requirements: Android NDK (r25+), python3, AprilTag sources (vendored in
# ../../third_party/apriltag; not needed for the pass-through build).
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)

NDK=${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_NDK:-/opt/android-sdk/android-ndk-r27c}}}
API=${API:-29}
CC=${CC:-$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang}
PY=${PY:-python3}
OUT=${OUT:-libarcore_sdk_c.so}
SHIM_INC=${SHIM_INC:-overrides.inc}
APRILTAG=${APRILTAG:-$ROOT/third_party/apriltag}

[ -x "$CC" ] || { echo "compiler not found: $CC" >&2; exit 1; }

if [ -z "${WITH_APRILTAG:-}" ]; then
    case "$SHIM_INC" in
        passthrough.inc) WITH_APRILTAG=0 ;;
        *) WITH_APRILTAG=1 ;;
    esac
fi

APR_SOURCES=()
if [ "$WITH_APRILTAG" = "1" ]; then
    [ -f "$APRILTAG/apriltag.c" ] || { echo "AprilTag sources missing: $APRILTAG" >&2; exit 1; }
    APR_SOURCES=(
        "$APRILTAG/apriltag.c"
        "$APRILTAG/apriltag_pose.c"
        "$APRILTAG/apriltag_quad_thresh.c"
        "$APRILTAG/tag36h11.c"
    )
    while IFS= read -r -d '' f; do APR_SOURCES+=("$f"); done \
        < <(find "$APRILTAG/common" -name '*.c' -print0)
fi

SHIM_INC="$SHIM_INC" "$PY" gen_shim.py
"$PY" postprocess.py

# shellcheck disable=SC2086
"$CC" -shared -fPIC -O2 ${DEFINES:-} ${CFLAGS:-} \
    -I. -I"$APRILTAG" \
    -Wl,-soname,libarcore_sdk_c.so -Wl,--version-script=vers.map \
    -o "$OUT" shim.c internal.S "${APR_SOURCES[@]}" \
    -ldl -llog -lm -pthread

ls -l "$OUT"
