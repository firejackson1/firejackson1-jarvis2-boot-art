#!/bin/sh
set -eu
cd "$(dirname "$0")"
: "${ANDROID_JAR:?Set ANDROID_JAR to the API 34 android.jar}"
: "${R8_JAR:?Set R8_JAR to an R8 JAR containing D8}"
: "${APK_KEYSTORE:?Set APK_KEYSTORE to the signing keystore}"
: "${APK_KEY_ALIAS:?Set APK_KEY_ALIAS to the signing alias}"
mkdir -p build/classes build/dex
aapt package -f -M AndroidManifest.xml -S res -I "$ANDROID_JAR" -F build/unsigned.apk
javac --release 8 -Xlint:all -classpath "$ANDROID_JAR" -d build/classes src/dev/jarvis/bootart/*.java
jar cf build/classes.jar -C build/classes .
java -cp "$R8_JAR" com.android.tools.r8.D8 --min-api 26 --lib "$ANDROID_JAR" --output build/dex build/classes.jar
(cd build/dex && aapt add ../unsigned.apk classes.dex)
apksigner sign --ks "$APK_KEYSTORE" --ks-key-alias "$APK_KEY_ALIAS" --out build/Jarvis-Boot-Art.apk build/unsigned.apk
apksigner verify --verbose build/Jarvis-Boot-Art.apk
