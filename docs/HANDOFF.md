# Rokid Glasses RV101: firmware notes and app work

State as of 2026-11-27 (camera 2.10.0-ru, input test 1.4). This document carries the context to a
new project or Claude Code session: "read docs/HANDOFF.md" and the session is up to date.
Chat with the owner is in Russian; everything in the repository is in English.

---

## 1. Hardware and environment

| What | Value |
|---|---|
| Glasses | Rokid Glasses **RV101** |
| Firmware | **1.29.013-20261110-150201**, YodaOS-Sprite |
| Firmware auto-update | on → **turn it off** while developing |
| Clock on the glasses | Beijing time (UTC+8); file names are +3 h vs Aktau |
| App platform | Android 12 (API 32), HUD 480×640 portrait, monochrome green |
| Ring | Rokid Ring **RGR06** (scroll wheel + buttons) |
| Extra power | 2 magnetic temple batteries |
| Phone | Samsung Galaxy S25+, Android 17, One UI 9.0 |
| Companion app | Hi Rokid (Google Play) |
| Installing APKs | Hi Rokid → Tools → Glasses app management → Install |
| Built-in AI models | Auto, Gemini, DeepSeek + one more (no ChatGPT, no Claude) |
| Developer cable | 5-pin, ordered (not in the box), expected early December |
| Laptop | dual boot Ubuntu / Windows (2× 4 TB SSD); work on Ubuntu + Claude Code |

Camera: **no optical zoom**, digital (crop) only. The system camera saves 3024×2268; the sensor
gives up to 4032×3024. The camera picture is upright only when turned 90° (sensor orientation 90),
so its native video is 9:16 portrait.

---

## 2. Why the original ZOOM IN CAMERA did not work

Original: [ksuzukigh/rokid-zoom-in-camera](https://github.com/ksuzukigh/rokid-zoom-in-camera)
v1.2.1 (commit `c72eb42`, MIT).

Symptoms: zoom visible in preview but files saved at 1×; preview did not come back after a photo;
video had no preview, only a timer.

**Cause (confirmed):** photos and videos were taken by the glasses' **system camera service**, not the app.
- The app asks the system to disable the button (broadcast to `com.rokid.os.sprite.assistserver`,
  keys `settings_interaction_shortPressFun` / `longPressFun` → `none`). **Firmware 1.29 ignores it.**
- The ring's photo button talks to the system camera directly; the app never sees it.
- The system camera takes the camera from the app → the preview freezes.

---

## 3. This repository

| Module | App | ID |
|---|---|---|
| `app` | **ZOOM Камера RU** 2.10.0-ru | `io.github.ksuzukigh.rokidzoomincamera.ru` (next to the original) |
| `inputtest` | **Тест кнопок и кольца** 1.4 | `kz.rokid.inputtest` |

Branch `v2.1`, PR #1 into `main` (one commit per version, 2.1.0 … 2.7.1). See CHANGELOG.md.

### Input facts (input tests 1.1 / 1.3)
| Source (InputDevice) | Signals |
|---|---|
| Temple `ROKID,PSOC-TP-R` | KEY ENTER (tap), NOTIFICATION(83) sc=204, BACK(4). **No touch events.** |
| Ring `RGR06 Keyboard` | arrows (wheel), ENTER, NOTIFICATION(83) sc=59, BACK(4), MEDIA_PLAY_PAUSE |
| Glasses button | broadcast `ACTION_SPRITE_BUTTON_*` + system camera |

- Every gesture is preceded by NOTIFICATION.
- Tap = ENTER; double tap = ENTER×2 or BACK (system exit, never use it).
- Swipe forward = DPAD_RIGHT + DPAD_DOWN, back = DPAD_LEFT + DPAD_UP; the wheel sends the same in bursts.
- Hold on the temple = NOTIFICATION only (inconvenient, the owner does not want it).
- Wheel press = MEDIA_PLAY_PAUSE + ENTER. It can also reach the phone's media player over Bluetooth.
- Ring has two mouse-like buttons: **left** = system camera (like the glasses button), **right** = ENTER.
- Temple and ring send the same codes; only the device name differs.
- The owner prefers the ring over temple gestures.

### Camera controls (2.5.0+)
Wheel press = photo / OK / stop recording; wheel turn = zoom (0.1× steps) / select; right ring
button = menu and settings, back in menus; temple tap = photo / OK fallback; Back = menu, Back in
the menu = exit.

### Key design points
- Photo: full sensor frame captured, zoom + crop in software (`PhotoProcessor`, `PhotoCrop`);
  aspects are width/height as viewed, EXIF rotation respected.
- Video: native path = MediaRecorder surface + orientation hint. Other shapes = `VideoGlRenderer`
  (camera → SurfaceTexture → GLES2 → recorder surface). On RV101 the SurfaceTexture matrix already
  contains the 90° turn; `VideoGeometry.residualRotation` adds a turn only when it does not.
- `InputArbiter` dedupes key pairs (280 ms in menus, 90 ms for zoom).
- An active `MediaSession` swallows media keys while the camera is open.
- `CrashLog` writes the trace on crash and copies it to Download on the next start.

### Build
- GitHub Actions (`.github/workflows/android.yml`) → artifact `rokid-zoom-ru-apk` with both APKs.
- Local: JDK 17 + Android SDK 35, `./gradlew test assembleDebug`.
- Shared signing key `keystore/debug.keystore` (password `android`).
- Cloud sessions cannot reach dl.google.com, so APKs are built without Gradle: aapt → javac
  `--release 8` against android.jar (API 32) → dx → zipalign → apksigner. **No lambdas** in code.
- Unit tests: 58 (pure-Java classes).

Key files: `MainActivity.java` (camera, input, menus, help), `PhotoProcessor.java`, `PhotoCrop.java`,
`CaptureOptions.java` (settings model), `VideoGeometry.java`, `VideoGlRenderer.java`,
`FrameOverlayView.java`, `InputArbiter.java`, `MediaLabels.java`, `CrashLog.java`,
`inputtest/.../MainActivity.java`.

## 4. Open checks

1. Camera 2.7.1: 16:9 and other video shapes must not be rotated in the saved file; zoom while recording.
2. Whether the wheel press still starts the phone's audiobook (if yes, the ring is likely paired to the phone directly).
3. Any crash report from `Download/ZoomCamera_crash_*.txt`.
4. Turn off firmware auto-update.

---

## 5. Backlog

Agreed order: camera → translator → navigation → HUD for the PFD. Assistant and teleprompter in parallel.

### 5.1 Translator (camera text + live speech)
- **No AR overlay** (small display, camera off the eye axis) → **text**.
- Camera: "under the crosshair" mode (~2 updates/s) and "freeze frame" (list original → translation, scroll with the wheel), zoom.
- Speech, issues with the stock app: text scrolls away, new words reset scrolling, noise. Fixes:
  scrolling not reset by new text, push-to-talk, noise suppression, VAD, strong ASR,
  AI translation zh→ru with context, reply by voice / on the phone.
- Backends: offline ML Kit (OCR zh v2 + zh↔ru); online Claude/DeepSeek/Gemini.
  **In China** Google/Claude/Gemini are blocked → download offline models in advance; online DeepSeek/Qwen, ASR Alibaba/iFlytek.

### 5.2 Claude assistant on the glasses
- No built-in Claude. Options: AIUI Studio agent (JS), own app (speech → Claude API → HUD), phone bridge (CXR SDK).
- Start by tap (the "Hi Rokid" wake phrase handles accents poorly), "what do I see" from the camera, Russian.
- Needs a separate Claude API key (a claude.ai subscription does not work).
- Bridge to Claude Code (approvals / agent status on the glasses): claude-bridge-glasses, Rokid_Claude, rokid-glasses-bots.

### 5.3 Teleprompter with line-by-line scrolling
- The stock teleprompter pages only. Own app: one wheel click = one line. Send text from the computer over Wi-Fi.

### 5.4 Navigation
| Source | Aktau | Kaliningrad | China | Access |
|---|---|---|---|---|
| OpenStreetMap (OSRM, offline) | fair | good | big cities, offline | free |
| Google (Routes/Places) | good | good | blocked | API key, quota |
| 2GIS Mobile SDK | excellent | excellent | no | key on request |
| Yandex MapKit/NaviKit | good | excellent | no | navigation from ~$4800/month |
| Amap | no | no | best | Chinese developer account |

Ready-made: **Rokid-GMaps** (OSM + optional Google), **Rokid-Maps**. Plan: base on Rokid-GMaps, optional 2GIS; offline OSM for China.

### 5.5 HUD for the PFD (pfd_dev project)
- Glasses as a head-up display: speed, altitude, heading, vario, roll/pitch, next waypoint.
- **GDL90** over Wi-Fi/UDP (EFB standard: ForeFlight, SkyDemon, EasyVFR). Watchdog: "НЕТ ДАННЫХ" when the stream stops.
- Navigation computed on the Pi (OpenAIP), the glasses get ready numbers. Debug without the PFD: X-Plane → bridge → GDL90 → glasses.
- For functional checks only; the certified instruments stay primary.
- Hardware: CM5 4 GB / 32 GB eMMC, no Wi-Fi; carrier Waveshare CM5-IO-BASE-B. Link to the glasses:
  **ESP32-S3 (Seeed XIAO ESP32S3)** on UART from the STM32 → Wi-Fi AP → GDL90 (2 pcs ordered, delivery Dec 13–19).
  Optional Wi-Fi CM5 (CM5104032) + antenna kit; bench: Pi 5 with Wi-Fi.

### 5.6 Glasses ↔ computer without the phone
- Phone needed once: developer mode + Wi-Fi. Then USB ADB (5-pin cable) or ADB over Wi-Fi;
  **scrcpy** for the screen, `adb install`, `adb logcat`, `adb pull`. Ubuntu: `sudo apt install adb`.

---

## 6. Resources

- Project list: [awesome-rokid](https://github.com/Anezium/awesome-rokid)
- APK installer: [Rokid-APKs](https://github.com/Anezium/Rokid-APKs)
- YodaOS docs (unofficial): [rokid-docs](https://github.com/buildwithfenna/rokid-docs)
- Cameras: [ZOOM IN CAMERA](https://github.com/ksuzukigh/rokid-zoom-in-camera), [Warlock Camera](https://github.com/Warlock94/WarlockCamera)
- Ring R08 → arrows: [R08-Access-Bridge](https://github.com/Anezium/R08-Access-Bridge)
- Notifications: [Rokid Relay](https://github.com/Anezium/Rokid-Relay); store: [RokidBrew](https://github.com/Anezium/RokidBrew)
- Navigation: [Rokid-GMaps](https://github.com/Anezium/Rokid-GMaps), [Rokid-Maps](https://github.com/chartmann1590/Rokid-Maps)
- Claude: [claude-bridge-glasses](https://github.com/markossssssss/claude-bridge-glasses),
  [Rokid_Claude](https://github.com/williamlzz/Rokid_Claude), [rokid-glasses-bots](https://github.com/flaibot/rokid-glasses-bots)
- Mac control: [rokid-mac-control](https://github.com/ksuzukigh/rokid-mac-control)

## 7. Known limits

- Battery: under 2 h with AI/video active. Weak camera in the dark.
- Each firmware update can break third-party apps; re-check after updating.
- Rebuilt APKs are signed with the debug key; the original app is untouched (different ID).
