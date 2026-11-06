#!/usr/bin/env bash
# Build and install script for Sony BRAVIA BF1 (Google TV 12)
set -euo pipefail

TV_HOST="${TV_HOST:-192.168.0.157}"
DIR="$(cd "$(dirname "$0")" && pwd)"
PY="$DIR/tools/venv/bin/python3"

export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

echo "=== Building Launcher APK ==="
cd "$DIR/launcher"
./gradlew assembleRelease --no-daemon

APK="$DIR/launcher/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
    echo "Build failed: $APK not found." >&2
    exit 1
fi

echo "=== Pushing and Installing on $TV_HOST ==="
cd "$DIR"
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/b64push.py" "$APK" /data/local/tmp/tvhome.apk
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/tv.py" 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'

echo "=== Optimizing & Setting Permissions ==="
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/tv.py" 'cmd package compile -m speed -f com.gathin.tvhome; pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS 2>/dev/null'

echo "=== Ensuring Home is Active ==="
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/tv.py" 'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity'
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/tv.py" 'am start -W -n com.gathin.tvhome/.MainActivity'

echo "=== Installed and verified on Sony BRAVIA BF1! ==="
