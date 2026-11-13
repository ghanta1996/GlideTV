#!/usr/bin/env bash
set -euo pipefail
TV_DEVICE="${1:?Usage: $0 <adb-serial> <previous-home-component> [other-home-components-to-enable...]}"
STOCK_HOME="${2:?Pass the previous launcher component saved before changing Home.}"
shift 2
TV_USER="$(adb -s "$TV_DEVICE" shell am get-current-user | tr -d '\r')"
[[ "$TV_USER" =~ ^[0-9]+$ ]] || { printf '%s\n' 'Could not determine the TV user.' >&2; exit 1; }
for component in "$STOCK_HOME" "$@"; do
 [[ "$component" =~ ^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$ ]] || { printf '%s\n' 'Pass complete package/activity component names.' >&2; exit 1; }
 case "$component" in android/*|com.android.systemui/*|space.glidetv.launcher/*) printf '%s\n' 'Select the previous TV launcher component.' >&2; exit 1;; esac
 adb -s "$TV_DEVICE" shell pm enable --user "$TV_USER" "$component"
done
adb -s "$TV_DEVICE" shell cmd package set-home-activity --user "$TV_USER" "$STOCK_HOME"
adb -s "$TV_DEVICE" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
