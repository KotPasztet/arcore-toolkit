[English](../08-beginner-cases.md) | **Русский**

# Для новичков: печать маркера, установка APK, запуск AR

Пошаговая инструкция для тех, кто раньше не работал с ADB и сборкой Android.
Все команды выполняются из корня репозитория, если не указано иное.

Нужно:

- Linux/macOS (Windows тоже можно, команды аналогичные);
- `git`, Python 3.9+, JDK 17, Android SDK + NDK (для приложения/шима);
- телефон с включённой отладкой по USB или беспроводным ADB;
- принтер или копицентр, который печатает PDF в масштабе 100%.

Сначала проверь телефон:

```bash
adb devices -l
```

В списке должно быть `device`, а не `unauthorized`.

## 1. Напечатать AprilTag

```bash
python3 tools/calib/make_marker.py
```

Появятся файлы:

```
tools/calib/marker_a4_150mm.pdf
tools/calib/marker_a4_150mm.png
```

Печатай PDF в масштабе **100% / Actual size**, не «по размеру страницы».
Для CUPS:

```bash
lpstat -p
lp -d PRINTER_NAME -o media=A4 tools/calib/marker_a4_150mm.pdf
```

Наклей лист на жёсткий картон. Измерь напечатанную линейку 100 мм:

- примерно **99 мм** — ничего не меняй, шим по умолчанию ждёт `0.1485 м`;
- ровно **100 мм** — чёрный квадрат 150 мм, скажи шиму:

```bash
adb shell 'mkdir -p /sdcard/Android/data/<package>/files'
adb shell 'echo 150 > /sdcard/Android/data/<package>/files/tag_size'
```

Маркер должен быть матовым, хорошо освещённым, в фокусе, с видимой белой рамкой.

## 2. Установить ARCore и профиль

Самый простой путь — Google Play:

1. установи **Google Play Services for AR** обычным способом;
2. установи профиль через тестовое приложение (шаг 3) или через activity
   загрузки профиля из [02-profiles.md](02-profiles.md).

Профиль удобнее выбирать в тестовом приложении. Начни с `chopin_patched` или
кнопки **Auto: match this camera**.

## 3. Собрать и установить тестовое приложение

```bash
cd android
./build.sh
adb install -r arcore-toolkit.apk
```

Если экран спит:

```bash
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
```

Запуск:

```bash
adb shell monkey -p io.github.nikonektik.arcoretoolkit \
  -c android.intent.category.LAUNCHER 1
```

На телефоне разреши камеру, открой **Profiles...**, выбери профиль и наведи
камеру на напечатанный маркер. В HUD должно смениться
`Tracking: PAUSED` → `Tracking: TRACKING`.

Проверка логов:

```bash
adb logcat -s ARCOREGAME
adb logcat -s ARCORE_SHIM
```

## 4. Собрать маркерный шим

```bash
cd tools/shim
./build.sh
```

Результат — `tools/shim/libarcore_sdk_c.so`. Чистый pass-through собирается так:

```bash
SHIM_INC=passthrough.inc SHIM_NO_OVERRIDES=1 \
  OUT=libarcore_sdk_c_stock.so ./build.sh
```

## 5. Пропатчить APK шимом

Нужен **оригинальный APK** приложения. APK, вытащенный с телефона, подходит,
только если приложение ставилось одним APK, а не split-набором:

```bash
APP_PACKAGE=com.example.app
adb shell pm path "$APP_PACKAGE"
# скопируй путь к base.apk из вывода, затем:
adb pull /data/app/.../base.apk app.apk
```

Если приложение распространяется как `.apk`/`.xapk`, бери исходный `.apk`.

Патч и установка:

```bash
cd /path/to/arcore-toolkit
python3 tools/shim/patch_apk.py app.apk --build-shim --install
```

Команда собирает шим, заменяет `lib/*/libarcore_sdk_c.so`, оставляет настоящую
библиотеку как `libarcore_sdk_c_real.so`, подписывает APK и делает
`adb install -r`.

Если установка падает с ошибкой подписи, патченный APK подписан не тем ключом.
Либо укажи оригинальный ключ:

```bash
python3 tools/shim/patch_apk.py app.apk --build-shim \
  --ks /path/to/original.keystore --key-alias ALIAS --install
```

Либо сначала удали приложение (**данные приложения будут потеряны**):

```bash
adb uninstall "$APP_PACKAGE"
adb install app-shim.apk
```

## 6. Запустить пропатченное приложение

```bash
adb shell monkey -p "$APP_PACKAGE" -c android.intent.category.LAUNCHER 1
```

Наведи камеру на маркер. Если чёрный экран или нет трекинга:

```bash
adb logcat -s ARCORE_SHIM
adb shell 'tail -30 /sdcard/Android/data/<package>/files/shim.log'
```

Ищи строки:

```
tracking on
metrics ... det=...
```

## 7. Быстрая диагностика

| Симптом | Что проверить |
|---|---|
| `ARCore is not available` | установить Google Play Services for AR и профиль |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | другой ключ подписи; `--ks` или удалить приложение |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | неверный ABI; собрать `arm64-v8a` |
| Маркер не детектится | масштаб печати, `tag_size`, свет, фокус, белая рамка, дистанция |
| Контент дёргается | держать телефон ровнее, больше света; настроить `euro`/`lead` |
| Контент едет за камерой | проверить `latency_ms` и `lead` в папке данных |
| Приложение закрылось | посмотреть экран краша или `adb logcat` |
| Отчёт о краше | сохраняется в `files/crash/last_crash.txt` |

Подробнее:

- профили и сервер: [02-profiles.md](02-profiles.md)
- экраны приложения: [03-test-app.md](03-test-app.md)
- размеры маркеров: [05-marker-ar.md](05-marker-ar.md)
- устройство шима: [06-shim.md](06-shim.md)
