#!/usr/bin/env bash
# Build the ARCore Toolkit APK without Gradle.
#
# Downloads the ARCore and OpenCV AARs into ./deps (not committed), compiles the
# sources with javac, converts them with d8, packages with aapt2 and signs with
# a locally generated debug key.
#
# Requirements: JDK 11+, Android SDK (build-tools + platform 35), Android NDK
# (for libc++_shared.so), curl, unzip, zip.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}
BT=${BUILD_TOOLS:-$SDK/build-tools/35.0.0}
ANDROID_JAR=${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}
NDK=${ANDROID_NDK_ROOT:-${ANDROID_NDK:-$SDK/android-ndk-r27c}}
AR_VERSION=${AR_VERSION:-1.57.0}
OPENCV_VERSION=${OPENCV_VERSION:-4.9.0}
OUT=${OUT:-arcore-toolkit.apk}
KS=${KS:-debug.keystore}
KS_PASS=${KS_PASS:-android}
KEY_PASS=${KEY_PASS:-android}
KEY_ALIAS=${KEY_ALIAS:-debug}
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-0.3.0}
# Build all ABI directories shipped by the ARCore/OpenCV AARs by default.
# Override with e.g. ABIS=arm64-v8a to keep the APK small.
ABIS=${ABIS:-"arm64-v8a armeabi-v7a x86 x86_64"}

AR_AAR="https://dl.google.com/dl/android/maven2/com/google/ar/core/${AR_VERSION}/core-${AR_VERSION}.aar"
CV_AAR="https://repo1.maven.org/maven2/org/opencv/opencv/${OPENCV_VERSION}/opencv-${OPENCV_VERSION}.aar"

for path in "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$BT/zipalign" "$ANDROID_JAR"; do
    [ -e "$path" ] || { echo "missing: $path" >&2; exit 1; }
done

mkdir -p deps

fetch() {
    [ -f "$2" ] || curl -fsSL "$1" -o "$2"
}
fetch "$AR_AAR" "deps/arcore-$AR_VERSION.aar"
fetch "$CV_AAR" "deps/opencv-$OPENCV_VERSION.aar"

rm -rf build/deps build/classes build/dex build/lib build/base.apk build/aligned.apk build/classes.dex
mkdir -p build/deps/arcore build/deps/opencv build/classes build/dex build/lib
for abi in $ABIS; do mkdir -p "build/lib/$abi"; done
unzip -q -o "deps/arcore-$AR_VERSION.aar" -d build/deps/arcore
unzip -q -o "deps/opencv-$OPENCV_VERSION.aar" -d build/deps/opencv

CP="$ANDROID_JAR:build/deps/arcore/classes.jar:build/deps/opencv/classes.jar"
find app/src -name '*.java' > build/sources.txt
javac -source 8 -target 8 -classpath "$CP" -d build/classes @build/sources.txt

find build/classes -name '*.class' > build/classes.txt
"$BT/d8" --lib "$ANDROID_JAR" --min-api 29 --output build/dex @build/classes.txt \
    build/deps/arcore/classes.jar build/deps/opencv/classes.jar

"$BT/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -I "$ANDROID_JAR" \
    --min-sdk-version 29 --target-sdk-version 35 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --replace-version

cp build/dex/classes.dex build/classes.dex

abi_triplet() {
    case "$1" in
        arm64-v8a)   echo aarch64-linux-android ;;
        armeabi-v7a) echo arm-linux-androideabi ;;
        x86)         echo i686-linux-android ;;
        x86_64)      echo x86_64-linux-android ;;
        *)           echo "" ;;
    esac
}

for abi in $ABIS; do
    [ -d "build/deps/arcore/jni/$abi" ] || {
        echo "warning: ARCore has no $abi binaries, skipping" >&2
        continue
    }
    cp build/deps/arcore/jni/"$abi"/*.so build/lib/"$abi"/
    if [ -f "build/deps/opencv/jni/$abi/libopencv_java4.so" ]; then
        cp build/deps/opencv/jni/"$abi"/libopencv_java4.so build/lib/"$abi"/
    else
        echo "warning: OpenCV has no $abi binaries, skipping" >&2
        rm -rf build/lib/"$abi"
        continue
    fi
    # Use the libc++_shared.so shipped with the AAR when available: it matches
    # the OpenCV build.  Fall back to the NDK sysroot for older AARs.
    if [ -f "build/deps/opencv/jni/$abi/libc++_shared.so" ]; then
        cp build/deps/opencv/jni/"$abi"/libc++_shared.so build/lib/"$abi"/
    else
        triplet=$(abi_triplet "$abi")
        src="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$triplet/libc++_shared.so"
        [ -n "$triplet" ] && [ -f "$src" ] && cp "$src" build/lib/"$abi"/
    fi
done

(cd build && zip -q -u base.apk classes.dex && zip -q -r -u base.apk lib)

if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias "$KEY_ALIAS" \
        -storepass "$KS_PASS" -keypass "$KEY_PASS" \
        -dname "CN=ARCore Toolkit" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

"$BT/zipalign" -p -f 4 build/base.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
    --out "$OUT" build/aligned.apk

ls -l "$OUT"
