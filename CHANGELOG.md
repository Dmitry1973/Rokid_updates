# Changelog

## 2.7.1-ru (2026-11-27)
- Fixed: 16:9 and other non-native video shapes were saved rotated by 90°. The RV101 camera
  already turns the SurfaceTexture picture upright (the turn is in its transform matrix) and
  2.7.0 turned it a second time. The turn is now read from the matrix on the first frame and
  added only if the camera did not do it (`VideoGeometry.residualRotation`, unit-tested for
  every shape and size).

## 2.7.0-ru (2026-11-27)
- New setting «Формат ВИДЕО» (video format): native (as the camera gives it, 9:16 portrait on
  RV101), 16:9, 9:16, 4:3, 3:4, 1:1. Default 16:9 landscape.
- Non-native shapes are recorded through OpenGL: the camera frame is turned, cropped and encoded
  (`VideoGlRenderer`, geometry in `VideoGeometry`). The camera stream is picked so it covers the
  long side chosen in «Разрешение ВИДЕО». If a shape fails, recording retries in native format.
- The viewfinder shows the chosen video frame while recording, and while a video item is
  selected in settings. Menu line «Видео получится: W×H».
- HUD shows `ВИДЕО 16:9 1072p30` instead of a bare resolution; the format is in the file name.

## 2.6.0-ru (2026-11-25)
- Photo formats with orientation: full frame, 16:9 / 9:16, 4:3 / 3:4, 3:2 / 2:3, 1:1. 16:9 is
  always landscape, 9:16 always portrait (EXIF rotation is respected); the viewfinder frame
  matches. The old format choice resets to full frame.
- Smooth zoom: 0.1× per wheel click or swipe (setting «Шаг зума»: 0.1 / 0.2 / 0.5), also while
  recording. 12-cell zoom bar.
- Wheel press should no longer reach the phone's player: while the camera is open the app holds
  an active media session that takes the media keys.
- Crashes: the stack trace is kept and copied on the next start to
  `Download/ZoomCamera_crash_*.txt`; the HUD says «Прошлый запуск: сбой». Out-of-memory while
  saving a photo no longer crashes the app.
- Default viewfinder 90 % and lifted 5 %: the bottom edge moves up, the top stays.
- New icon: a camera with a green lens and a "+" badge.

## 2.5.0-ru (2026-11-25)
- Ring works like a mouse, no duplicates: wheel press = photo / OK in menus / stop recording;
  right ring button = menu and settings, back inside menus; wheel turn = zoom / select.
- Temple tap stays as the no-ring fallback (photo / OK); Back = menu.
- Ring and temple are told apart by device name (`RGR…`); a bare ENTER from the ring waits
  150 ms so it is not confused with a wheel press (PLAY_PAUSE + ENTER).

## 2.4.3-ru (2026-11-25)
- On-screen hints and help use gesture names instead of key names.
- Wheel press opens the menu without taking a photo (the accompanying ENTER is ignored for 500 ms).

## 2.4.2-ru (2026-11-25)
- Menu opens with a ring wheel press (MEDIA_PLAY_PAUSE; the ENTER that comes with it is ignored);
  inside menus a wheel press = OK.
- Opening the menu by holding a finger on the temple was removed (inconvenient, unreliable);
  Back remains the fallback.

## 2.4.1-ru (2026-11-25)
- The viewfinder takes the shape of the chosen format: everything outside the photo (or the video
  while recording) is dimmed.
- Video frame corners are shown only while recording.

## 2.4.0-ru (2026-11-25)
- Top right: «ФОТО 16:9 · 9.1 МП», «ВИДЕО 1080p30» and the last file (MP and size / resolution,
  fps, size, duration).
- While recording: «REC 1080p30 · 45 МБ», the size grows.
- Short resolution labels: 4K, 8K, 1080p, 720p; photos in megapixels (`MediaLabels`).

## 2.3.3-ru (2026-11-25)
- Zoom while recording video: temple swipe or wheel (the camera's digital crop goes into the file).
- Test: every photo format at every zoom step keeps its proportions and centre.

## 2.3.2-ru (2026-11-25)
- Menu also opens by holding a finger on the temple ~1 s (arrives as NOTIFICATION with no key after it).
- «Настройки фото и видео» is the first menu item.
- Top right always shows «ФОТО 16:9 · 4032×2268»: the current format and the next photo size.
- Settings items are named «Формат ФОТО» / «Разрешение ВИДЕО» so photo 16:9 is not confused with video.

## 2.3.1-ru / Input test 1.4 (2026-11-25), from the 1.3 wizard reports
Found the cause of the zoom "dance": one swipe (and one wheel step) sends TWO keys, forward
DPAD_RIGHT + DPAD_DOWN, back DPAD_LEFT + DPAD_UP. The camera treated DOWN as back and UP as
forward, so each swipe cancelled itself. Now RIGHT/DOWN = +1, LEFT/UP = −1, with duplicate
filtering. Temple swipe works again for zoom and menus. MEDIA_PLAY_PAUSE (wheel press) is ignored,
ENTER replaces it. «Свайп зума: обратный» now also applies to the wheel.

Test 1.4: a step advances only after a signal (or after 20 s with none); while the system camera
is open the step waits and gives 3 s after returning.

## 2.3.0-ru / Input test 1.3 (2026-11-25), from the 1.1 test reports
The reports showed: the temple (ROKID,PSOC-TP-R) sends no touch events to apps, only keys
(ENTER, NOTIFICATION sc=204, BACK); the ring (RGR06 Keyboard) sends arrows, ENTER,
NOTIFICATION sc=59, BACK. Temple and ring send the same codes and differ only by device.

Camera:
- Menu on Back (touch hold never arrives); Back inside the menu leaves the app.
- Photo = OK / tap, zoom = wheel (arrows) through `InputArbiter`.

Test 1.3: a step-by-step wizard "do the gesture → signal recorded", table tagged by source
(`/д` temple, `/к` ring), steps advance on time, exit with Back ×3.

Versions 2.2 / test 1.2 were never installed on the glasses (menu and exit relied on touch hold).

## 2.2.0-ru / Input test 1.2 (2026-11-25), second run on the glasses
Camera:
- Double tap removed entirely (the system takes it); menu on a 1 s hold: video, settings, help, exit.
- Zoom: one swipe or one wheel click = exactly one step; duplicates (touch + system arrow) and
  wheel bounce are filtered (`InputArbiter`).
- Zoom steps 1× / 1.5× / 2× / 3× / 4×.
- Setting «Свайп зума: обычный / обратный»; leaving settings by hold.

Input test:
- Back is recorded instead of exiting (shows whether the system sends Back on double tap).
- New check "swipe = touch + key": shows whether the system duplicates a swipe with an arrow key.

## 2.1.0-ru / Input test 1.1 (2026-11-25), after the first run on the glasses
Camera:
- All text (zoom, hint, status, menu) moved to the top of the screen and no longer moves with the picture.
- Large zoom readout «ЗУМ 1.5×» + bar; on change the value flashes in the centre for a second.
- Permanent hint line; the settings menu shows the whole list; new items: photo size, JPEG
  quality, help, close menu; «Фото получится: W×H» line.
- On-glasses help (first start and from the menu).
- Clear message when the system camera took the button or ring.
- New icon (magnifier), not confused with cameras.

Input test:
- Exit by holding a finger on the temple 3 s (with countdown) or Back.
- What arrived is shown large at the top, a checklist below.
- Shows when the system camera intercepted the button.
- Report on exit: image in DCIM/Camera and text in Download.
- New icon (ring).

Build: shared debug key `keystore/debug.keystore`; lambda-free code (builds with Gradle and with plain javac + dx).

## 2.0.0-ru (2026-11-24)
Fork of Rokid ZOOM IN CAMERA 1.2.1:
- Shooting by tap and hold on the temple (works around the system camera taking the button).
- Camera auto-reconnect after interception.
- Software zoom and photo crop: the file always matches the preview.
- Photo formats 4:3, 16:9, 3:2, 1:1.
- Video resolution and frame rate choice.
- Frame overlay on screen.
- 0.5× zoom steps; zoom with the wheel / up-down keys.
- Settings menu on double tap, settings are saved.
- Russian interface.
- Test app «Тест кнопок и кольца».
