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

AR_AAR="https://dl.google.com/dl/android/maven2/com/google/ar/core/${AR_VERSION}/core-${AR_VERSION}.aar"
CV_AAR="https://repo1.maven.org/maven2/org/opencv/opencv/${OPENCV_VERSION}/opencv-${OPENCV_VERSION}.aar"

for path in "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$BT/zipalign" "$ANDROID_JAR"; do
    [ -e "$path" ] || { echo "missing: $path" >&2; exit 1; }
done

mkdir -p deps build/lib/arm64-v8a

fetch() {
    [ -f "$2" ] || curl -fsSL "$1" -o "$2"
}
fetch "$AR_AAR" "deps/arcore-$AR_VERSION.aar"
fetch "$CV_AAR" "deps/opencv-$OPENCV_VERSION.aar"

rm -rf build/deps build/classes build/dex build/base.apk build/aligned.apk build/classes.dex
mkdir -p build/deps/arcore build/deps/opencv build/classes build/dex
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
    --version-code 1 --version-name 0.1.0

cp build/dex/classes.dex build/classes.dex
cp build/deps/arcore/jni/arm64-v8a/*.so build/lib/arm64-v8a/
cp build/deps/opencv/jni/arm64-v8a/libopencv_java4.so build/lib/arm64-v8a/
if [ -f "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" ]; then
    cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" \
       build/lib/arm64-v8a/
fi

(cd build && zip -q -u base.apk classes.dex && zip -q -r -u base.apk lib)

if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias debug -storepass android -keypass android \
        -dname "CN=ARCore Toolkit" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

"$BT/zipalign" -p -f 4 build/base.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT" build/aligned.apk

ls -l "$OUT"
