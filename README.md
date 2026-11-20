# Sonata — Arkadia Voice App

Android 10+ **fully offline** neural voice app for the Arkadia Intelligence System
(ARKANA).

> **Architecture note.** Sonata is offline-only. There is no online TTS tier, no
> STT, no cloud voice service and no API key. The voice is the user's own, cloned
> and trained **on the device**. See `docs/evolution/E10-OFFLINE-PHASES-1-7.md` for
> the implementation and `docs/evolution/E11-S0-DEVICE-HANDOFF.md` for the device
> validation that gates on-device training.

## Features

| Feature | Detail |
|---|---|
| **PROCESS_TEXT** | Appears as "Speak with Sonata" in every app's text-selection menu |
| **Floating Overlay** | Draggable black-glass player — play, pause, stop, speed, close |
| **Foreground Service** | Notification with pause/resume/stop — audio continues in background |
| **Offline neural TTS** | On-device model behind one swappable runtime abstraction — no network |
| **Voice Studio** | Guided enrollment, on-device adapter training, generation in your own voice |
| **Read-Along Canvas** | Cleaned text with sentence-level sync and confidence-gated word highlighting |
| **Narration Intelligence** | Six-stage planning pipeline with eight delivery styles |
| **Library** | Every generation saved automatically; rename, favourite, collect, export |
| **Export** | WAV / M4A / MP3 via MediaStore and SAF, single or batch |
| **Settings** | Voice, speed, pitch, auto-read, storage management |
| **Performance** | Cold start < 1 s · Speech start < 2 s · No wakelock abuse |

## Building

### Requirements
- Android Studio Hedgehog (2023.1) or newer
- JDK 17
- Android SDK 34 (API 29 min)

### Debug build
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Release build (unsigned)
```bash
./gradlew assembleRelease
```

### CI — GitHub Actions

Every push to `main` or `feature/**` triggers `.github/workflows/build-apk.yml`.

**To enable APK signing**, add these GitHub Secrets to the SONATA repo:

| Secret | How to generate |
|---|---|
| `SIGNING_KEY` | `base64 -w 0 your-keystore.jks` |
| `KEY_ALIAS` | Alias used when creating the keystore |
| `KEY_STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password |

Generate a keystore (one-time):
```bash
keytool -genkey -v -keystore sonata-release.jks \
  -alias sonata -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Sonata, OU=Arkadia, O=Arkadia Intelligence Systems, L=Pankshin, ST=Plateau, C=NG"
```

## Configuration

Sonata is fully offline. There is no backend URL to configure and no API key to
enter. Voice models are bundled or side-loaded from device storage; nothing is
downloaded at runtime.

## Permissions

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Floating overlay |
| `FOREGROUND_SERVICE` | Background playback |
| `POST_NOTIFICATIONS` | Playback notification (Android 13+) |
| `RECORD_AUDIO` | Voice enrollment samples |
