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

adb shell wm size 1920x1080
adb shell wm density 320
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard

seed_home() {
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
  adb shell am start -W -n "$app_id/.HomeActivity"
  sleep 4
}

capture() {
  adb exec-out screencap -p > "$output/$1.png"
  adb shell uiautomator dump /sdcard/glide-window.xml >/dev/null
  adb pull /sdcard/glide-window.xml "$output/$1.xml" >/dev/null
}

if [[ -n "${VISUAL_BASE_APK:-}" ]]; then
  adb install -r "$VISUAL_BASE_APK"
  seed_home
  capture before
  adb uninstall "$app_id"
fi
adb install -r "${VISUAL_HEAD_APK:-build/GlideTV.apk}"
seed_home
capture after-coral
for name in ocean mint mono; do
  adb shell input keyevent KEYCODE_DPAD_RIGHT
  sleep 1
  capture "after-$name"
done
# A short unedited recording demonstrates color transitions and left/right focus changes.
adb shell screenrecord --time-limit 12 --bit-rate 4000000 /sdcard/glide-dock.mp4 &
recording=$!
sleep 1
for key in LEFT LEFT LEFT RIGHT RIGHT RIGHT LEFT LEFT LEFT; do
  adb shell input keyevent "KEYCODE_DPAD_$key"
  sleep .8
done
wait "$recording"
adb pull /sdcard/glide-dock.mp4 "$output/focus-transitions.mp4"
adb logcat -d -s AndroidRuntime:E > "$output/android-errors.txt"
if grep -Fq "Process: $app_id, PID:" "$output/android-errors.txt"; then
  cat "$output/android-errors.txt" >&2
  exit 1
fi
python3 - "$output" <<'PY'
from pathlib import Path
import sys, xml.etree.ElementTree as ET
root=Path(sys.argv[1])
for name,label in [('after-coral','Coral demo'),('after-ocean','Ocean demo'),('after-mint','Mint demo'),('after-mono','Mono demo')]:
    tree=ET.parse(root/(name+'.xml'))
    focused=[n for n in tree.iter('node') if n.get('focused')=='true']
    assert any(label in n.get('content-desc','') for n in focused), (name, [n.attrib for n in focused])
    assert (root/(name+'.png')).stat().st_size>10000, name
print('Runtime screenshots and D-pad focus states verified')
PY
git rev-parse HEAD > "$output/tested-commit.txt"
printf '%s\n' 'Android TV API 36 emulator, 1920x1080 at 320 dpi. Coral/Ocean/Mint/Mono are synthetic demo apps, not third-party services. Scenery is the native fallback; location is unset. Screenshots and recording are actual app captures.' > "$output/README.txt"
