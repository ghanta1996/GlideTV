#!/bin/zsh
set -eu
cd "${0:A:h}"
TV_DEVICE="${1:?Usage: $0 <adb-serial>}"
HELPER_DEX="${GLIDE_HELPER_DEX:-build/bridge-dex/classes.dex}"
[[ -f "$HELPER_DEX" ]] || { print -u2 "Build the helper or set GLIDE_HELPER_DEX to the release download."; exit 1; }
adb -s "$TV_DEVICE" push "$HELPER_DEX" /data/local/tmp/glide-memory-helper.dex
HELPER_PID="$(adb -s "$TV_DEVICE" shell pidof glide-memory | tr -d '\r' || true)"
if [[ -n "$HELPER_PID" ]]; then
 for helper_pid in ${(s: :)HELPER_PID}; do
  [[ "$helper_pid" == <-> ]] && adb -s "$TV_DEVICE" shell kill "$helper_pid"
 done
fi
adb -s "$TV_DEVICE" shell 'CLASSPATH=/data/local/tmp/glide-memory-helper.dex app_process /system/bin --nice-name=glide-memory space.glidetv.bridge.MemoryBridge </dev/null >/data/local/tmp/glide-memory-helper.log 2>&1 &'
