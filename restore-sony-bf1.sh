#!/usr/bin/env bash
# Restore script for Sony BRAVIA BF1 (Google TV 12)
# Re-enables stock Google TV launcher, setup wizard, ambient mode, debloated apps,
# and resets Private DNS back to default.
set -euo pipefail

TV_HOST="${TV_HOST:-192.168.0.157}"
DIR="$(cd "$(dirname "$0")" && pwd)"
PY="$DIR/tools/venv/bin/python3"

if [ ! -x "$PY" ]; then
    echo "Python venv not found at $PY. Please run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2
    exit 1
fi

echo "=== Restoring Sony BRAVIA BF1 on $TV_HOST ==="

# 1. Re-enable stock launcher & core services
echo "-> Re-enabling stock launcher and Google TV components..."
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/tv.py" 'pm enable --user 0 com.google.android.apps.tv.launcherx'
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/tv.py" 'pm enable --user 0 com.google.android.tungsten.setupwraith'
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/tv.py" 'pm enable --user 0 com.google.android.apps.tv.dreamx'

# 2. Set stock launcher back as Home
echo "-> Setting stock launcher back as Home..."
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/tv.py" 'cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity'

# 3. Re-enable preloaded apps disabled in this session
echo "-> Re-enabling debloated preloaded apps..."
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/tv.py" 'pm enable --user 0 com.sonypicturescore' || true
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/tv.py" 'pm enable --user 0 com.google.android.videos' || true
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/tv.py" 'pm enable --user 0 com.google.android.youtube.tvmusic' || true

# 4. Revert Private DNS to opportunistic (default)
echo "-> Reverting Private DNS to default (opportunistic)..."
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/tv.py" 'settings put global private_dns_mode opportunistic'

# 5. Revert screensaver
echo "-> Restoring default screensaver..."
TV_HOST="$TV_HOST" "$PY" -I "$DIR/tools/tv.py" 'settings put secure screensaver_components com.google.android.apps.tv.dreamx/.DreamActivity' || true

echo "=== Restore completed successfully! ==="
echo "Home is now restored to stock Google TV launcher."
