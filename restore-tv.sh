#!/bin/zsh
set -eu
TV_DEVICE="${1:?Usage: $0 <adb-serial>}"
adb -s "$TV_DEVICE" shell cmd package install-existing --user 0 com.tcl.esticker
adb -s "$TV_DEVICE" shell cmd package install-existing --user 0 com.tcl.exhibit
for package in com.tcl.waterfall.overseas com.tcl.smartalexa com.tcl.magiconnectfree com.tcl.usercenter com.tcl.messagebox; do
 adb -s "$TV_DEVICE" shell pm enable --user 0 "$package"
done
adb -s "$TV_DEVICE" shell settings put global window_animation_scale 1.0
adb -s "$TV_DEVICE" shell settings put global transition_animation_scale 1.0
adb -s "$TV_DEVICE" shell settings put global animator_duration_scale 1.0
adb -s "$TV_DEVICE" shell pm enable --user 0 com.google.android.apps.tv.launcherx
adb -s "$TV_DEVICE" shell pm enable --user 0 com.google.android.tungsten.setupwraith
adb -s "$TV_DEVICE" shell cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity
adb -s "$TV_DEVICE" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
# Stop only the optional Glide RAM helper, if it is running.
MEMORY_HELPER_PID="$(adb -s "$TV_DEVICE" shell pidof glide-memory | tr -d '\r' || true)"
if [[ -n "$MEMORY_HELPER_PID" ]]; then
 for helper_pid in ${(s: :)MEMORY_HELPER_PID}; do
  [[ "$helper_pid" == <-> ]] && adb -s "$TV_DEVICE" shell kill "$helper_pid"
 done
fi
