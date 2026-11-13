#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")"
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17 ]]; then
 export JAVA_HOME=/opt/homebrew/opt/openjdk@17
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-36/android.jar"
ZXING="$PWD/lib/zxing-core-3.5.4.jar"
mkdir -p build/classes build/dex
"$BT/aapt2" compile --dir res -o build/resources.zip
mkdir -p build/apk-assets/remote
cp assets/remote/index.html build/apk-assets/remote/index.html
"$BT/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -I "$JAR" -A build/apk-assets build/resources.zip
javac -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -cp "$ZXING" -d build/classes src/space/glidetv/launcher/*.java
jar cf build/classes.jar -C build/classes .
"$BT/d8" --lib "$JAR" --min-api 26 --output build/dex build/classes.jar "$ZXING"
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
GLIDE_KEYSTORE="${GLIDE_KEYSTORE:-$PWD/glide-release.jks}"
export GLIDE_KEYSTORE_PASSWORD="${GLIDE_KEYSTORE_PASSWORD:-android}"
export GLIDE_KEY_PASSWORD="${GLIDE_KEY_PASSWORD:-$GLIDE_KEYSTORE_PASSWORD}"
GLIDE_KEY_ALIAS="${GLIDE_KEY_ALIAS:-glide}"
if [[ ! -f "$GLIDE_KEYSTORE" ]]; then
 if [[ "${CI:-false}" == true ]]; then printf '%s\n' 'CI signing keystore is missing.' >&2; exit 1; fi
 keytool -genkeypair -keystore "$GLIDE_KEYSTORE" -storepass:env GLIDE_KEYSTORE_PASSWORD -keypass:env GLIDE_KEY_PASSWORD -alias "$GLIDE_KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Glide TV Local'
fi
"$BT/apksigner" sign --ks "$GLIDE_KEYSTORE" --ks-pass env:GLIDE_KEYSTORE_PASSWORD --key-pass env:GLIDE_KEY_PASSWORD --ks-key-alias "$GLIDE_KEY_ALIAS" --out build/GlideTV.apk build/aligned.apk
"$BT/apksigner" verify build/GlideTV.apk
ls -lh build/GlideTV.apk

mkdir -p build/bridge-classes build/bridge-dex
javac -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -d build/bridge-classes bridge/space/glidetv/bridge/*.java
jar cf build/bridge.jar -C build/bridge-classes .
"$BT/d8" --lib "$JAR" --min-api 26 --output build/bridge-dex build/bridge.jar
