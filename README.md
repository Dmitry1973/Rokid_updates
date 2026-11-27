# ZOOM Camera RU for Rokid Glasses RV101

An extended fork of [Rokid ZOOM IN CAMERA](https://github.com/ksuzukigh/rokid-zoom-in-camera)
(by Keiichi Suzuki, MIT license), plus a test app for the glasses' buttons and the ring.
The on-glasses interface is in Russian; code, docs and history are in English.

The project builds two APKs:

| Module | App on the glasses | Purpose |
|---|---|---|
| `app` | **ZOOM Камера RU** (2.8.0-ru) | camera with smooth digital zoom, photo and video formats, resolution and fps choice |
| `inputtest` | **Тест кнопок и кольца** (1.4) | shows which signals the temple touchpad, the button and the RGR06 ring send |

The camera installs **next to** the original app (different application ID).

## Features (current version)

- **Ring works like a mouse, no duplicate actions:** press the wheel = photo / OK in menus /
  stop recording; turn the wheel = zoom / move in menus; right ring button = menu and settings
  (back inside menus). Without the ring: temple tap = photo / OK, swipe = zoom, Back = menu.
- **Smooth zoom** 1×–4× in 0.1× steps (0.2 / 0.5 selectable), also while recording video.
- **Photo formats with orientation:** full frame, 16:9, 9:16, 4:3, 3:4, 3:2, 2:3, 1:1.
  The full sensor frame is captured and zoom + crop are applied in software (`PhotoProcessor`),
  so the file always matches the viewfinder. EXIF rotation is taken into account.
- **Video formats:** native (the camera gives 9:16 portrait on RV101), 16:9, 9:16, 4:3, 3:4, 1:1.
  Non-native shapes are rendered through OpenGL (`VideoGlRenderer`): the camera frame is turned
  upright, cropped and encoded. If a shape fails to start, recording falls back to native.
- **Frame-only view (default):** no camera picture on the HUD, only the outline of what gets
  captured; it shrinks with zoom. «Масштаб рамки» matches it to what the eye sees.
- **Viewfinder shows the real frame (picture view):** everything outside the photo (or video while recording)
  is dimmed. File info at the top: `ФОТО 16:9 · 9.1 МП`, `ВИДЕО 16:9 1072p30`, last file.
- **Media-key guard:** while the camera is open, an active media session swallows the ring's
  PLAY_PAUSE so it does not start a player on the paired phone.
- **Crash report:** after a crash the next start shows a notice and copies the stack trace to
  `Download/ZoomCamera_crash_*.txt`.
- Camera auto-reconnect when the system camera takes it; settings are remembered.

Do not press the glasses' camera button or the ring's **left** button: the system camera takes them.

## Settings (right ring button → «Настройки фото и видео»)

| Item | Values |
|---|---|
| Формат ФОТО (photo format) | full frame, 16:9, 9:16, 4:3, 3:4, 3:2, 2:3, 1:1 |
| Размер ФОТО (photo size) | max, or long side up to 3000 / 2000 / 1280 px |
| Качество JPEG | 100, 95 (default), 90, 85, 80, 70 % |
| Формат ВИДЕО (video format) | native, 16:9 (default), 9:16, 4:3, 3:4, 1:1 |
| Разрешение ВИДЕО | all recorder sizes of the camera (caps the long side) |
| Кадров/с ВИДЕО | what the camera supports (24 / 30 / 60) |
| Съёмка тапом | temple tap takes photos on / off |
| Размер / Высота картинки | viewfinder size 100–50 % and vertical shift |
| Свайп зума | normal / reversed |
| Шаг зума | 0.1 / 0.2 / 0.5 per click |

The menu shows the resulting photo and video size, e.g. `Видео получится: 1920×1072 (16:9)`.

## Building

All builds are signed with the shared key `keystore/debug.keystore` (password `android`),
so new versions install over old ones.

- **GitHub Actions:** every push builds the artifact **rokid-zoom-ru-apk** with both APKs.
- **Android Studio:** open the project, *Build → Build APK(s)*.
- **Command line:** JDK 17 + Android SDK 35, then `./gradlew test assembleDebug`.
- Without Gradle (used in cloud sessions where Google Maven is unreachable): aapt → javac
  `--release 8` against android.jar → dx → zipalign → apksigner. That is why the code has
  **no lambdas or method references**.

## Installing on the glasses

Via the Hi Rokid phone app: **Home → Tools → Glasses app management → Install new app** → pick the APK.

## Input facts on RV101 (firmware 1.29)

The temple touchpad sends no touch events to apps, only keys. Every gesture starts with
NOTIFICATION(83). Tap = ENTER; swipe forward = DPAD_RIGHT + DPAD_DOWN, back = DPAD_LEFT + DPAD_UP
(the ring wheel sends the same in bursts); wheel press = MEDIA_PLAY_PAUSE + ENTER; right ring
button = ENTER; double tap is taken by the system; glasses button and left ring button =
system camera. Ring and temple are told apart only by the InputDevice name (`RGR…`).

## License

MIT, see [LICENSE](LICENSE). Original project © 2026 Keiichi Suzuki.
