# Sony BRAVIA BF1 — CleanTV Setup & Maintenance Guide

This document describes the debloat, launcher customization, and restoration procedures applied to the **Sony BRAVIA BF1** (Google TV 12, Build `STT2.230811.001`, MediaTek).

---

## 1. Summary of Changes Applied

### Debloated / Disabled Packages
The following OEM bloatware, telemetry, and preloaded unused apps were disabled using `pm disable-user --user 0` (never uninstalled, fully reversible):
- `com.google.android.apps.tv.launcherx` (Stock Google TV home with sponsored recommendation rows)
- `com.google.android.tungsten.setupwraith` (Setup wizard claiming HOME intent)
- `com.google.android.apps.tv.dreamx` (Ambient mode screensaver with ad cards)
- `tv.samba.ssm` (Samba TV ACR ad targeting tracking)
- `com.sony.dtv.promos` (Sony promotional banners)
- `com.sony.dtv.sonyselect` (Sony Select sponsored apps)
- `com.google.android.feedback` (Google feedback reporter)
- `com.sony.dtv.demosupport`, `com.sony.dtv.demosystemsupport`, `com.sony.dtv.multiscreendemo` (Store demo modes)
- `com.sonypicturescore` (Sony Pictures Core promotional app)
- `com.google.android.videos` (Google TV Movies)
- `com.google.android.youtube.tvmusic` (YouTube Music)
- `com.google.android.play.games` (Play Games)

### Preserved Features & System Components
As requested:
- **Streaming & Media Apps kept**: Netflix, YouTube, Prime Video, Disney+ Hotstar, Apple TV, Sony LIV, Spotify, Crunchyroll, Zee5, MX Player, PlayStation Remote Play.
- **System Services kept**: Google Assistant / Voice Search (`com.google.android.katniss`, `com.sony.dtv.voicesearch`), Chromecast built-in (`com.google.android.apps.mediashell`), Sony Update Center (`com.sony.gaus.updatecenter`), AirPlay (`com.sony.dtv.airplayapp`), Live TV / HDMI Inputs (`com.sony.dtv.smartui`).

### Private DNS (Ad-Blocking)
- Configured to **AdGuard DNS** (`dns.adguard-dns.com` under `hostname` mode).
- Blocks network-wide telemetry and tracker queries on the TV.

### Launcher Installed
- Native lightweight Apple TV style launcher (`com.gathin.tvhome`, version 3.2, 574 KB).
- **Dock row**: 4 slots (Netflix, Prime Video, YouTube, Disney+ Hotstar).
- **Screensaver**: Directed to the launcher's minimalist ambient clock (`com.gathin.tvhome/.ClockDream`).

---

## 2. Backup & Restoration

Full backups of the original state are preserved in the `backup/` directory:
- `backup/packages-full.txt`: Complete package manifest prior to modifications.
- `backup/packages-disabled.txt`: Initial disabled package list.
- `backup/getprop.txt`: Device hardware and build properties.
- `backup/settings-system.txt`, `settings-secure.txt`, `settings-global.txt`: Full Android settings dumps.

### How to Restore Everything to Stock
To restore the stock Google TV launcher and re-enable all debloated apps:
```bash
./restore-sony-bf1.sh
```
Or manually run:
```bash
tools/venv/bin/python3 tools/tv.py 'pm enable --user 0 com.google.android.apps.tv.launcherx'
tools/venv/bin/python3 tools/tv.py 'pm enable --user 0 com.google.android.tungsten.setupwraith'
tools/venv/bin/python3 tools/tv.py 'pm enable --user 0 com.google.android.apps.tv.dreamx'
tools/venv/bin/python3 tools/tv.py 'cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity'
tools/venv/bin/python3 tools/tv.py 'settings put global private_dns_mode opportunistic'
```

### How to Re-enable Any Individual App
To re-enable any specific app at any time, run:
```bash
TV_HOST=192.168.0.140 tools/venv/bin/python3 tools/tv.py 'pm enable --user 0 <package_name>'
```
For example, to re-enable Sony Pictures Core:
```bash
TV_HOST=192.168.0.140 tools/venv/bin/python3 tools/tv.py 'pm enable --user 0 com.sonypicturescore'
```

---

## 3. Customizing the Launcher

### Changing Dock Apps & Order
Edit `launcher/app/src/main/res/values/config.xml`:
```xml
<string-array name="dock_apps">
    <item>com.netflix.ninja</item>
    <item>com.amazon.amazonvideo.livingroom</item>
    <item>com.google.android.youtube.tv</item>
    <item>in.startv.hotstar</item>
</string-array>
```
To change to other apps (e.g., Apple TV or Spotify), replace the package names with:
- Apple TV: `com.apple.atve.androidtv.appletv`
- Spotify: `com.spotify.tv.android`
- Sony LIV: `com.sonyliv`
- Crunchyroll: `com.crunchyroll.crunchyroid`

After editing, rebuild and push:
```bash
./build-and-install-sony-bf1.sh
```

### Changing Themes or Wallpaper
The **sony-ambient** theme is currently active on the TV (rotating through the TV's official high-resolution ambient screensaver backdrops every 5 minutes across both Home and Screensaver).

### Customizing Wallpaper & Visual Effects
In `themes/sony-ambient/config.json`:
- `"ambient_glow": false` — Disables the app-color ambient glow/fade behind the dock, keeping the original wallpaper colors 100% natural.
- `"app_glow": false` — Keeps tile focus highlights neutral without tinting background graphics.
- `"wallpaper_dim": 0` — Shows the wallpaper at full original brightness without dark shading.
- `"wallpaper_rotation_minutes": 1` — Rotates through the ambient collection every minute.

To switch to other themes:
```bash
TV_HOST=192.168.0.140 tools/theme.sh themes/sony-ambient
TV_HOST=192.168.0.140 tools/theme.sh themes/nature-calm
TV_HOST=192.168.0.140 tools/theme.sh themes/glass
TV_HOST=192.168.0.140 tools/theme.sh themes/cinematic
TV_HOST=192.168.0.140 tools/theme.sh themes/aurora
```
To reset back to the pitch-black Apple TV minimalist layout:
```bash
TV_HOST=192.168.0.140 tools/theme.sh --reset
```
To set a custom wallpaper:
```bash
TV_HOST=192.168.0.140 tools/venv/bin/python3 tools/b64push.py /path/to/wallpaper.jpg /sdcard/wallpaper.jpg
TV_HOST=192.168.0.140 tools/venv/bin/python3 tools/tv.py 'am broadcast -a com.gathin.tvhome.RELOAD'
```

---

## 4. Closing ADB & Securing the TV (Important!)

Leaving ADB port 5555 open allows any device on your local Wi-Fi to access the TV shell. Once you have verified the TV:

1. **On your Sony TV**:
   - Go to **Settings → System → About → Status** (or **Developer options**).
   - Turn **OFF** **Network / ADB debugging** (or **Wireless debugging**).
   - Turn **OFF** **USB debugging**.
   - Select **Revoke USB debugging authorizations**.
2. **Verify port closure from your terminal**:
   ```bash
   TV_HOST=192.168.0.140 tools/venv/bin/python3 tools/adb-closed.py 192.168.0.140
   ```
   The tool will confirm `port 5555: connection refused`.
