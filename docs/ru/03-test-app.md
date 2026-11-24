[English](../03-test-app.md) | **Русский**

# Тестовое приложение

`io.github.nikonektik.arcoretoolkit` — один APK с четырьмя экранами.

## AR-тест (главный экран)

* Полноэкранный `GLSurfaceView` с камерой ARCore как фоном.
* HUD: состояние трекинга, число плоскостей и якорей, активный профиль.
* Тап ставит объект: hit-test по плоскости → instant placement → фиксированная поза
  в 1 м перед камерой (запасной вариант, чтобы тестировать без плоскостей).
* Четыре кнопки:

| Кнопка | Что делает |
|---|---|
| **Profiles...** | браузер профилей (см. ниже) |
| **Device info / intrinsics** | отчёт по камере и IMU, вычисленные fx/fy |
| **Marker AR** | прототип AprilTag (Camera2 + OpenCV) |
| **Calibration** | запись камеры + IMU для Kalibr |

> В приложении по умолчанию прописан сервер профилей
> `https://nikonekt.ru:9444/arcore/switch/`. Кнопка **Server URL...** переключает
> на ваш собственный.

### Диалог Profiles

Открывается кнопкой **Profiles...**; список берётся с сервера
(`GET <base>profiles`, см. [02-profiles.md](02-profiles.md)):

```
Server: https://host/arcore/switch/
Device: <ro.product.device>  key: <obf>
Camera: fx 464.5, fy 464.2

[ Auto: match this camera ]
[ Advanced tuning... ]
[ Server URL... ]
chopin_patched (fx 464.6) ★
viotune (fx 464.6)
...
```

* профиль, чей фокус ближе всего к камере этого телефона, помечен **★**;
* тап по профилю скачивает его (`<base><name>/<version>.zip`) и перезапускает ARCore;
* **Auto: match this camera** читает заднюю камеру, считает
  `fx = f_mm / sensor_width_mm × 640`, выбирает ближайший профиль каталога и
  запрашивает кастомный профиль с оптикой **этого** телефона и его ключом
  (`custom/<version>.zip?base=...&fx=...&dev=...`). В итоге берётся IMU/тайминги
  донора, но точная оптика вашего телефона;
* **Advanced tuning...** — полный редактор параметров (спиннер базы + все поля).
  Пустое поле оставляет значение базового профиля.

### Восстановление после несовместимого профиля

Некоторые профили создают сессию, но камера их не принимает (`FatalException` в
`Session.resume()`) — обычно это профиль телефона с другим camera pipeline. Тогда
приложение показывает:

```
Profile rejected by ARCore
ARCore created the session but could not start the camera with this profile.
[Re-apply <последний рабочий профиль>]  [Profiles...]  [Close]
```

Последний профиль, который реально запустился, запоминается — одно нажатие
возвращает рабочее состояние. `flame` (Pixel 4) — известный пример профиля, который
так ломает POCO X6 Pro.

### Device info / intrinsics

Читает Camera2 характеристики задней камеры и показывает:

```
active array: 5632x4224
physical size: 5.640 x 4.230 mm
focal length: 3.620 mm
=> for 640x480: fx=410.8 fy=410.8 (HFOV 75.8 deg, VFOV 60.5 deg)
   arcore-profile patch base.pb -o out.pb --fx 410.8 --fy 410.8 --cx 320 --cy 240
```

Это и есть число, которое нужно профилю: CPU-кадр ARCore — 640×480, и если поток
использует всю ширину сенсора, `fx = f_mm / sensor_width_mm × 640`. Экран также
показывает сенсоры акселерометра/гироскопа (вендор, частота) и фабричные
`LENS_INTRINSIC_CALIBRATION`/`LENS_DISTORTION`, если вендор их отдаёт (обычно нет).

Кнопка **Copy as text** копирует отчёт целиком.

## Прототип маркерного AR

Самостоятельная activity на Camera2 + OpenCV, без ARCore:

* поток `YUV_420_888` 640×480, преобразование в Bitmap для показа,
* детекция ArUco `DICT_APRILTAG_36h11`,
* `solvePnP` для маркера 150 мм, на экране ошибка репроекции и дистанция,
* куб на маркере.

Это эталон ориентации камеры (сенсор повёрнут на 90° по часовой для портрета) и
быстрый способ проверить читаемость маркера до отладки шима.

## Запись для калибровки

Пишет набор для Kalibr:

* Camera2, mono **1280×960**, ~20 fps, JPEG-кадры + `frames.csv`;
* гироскоп + акселерометр на `SENSOR_DELAY_FASTEST` (~400 Гц) в `imu.csv`;
* `meta.txt` с характеристиками камеры.

Сессии лежат в `files/calib/<session>/`. Вытащить:

```bash
adb exec-out run-as io.github.nikonektik.arcoretoolkit tar -c files/calib > calib.tar
```

## Сборка

`android/build.sh` делает всё без Gradle:

1. скачивает AAR `com.google.ar:core:1.57.0` и `org.opencv:opencv:4.9.0` в
   `android/deps/`;
2. `javac` (Java 8) против `android.jar` + AAR;
3. `d8` → `classes.dex` (библиотечные классы тоже включаются);
4. `aapt2 link` → базовый APK;
5. добавляет `classes.dex` и нативные библиотеки arm64-v8a;
6. `zipalign` + `apksigner` со сгенерированным debug-ключом.

Переменные: `ANDROID_SDK_ROOT`, `ANDROID_NDK_ROOT`, `AR_VERSION`,
`OPENCV_VERSION`, `OUT`, `KS`.

## Замечания

* APK только arm64-v8a. Другие ABI — скопируйте соответствующие `jni` в `build.sh`.
* UI местами русско/английский — строки в обычных Java-файлах, легко локализуются.
