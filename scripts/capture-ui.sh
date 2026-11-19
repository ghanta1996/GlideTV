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
       ('mint','Mint demo','#1ED760',False),('mono','Mono demo','#FFFFFF',False),
       ('amber','Amber demo','#FFB020',False),('violet','Violet demo','#8B5CF6',False),
       ('cyan','Cyan demo','#22D3EE',False),('rose','Rose demo','#FB7185',False),
       ('gold','Gold demo','#FACC15',False),('lime','Lime demo','#84CC16',False),
       ('navy','Navy demo','#3B82F6',False),('pearl','Pearl demo','#E2E8F0',False)]
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
for name in coral ocean mint mono amber violet cyan rose gold lime navy pearl; do
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
  local visual_night_fixture="${2:-false}"
  local visual_music_fixture="${3:-false}"
  local dense_favorites="${4:-false}"
  local favorites='space.glidetv.preview.coral,space.glidetv.preview.ocean,space.glidetv.preview.mint,space.glidetv.preview.mono'
  if [[ "$dense_favorites" == true ]]; then
    favorites+=',space.glidetv.preview.amber,space.glidetv.preview.violet,space.glidetv.preview.cyan,space.glidetv.preview.rose,space.glidetv.preview.gold,space.glidetv.preview.lime,space.glidetv.preview.navy,space.glidetv.preview.pearl'
  fi
  adb shell am force-stop "$app_id"
  adb shell run-as "$app_id" mkdir -p shared_prefs
  cat > build/visual-fixtures/home.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="favorites">$favorites</string>
  <boolean name="location_prompted" value="true" />
  <boolean name="connection_permissions_prompted" value="true" />
  <boolean name="permission_setup_prompted" value="true" />
</map>
XML
  adb shell -T "run-as $app_id sh -c 'cat > shared_prefs/home.xml'" < build/visual-fixtures/home.xml
  adb shell pm grant "$app_id" android.permission.READ_TV_LISTINGS || true
  adb shell am start -W --ez visual_watch_fixture "$visual_watch_fixture" --ez visual_night_fixture "$visual_night_fixture" --ez visual_music_fixture "$visual_music_fixture" -n "$app_id/.HomeActivity"
  sleep 4
}

capture() {
  adb exec-out screencap -p > "$output/$1.png"
  adb shell uiautomator dump /sdcard/glide-window.xml >/dev/null
  adb pull /sdcard/glide-window.xml "$output/$1.xml" >/dev/null
}

assert_focus() {
  local capture_name="$1" expected="$2"
  sleep .5
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

assert_not_visible() {
  local capture_name="$1" unexpected="$2"
  capture "$capture_name"
  python3 - "$output/$capture_name.xml" "$unexpected" <<'PY'
import sys, xml.etree.ElementTree as ET
tree=ET.parse(sys.argv[1])
unexpected=sys.argv[2]
assert not any(unexpected in (node.get('text','')+' '+node.get('content-desc','')) for node in tree.iter('node')), unexpected
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

capture_watch_focus() {
  local capture_name="$1"
  for _ in 1 2 3 4; do
    press UP
    capture "$capture_name-probe"
    if python3 - "$output/$capture_name-probe.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
tree=ET.parse(sys.argv[1])
focused=[node for node in tree.iter('node') if node.get('focused')=='true']
raise SystemExit(0 if any('Resume Focus regression' in node.get('content-desc','') for node in focused) else 1)
PY
    then
      mv "$output/$capture_name-probe.png" "$output/$capture_name.png"
      mv "$output/$capture_name-probe.xml" "$output/$capture_name.xml"
      return
    fi
    press DOWN
    sleep 1
  done
  echo "Continue Watching did not become focusable at $capture_name" >&2
  exit 1
}

capture_performance() {
  local label="$1"
  adb shell dumpsys gfxinfo "$app_id" reset >/dev/null
  for key in RIGHT RIGHT RIGHT LEFT LEFT LEFT; do
    adb shell input keyevent "KEYCODE_DPAD_$key"
    sleep 1.9
  done
  adb shell dumpsys gfxinfo "$app_id" > "$output/$label-gfxinfo.txt"
  adb shell dumpsys meminfo "$app_id" > "$output/$label-meminfo.txt"
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
    if [[ "$resolution" == 1920x1080 ]]; then
      seed_home true
      capture_performance "before-$label"
    fi
    adb uninstall "$app_id"
  fi
  adb install -r "${VISUAL_HEAD_APK:-build/GlideTV.apk}"
  seed_home true false true
  capture "after-$label-coral"
  capture_watch_focus "after-$label-watch"
  for _ in 1 2 3 4 5 6 7; do press RIGHT; done
  assert_focus "after-$label-watch-last" "Resume Watch fixture 8"
  press DOWN
  for name in ocean mint mono; do
    adb shell input keyevent KEYCODE_DPAD_RIGHT
    sleep 1
    capture "after-$label-$name"
  done
  if [[ "$resolution" == 1920x1080 ]]; then
    seed_home true true true
    capture "after-$label-night"
    capture_watch_focus "after-$label-night-watch"
    seed_home true false true
    capture_performance "after-$label"
  fi
  seed_home true false true
  press UP
  press UP
  press RIGHT
  press RIGHT
  press DOWN
  adb shell input keyevent KEYCODE_DPAD_CENTER
  sleep 3
  assert_visible "after-$label-ambient" "Midnight Drive Across the City Lights"
  seed_home false false false true
  capture "after-$label-dense-first"
  for _ in 1 2 3 4 5 6 7 8 9 10 11; do press RIGHT; done
  assert_focus "after-$label-dense-last" "Pearl demo"
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
press DOWN
assert_focus "nav-library-first-app" "Amber demo"
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER
sleep .5
assert_visible "nav-library-app-options" "Uninstall app"
press DOWN
press DOWN
press DOWN
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 3
assert_visible "nav-library-uninstall-confirm" "Do you want to uninstall this app?"
adb shell input keyevent KEYCODE_BACK
sleep .5
adb uninstall space.glidetv.preview.coral
sleep 2
assert_not_visible "nav-library-after-uninstall" "Coral demo"
adb shell input keyevent KEYCODE_BACK
sleep .5
adb install -r build/visual-fixtures/coral/demo.apk
seed_home
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

seed_home true false true
press UP
assert_focus "nav-music-watch" "Resume Focus regression"
press UP
assert_focus "nav-music-top" "Glide Remote"
press RIGHT
press RIGHT
assert_focus "nav-music-settings" "Settings"
press DOWN
assert_focus "nav-music-card" "Now playing Midnight Drive"
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 3
assert_visible "nav-ambient-open" "Midnight Drive Across the City Lights"
adb shell input keyevent KEYCODE_BACK
sleep .5

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
import re, sys, xml.etree.ElementTree as ET
root=Path(sys.argv[1])
resolutions=sys.argv[2:]
reference_geometry=None
layout_rows=[]

def png_size(path):
    with path.open('rb') as image:
        assert image.read(8) == b'\x89PNG\r\n\x1a\n', path
        assert image.read(4) == b'\x00\x00\x00\r', path
        assert image.read(4) == b'IHDR', path
        return tuple(int.from_bytes(image.read(4), 'big') for _ in range(2))

def bounds(node):
    values=[int(value) for value in re.findall(r'\d+',node.get('bounds',''))]
    assert len(values)==4, node.attrib
    return values

def find_node(nodes, text='', description=''):
    for node in nodes:
        if text and node.get('text','')==text:
            return node
        if description and description in node.get('content-desc',''):
            return node
    raise AssertionError((text,description))

for resolution in resolutions:
    expected_size=tuple(int(value) for value in resolution.split('-'))
    layout_tree=ET.parse(root/(f'after-{resolution}-watch.xml'))
    layout_nodes=list(layout_tree.iter('node'))
    width,height=expected_size
    interactive=[node for node in layout_nodes if node.get('package')=='space.glidetv.launcher' and (node.get('focusable')=='true' or node.get('clickable')=='true')]
    for node in interactive:
        left,top,right,bottom=bounds(node)
        assert 0<=left<right<=width and 0<=top<bottom<=height, node.attrib
    remote=find_node(layout_nodes,description='Glide Remote')
    library=find_node(layout_nodes,text='App library')
    settings=find_node(layout_nodes,text='Settings')
    watch=find_node(layout_nodes,description='Resume Focus regression')
    music=find_node(layout_nodes,description='Now playing Midnight Drive by Glide Sessions')
    favorites=[node for node in layout_nodes if ', favorite. Hold OK to move.' in node.get('content-desc','')]
    assert len(favorites)==4, [node.attrib for node in favorites]
    top_controls=[bounds(node) for node in (remote,library,settings)]
    assert all(top_controls[index][2]<=top_controls[index+1][0] for index in range(len(top_controls)-1)), top_controls
    favorite_bounds=[bounds(node) for node in favorites]
    assert all(favorite_bounds[index][2]<=favorite_bounds[index+1][0] for index in range(len(favorite_bounds)-1)), favorite_bounds
    watch_bounds=bounds(watch)
    music_bounds=bounds(music)
    assert max(item[3] for item in top_controls)<watch_bounds[1], (top_controls,watch_bounds)
    assert watch_bounds[3]<min(item[1] for item in favorite_bounds), (watch_bounds,favorite_bounds)
    def overlaps(first,second):
        return first[0]<second[2] and first[2]>second[0] and first[1]<second[3] and first[3]>second[1]
    assert not overlaps(music_bounds,watch_bounds), (music_bounds,watch_bounds)
    watch_cards=[bounds(node) for node in layout_nodes if node.get('content-desc','').startswith('Resume ')]
    assert all(not overlaps(music_bounds,item) for item in watch_cards), (music_bounds,watch_cards)
    assert all(not overlaps(music_bounds,item) for item in favorite_bounds), (music_bounds,favorite_bounds)
    key_bounds=[bounds(remote),bounds(library),bounds(settings),watch_bounds,music_bounds,favorite_bounds[0]]
    geometry=[coordinate/(width if index%2==0 else height) for item in key_bounds for index,coordinate in enumerate(item)]
    if reference_geometry is None:
        reference_geometry=geometry
    else:
        assert max(abs(current-reference) for current,reference in zip(geometry,reference_geometry))<.004, (resolution,geometry,reference_geometry)
    layout_rows.append((resolution,bounds(remote),bounds(library),bounds(settings),watch_bounds,music_bounds,favorite_bounds[0]))
    for name,label in [('coral','Coral demo'),('ocean','Ocean demo'),('mint','Mint demo'),('mono','Mono demo')]:
        capture_name=f'after-{resolution}-{name}'
        tree=ET.parse(root/(capture_name+'.xml'))
        focused=[node for node in tree.iter('node') if node.get('focused')=='true']
        assert any(label in node.get('content-desc','') for node in focused), (capture_name, [node.attrib for node in focused])
        screenshot=root/(capture_name+'.png')
        assert screenshot.stat().st_size>10000, capture_name
        assert png_size(screenshot) == expected_size, (capture_name, png_size(screenshot), expected_size)
    for suffix,description in [('watch-last','Resume Watch fixture 8'),('dense-last','Pearl demo')]:
        capture_name=f'after-{resolution}-{suffix}'
        tree=ET.parse(root/(capture_name+'.xml'))
        focused=[node for node in tree.iter('node') if node.get('focused')=='true']
        match=next((node for node in focused if description in node.get('content-desc','')),None)
        assert match is not None, (capture_name,[node.attrib for node in focused])
        left,top,right,bottom=bounds(match)
        assert 0<=left<right<=width and 0<=top<bottom<=height, (capture_name,match.attrib)
        screenshot=root/(capture_name+'.png')
        assert screenshot.stat().st_size>10000, capture_name
        assert png_size(screenshot) == expected_size, (capture_name,png_size(screenshot),expected_size)
    ambient_name=f'after-{resolution}-ambient'
    ambient_tree=ET.parse(root/(ambient_name+'.xml'))
    ambient_nodes=list(ambient_tree.iter('node'))
    ambient_title=find_node(ambient_nodes,text='Midnight Drive Across the City Lights')
    ambient_bounds=bounds(ambient_title)
    assert 0<=ambient_bounds[0]<ambient_bounds[2]<=width and 0<=ambient_bounds[1]<ambient_bounds[3]<=height, ambient_title.attrib
    ambient_screenshot=root/(ambient_name+'.png')
    assert ambient_screenshot.stat().st_size>10000, ambient_name
    assert png_size(ambient_screenshot)==expected_size, (ambient_name,png_size(ambient_screenshot),expected_size)
(root/'layout-geometry.md').write_text(
    '# Home layout geometry\n\n'
    'Actual Android TV UI bounds. CI also verifies every interactive control stays on-screen, the top controls and dock tiles do not overlap, Continue Watching stays between the top bar and dock, and normalized key bounds remain within 0.4% across resolutions.\n\n'
    '| Resolution | Remote | App library | Settings | Continue Watching | Music info | Focused favorite |\n'
    '| --- | --- | --- | --- | --- | --- | --- |\n'+
    ''.join(f'| {resolution.replace("-","×")} | `{remote}` | `{library}` | `{settings}` | `{watch}` | `{music}` | `{favorite}` |\n' for resolution,remote,library,settings,watch,music,favorite in layout_rows)
)
print('Runtime screenshots and D-pad focus states verified at 720p, 1080p, and 4K')
PY
python3 - "$output" <<'PY'
from pathlib import Path
import re, sys

root=Path(sys.argv[1])
def metrics(prefix):
    memory=(root/f'{prefix}-meminfo.txt').read_text(errors='replace')
    frames=(root/f'{prefix}-gfxinfo.txt').read_text(errors='replace')
    pss=re.search(r'TOTAL PSS:\s*([0-9,]+)',memory)
    if not pss:
        pss=re.search(r'^\s*TOTAL\s+([0-9,]+)',memory,re.MULTILINE)
    total=re.search(r'Total frames rendered:\s*([0-9,]+)',frames)
    janky=re.search(r'Janky frames:\s*([0-9,]+)',frames)
    tail=re.search(r'99th percentile:\s*([0-9,]+)ms',frames)
    return {
        'pss': pss.group(1) if pss else 'see raw capture',
        'frames': total.group(1) if total else 'see raw capture',
        'janky': janky.group(1) if janky else 'see raw capture',
        'tail': tail.group(1) if tail else 'see raw capture',
    }

before=metrics('before-1920-1080')
after=metrics('after-1920-1080')
(root/'performance-summary.md').write_text(
    '# Home performance capture\n\n'
    'Android TV API 36 at 1920×1080 and 320 dpi. Each build starts from the same seeded favorites and Continue Watching fixture, then receives the same six D-pad moves with a 1.9-second settle interval. Values are diagnostic evidence, not a device-independent benchmark.\n\n'
    '| Build | Total PSS (KB) | Frames rendered | Janky frames | 99th percentile (ms) |\n'
    '| --- | ---: | ---: | ---: | ---: |\n'
    f"| Base | {before['pss']} | {before['frames']} | {before['janky']} | {before['tail']} |\n"
    f"| Head | {after['pss']} | {after['frames']} | {after['janky']} | {after['tail']} |\n"
)
PY
git rev-parse HEAD > "$output/tested-commit.txt"
printf '%s\n' 'Android TV API 36 emulator. Captured UI sizes: 1280x720 at 213 dpi, 1920x1080 at 320 dpi, and 3840x2160 at 640 dpi, preserving a comparable TV layout scale. Coral/Ocean/Mint/Mono and Midnight Drive are synthetic debug-only fixtures, not third-party services or personal media. Scenery is the native fallback; location is unset. Screenshots and recording are actual app captures.' > "$output/README.txt"
