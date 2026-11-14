#!/usr/bin/env bash
set -euo pipefail

TV_HOST="${TV_HOST:-192.168.0.157}"
DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Connecting to $TV_HOST ==="
adb connect "$TV_HOST:5555"

echo "=== Pushing Live Sky Theme Config ==="
adb -s "$TV_HOST:5555" push "$DIR/themes/living-sky/theme.json" /sdcard/Android/data/com.gathin.tvhome/files/config.json
adb -s "$TV_HOST:5555" shell am broadcast -a com.gathin.tvhome.RELOAD
adb -s "$TV_HOST:5555" shell "am force-stop com.gathin.tvhome && am start -n com.gathin.tvhome/.MainActivity"

echo "=== Live Sky is active on $TV_HOST! ==="
