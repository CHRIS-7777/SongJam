# songjam — Local Music Jam (Pure APK, No Cloud)

> Open app → **Stream** | **Join**. Stream auto-starts server silently in bg + lists local songs. Join via IP:port + PIN. Host play → everyone plays in sync. 100% offline hotspot.

## Flow
1. Open `songjam` → Home: **Start a Jam (Stream)** + **Join a Jam**
2. Tap **Stream** → `SongJamNative.startServer({port:8080})` starts `JamService` ForegroundService silently → songs list from `MediaStore`
3. Top card shows **Jam address `192.168.43.1:8080` + Host PIN** (persisted, stable across launches)
4. Friends → **Join** → enter `IP:port` → Connect
5. Host taps song / Play / Next → `POST /api/state` → joiners re-sync (`>400ms` seek, `150-400ms` 0.97x–1.03x, `50-150ms` = perfect on earphones)

## Install (APK)
Push to GitHub → Actions `build-apk` → download `songjam-debug-apk/app-debug.apk` → install → grant Music + Notifications → ON Hotspot → tap Stream.

```bash
git remote add origin https://github.com/CHRIS-7777/SongJam.git
git push -u origin main
```

## Layout (pure APK — no python)
```
songjam/
  www/                        # Capacitor webDir
    index.html                # Home > Stream | Join
    app.js                    # host + join sync
    native-bridge.js          # auto-start bg server bridge
    style.css                 # offline system fonts
    manifest.json sw.js icons/
  capacitor.config.json       # appId com.chris7777.songjam, appName songjam
  package.json                # @capacitor/* v6
  android-patch/              # applied AFTER `npx cap add android` in CI
    app/src/main/java/.../JamService.java    # bg NanoHTTPD: /api/* + /stream Range + PIN
    app/src/main/java/.../MainActivity.java  # SongJamNative JS bridge + pre-warm
    app/src/main/res/xml/network_security_config.xml
  .github/workflows/build-apk.yml
  Music/.gitkeep              # placeholder only — songs stay on device
```

API (APK service):
- `GET /api/info` → `{ip,port,pin,songs}`
- `GET /api/songs` → `{songs:[{id,name,file,size}]}` (from MediaStore)
- `GET /api/time` → `{t}`
- `GET /api/state` → `{songId,playing,position,serverTime}`
- `POST /api/state {songId,playing,position,pin}`
- `GET /stream/<mediaId>` with `Range:` support

Permissions: `INTERNET, READ_MEDIA_AUDIO, FOREGROUND_SERVICE, ACCESS_WIFI_STATE, POST_NOTIFICATIONS` + cleartext LAN allowed.
