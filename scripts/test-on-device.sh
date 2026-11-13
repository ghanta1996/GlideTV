#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TV_DEVICE="${1:?Usage: $0 <adb-serial> (after ./build.sh)}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17 ]]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@17; fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
test -f build/classes.jar && test -f build/GlideTV.apk || { printf '%s\n' 'Run ./build.sh first.' >&2; exit 1; }
TEST_WORK="$(mktemp -d)"
REMOTE_TEST_DIR="/data/local/tmp/glide-scene-smoke-$$"
cleanup() { adb -s "$TV_DEVICE" shell rm -rf "$REMOTE_TEST_DIR" >/dev/null 2>&1 || true; rm -rf "$TEST_WORK"; }
trap cleanup EXIT
mkdir -p "$TEST_WORK/classes" "$TEST_WORK/dex"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR:$BT/core-lambda-stubs.jar" -cp build/classes.jar -d "$TEST_WORK/classes" tests/SceneSmoke.java tests/DownloadSmoke.java
jar cf "$TEST_WORK/test.jar" -C "$TEST_WORK/classes" .
"$BT/d8" --lib "$ANDROID_JAR" --classpath build/classes.jar --min-api 26 --output "$TEST_WORK/dex" "$TEST_WORK/test.jar"
adb -s "$TV_DEVICE" shell mkdir -p "$REMOTE_TEST_DIR"
adb -s "$TV_DEVICE" push "$TEST_WORK/dex/classes.dex" "$REMOTE_TEST_DIR/test.dex"
adb -s "$TV_DEVICE" push build/GlideTV.apk "$REMOTE_TEST_DIR/app.apk"
adb -s "$TV_DEVICE" shell "chmod 444 '$REMOTE_TEST_DIR/test.dex' '$REMOTE_TEST_DIR/app.apk'; CLASSPATH='$REMOTE_TEST_DIR/test.dex:$REMOTE_TEST_DIR/app.apk' app_process /system/bin space.glidetv.launcher.SceneSmoke '$REMOTE_TEST_DIR/state'"
adb -s "$TV_DEVICE" shell "CLASSPATH='$REMOTE_TEST_DIR/test.dex:$REMOTE_TEST_DIR/app.apk' app_process /system/bin space.glidetv.launcher.DownloadSmoke '$REMOTE_TEST_DIR/downloads'"
