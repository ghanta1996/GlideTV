#!/usr/bin/env bash
# Actual runtime captures with clearly identified, synthetic app fixtures.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
adb() { "$SDK/platform-tools/adb" "$@"; }
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo 'UI captures require a disposable Android emulator; refusing to alter a real device.' >&2
  exit 1
fi
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-36/android.jar"
output="${1:-build/visual-review}"
mkdir -p "$output" build/visual-fixtures
output="$(realpath "$output")"
app_id=space.glidetv.launcher
IFS=',' read -r -a resolutions <<< "${VISUAL_RESOLUTIONS:-1920x1080}"
for resolution in "${resolutions[@]}"; do
  if [[ ! "$resolution" =~ ^[1-9][0-9]*x[1-9][0-9]*$ ]]; then
    echo "Invalid visual resolution: $resolution" >&2
    exit 1
  fi
done
reset_display() {
  adb shell wm size reset >/dev/null 2>&1 || true
  adb shell wm density reset >/dev/null 2>&1 || true
}
trap reset_display EXIT

# These demo packages provide real PackageManager icons/banners, without accounts or media history.
python3 - <<'PY'
from pathlib import Path
items=[('coral','Coral demo','#EF4056',True),('ocean','Ocean demo','#00A8E1',True),
       ('mint','Mint demo','#1ED760',False),('mono','Mono demo','#FFFFFF',False)]
for name,label,color,banner in items:
    root=Path('build/visual-fixtures')/name
    (root/'res/drawable').mkdir(parents=True,exist_ok=True)
    (root/'res/drawable/icon.xml').write_text(f'''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="96dp" android:height="96dp" android:viewportWidth="96" android:viewportHeight="96">
<path android:fillColor="#090D14" android:pathData="M0,0H96V96H0Z"/>
<path android:fillColor="{color}" android:pathData="M30,20L76,48L30,76Z"/>
</vector>''')
    if banner:
        (root/'res/drawable/banner.xml').write_text(f'''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="320dp" android:height="180dp" android:viewportWidth="320" android:viewportHeight="180">
<path android:fillColor="#090D14" android:pathData="M0,0H320V180H0Z"/>
<path android:fillColor="{color}" android:pathData="M66,53L128,90L66,127Z M154,66H252V77H154Z M154,85H232V96H154Z M154,104H244V115H154Z"/>
</vector>''')
    banner_attr='android:banner="@drawable/banner"' if banner else ''
    (root/'AndroidManifest.xml').write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="space.glidetv.preview.{name}">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>
<application android:hasCode="false" android:label="{label}" android:icon="@drawable/icon" {banner_attr}>
<activity android:name="android.app.Activity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LEANBACK_LAUNCHER"/></intent-filter></activity>
</application></manifest>''')
PY
for name in coral ocean mint mono; do
  fixture="build/visual-fixtures/$name"
  "$BT/aapt2" compile --dir "$fixture/res" -o "$fixture/resources.zip"
  "$BT/aapt2" link -o "$fixture/base.apk" --manifest "$fixture/AndroidManifest.xml" -I "$JAR" "$fixture/resources.zip"
  "$BT/zipalign" -f 4 "$fixture/base.apk" "$fixture/aligned.apk"
  "$BT/apksigner" sign --ks glide-release.jks --ks-pass pass:android --key-pass pass:android --ks-key-alias glide --out "$fixture/demo.apk" "$fixture/aligned.apk"
  adb install -r "$fixture/demo.apk"
done

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard

seed_home() {
  local visual_watch_fixture="${1:-false}"
  adb shell am force-stop "$app_id"
  adb shell run-as "$app_id" mkdir -p shared_prefs
  cat > build/visual-fixtures/home.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="favorites">space.glidetv.preview.coral,space.glidetv.preview.ocean,space.glidetv.preview.mint,space.glidetv.preview.mono</string>
  <boolean name="location_prompted" value="true" />
  <boolean name="connection_permissions_prompted" value="true" />
</map>
XML
  adb shell -T "run-as $app_id sh -c 'cat > shared_prefs/home.xml'" < build/visual-fixtures/home.xml
  adb shell pm grant "$app_id" android.permission.READ_TV_LISTINGS || true
  if [[ "$visual_watch_fixture" == true ]]; then
    adb shell am start -W --ez visual_watch_fixture true -n "$app_id/.HomeActivity"
  else
    adb shell am start -W -n "$app_id/.HomeActivity"
  fi
  sleep 4
}

capture() {
  adb exec-out screencap -p > "$output/$1.png"
  adb shell uiautomator dump /sdcard/glide-window.xml >/dev/null
  adb pull /sdcard/glide-window.xml "$output/$1.xml" >/dev/null
}

assert_focus() {
  local capture_name="$1" expected="$2"
  capture "$capture_name"
  python3 - "$output/$capture_name.xml" "$expected" <<'PY'
import sys, xml.etree.ElementTree as ET
tree=ET.parse(sys.argv[1])
expected=sys.argv[2]
focused=[node for node in tree.iter('node') if node.get('focused')=='true']
assert any(expected in (node.get('text','')+' '+node.get('content-desc','')) for node in focused), (expected, [node.attrib for node in focused])
PY
}

assert_visible() {
  local capture_name="$1" expected="$2"
  capture "$capture_name"
  python3 - "$output/$capture_name.xml" "$expected" <<'PY'
import sys, xml.etree.ElementTree as ET
tree=ET.parse(sys.argv[1])
expected=sys.argv[2]
assert any(expected in (node.get('text','')+' '+node.get('content-desc','')) for node in tree.iter('node')), expected
PY
}

assert_favorite_order() {
  local capture_name="$1"
  shift
  capture "$capture_name"
  python3 - "$output/$capture_name.xml" "$@" <<'PY'
import sys, xml.etree.ElementTree as ET
tree=ET.parse(sys.argv[1])
actual=[]
for node in tree.iter('node'):
    description=node.get('content-desc','')
    if ', favorite. Hold OK to move.' in description:
        actual.append(description.split(', favorite. Hold OK to move.')[0])
assert actual == sys.argv[2:], (actual, sys.argv[2:])
PY
}

press() {
  adb shell input keyevent "KEYCODE_DPAD_$1"
  sleep .5
}

density_for_resolution() {
  case "$1" in
    1280x720) printf '%s\n' 213 ;;
    1920x1080) printf '%s\n' 320 ;;
    3840x2160) printf '%s\n' 640 ;;
    *) printf '%s\n' 320 ;;
  esac
}

capture_resolution() {
  local resolution="$1"
  local label="${resolution/x/-}"
  local density
  density="$(density_for_resolution "$resolution")"

  if [[ "$resolution" == 3840x2160 ]]; then
    adb shell wm size reset
  else
    adb shell wm size "$resolution"
  fi
  adb shell wm density "$density"
  if [[ -n "${VISUAL_BASE_APK:-}" ]]; then
    adb install -r "$VISUAL_BASE_APK"
    seed_home
    capture "before-$label"
    adb uninstall "$app_id"
  fi
  adb install -r "${VISUAL_HEAD_APK:-build/GlideTV.apk}"
  seed_home
  capture "after-$label-coral"
  for name in ocean mint mono; do
    adb shell input keyevent KEYCODE_DPAD_RIGHT
    sleep 1
    capture "after-$label-$name"
  done
  adb uninstall "$app_id"
}

for resolution in "${resolutions[@]}"; do
  capture_resolution "$resolution"
done

adb shell wm size 1920x1080
adb shell wm density 320
adb install -r "${VISUAL_HEAD_APK:-build/GlideTV.apk}"
seed_home
# Verify the real focus graph, including boundaries, modal dialogs, and both reorder exits.
assert_focus "nav-dock-initial" "Coral demo"
press LEFT
assert_focus "nav-dock-left-boundary" "Coral demo"
press UP
assert_focus "nav-top-remote" "Glide Remote"
press RIGHT
assert_focus "nav-top-library" "App library"
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 1
assert_visible "nav-library-dialog" "App library"
adb shell input keyevent KEYCODE_BACK
sleep .5
press DOWN
assert_focus "nav-return-to-dock" "Coral demo"
adb shell input keyevent KEYCODE_MENU
sleep .5
assert_visible "nav-app-options-dialog" "Coral demo"
adb shell input keyevent KEYCODE_BACK
sleep .5
press DOWN
assert_focus "nav-dock-down-boundary" "Coral demo"
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER
sleep .4
press RIGHT
adb shell input keyevent KEYCODE_BACK
sleep .5
assert_favorite_order "nav-reorder-cancel" "Coral demo" "Ocean demo" "Mint demo" "Mono demo"
assert_focus "nav-reorder-cancel-focus" "Coral demo"
seed_home
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER
sleep .4
press RIGHT
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep .5
assert_favorite_order "nav-reorder-save" "Ocean demo" "Coral demo" "Mint demo" "Mono demo"
assert_focus "nav-reorder-save-focus" "Coral demo"

seed_home true
assert_focus "nav-watch-initial" "Coral demo"
press UP
assert_focus "nav-watch-card" "Resume Focus regression"
press DOWN
assert_focus "nav-watch-return-to-dock" "Coral demo"
press UP
assert_focus "nav-watch-card-return" "Resume Focus regression"
press UP
assert_focus "nav-watch-up-to-top" "Glide Remote"

# A short unedited recording demonstrates the vertical top-bar/dock path and a saved reorder.
seed_home
adb shell screenrecord --time-limit 12 --bit-rate 4000000 /sdcard/glide-dock.mp4 &
recording=$!
sleep 1
for key in UP RIGHT DOWN; do
  adb shell input keyevent "KEYCODE_DPAD_$key"
  sleep .8
done
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER
sleep .8
adb shell input keyevent KEYCODE_DPAD_RIGHT
sleep .8
adb shell input keyevent KEYCODE_DPAD_CENTER
wait "$recording"
adb pull /sdcard/glide-dock.mp4 "$output/focus-transitions.mp4"
adb logcat -d -s AndroidRuntime:E > "$output/android-errors.txt"
if grep -Fq "Process: $app_id, PID:" "$output/android-errors.txt"; then
  cat "$output/android-errors.txt" >&2
  exit 1
fi
python3 - "$output" "${resolutions[@]/x/-}" <<'PY'
from pathlib import Path
import sys, xml.etree.ElementTree as ET
root=Path(sys.argv[1])
resolutions=sys.argv[2:]

def png_size(path):
    with path.open('rb') as image:
        assert image.read(8) == b'\x89PNG\r\n\x1a\n', path
        assert image.read(4) == b'\x00\x00\x00\r', path
        assert image.read(4) == b'IHDR', path
        return tuple(int.from_bytes(image.read(4), 'big') for _ in range(2))

for resolution in resolutions:
    expected_size=tuple(int(value) for value in resolution.split('-'))
    for name,label in [('coral','Coral demo'),('ocean','Ocean demo'),('mint','Mint demo'),('mono','Mono demo')]:
        capture_name=f'after-{resolution}-{name}'
        tree=ET.parse(root/(capture_name+'.xml'))
        focused=[node for node in tree.iter('node') if node.get('focused')=='true']
        assert any(label in node.get('content-desc','') for node in focused), (capture_name, [node.attrib for node in focused])
        screenshot=root/(capture_name+'.png')
        assert screenshot.stat().st_size>10000, capture_name
        assert png_size(screenshot) == expected_size, (capture_name, png_size(screenshot), expected_size)
print('Runtime screenshots and D-pad focus states verified at 720p, 1080p, and 4K')
PY
git rev-parse HEAD > "$output/tested-commit.txt"
printf '%s\n' 'Android TV API 36 emulator. Captured UI sizes: 1280x720 at 213 dpi, 1920x1080 at 320 dpi, and 3840x2160 at 640 dpi, preserving a comparable TV layout scale. Coral/Ocean/Mint/Mono are synthetic demo apps, not third-party services. Scenery is the native fallback; location is unset. Screenshots and recording are actual app captures.' > "$output/README.txt"
