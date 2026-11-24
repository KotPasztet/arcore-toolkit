[English](../07-troubleshooting.md) | **Русский**

# Диагностика

## ADB

| Симптом | Решение |
|---|---|
| `device not found` | переподключить, `adb kill-server && adb start-server`, подтвердить «Разрешить отладку» |
| устройство пропадает | питание USB/кабель; включить «Отладку по Wi-Fi» и `adb connect` |
| порт меняется | mDNS: `avahi-browse -rt _adb-tls-connect._tcp` |
| `unauthorized` | принять ключ на устройстве |

## Логи

```bash
adb logcat -c
adb logcat | grep -aE "ARCORE|ARCore|Minecraft|native|FATAL"

# только приложение
adb logcat --pid=$(adb shell pidof <package> | tr -d '\r')

# почему умер процесс
adb shell dumpsys activity exit-info <package>
```

Причины в `exit-info`: `5` — нативный краш (`status=6` = SIGABRT), `6` — ANR,
`16` — переустановка пакета.

Приложение пишет в теги `ARCOREGAME`/`ARCORETEST`, шим — в `ARCORE_SHIM` и в файлы в
папке данных приложения.

## Приложение падает сразу после запуска (`No pending exception expected`)

```
Abort message: 'No pending exception expected: java.lang.IllegalStateException:
Not allowed to start service Intent { ... Firebase ... RegistrationIntentService }:
app is in background'
```

Экран был выключен (или приложение стартовало в фоне), а игра поднимает
Firebase-сервис; Android 10 кидает исключение, оно остаётся «висящим», и ART убивает
процесс на следующем JNI-вызове. Разбудите устройство и запускайте с рабочего стола:

```bash
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell monkey -p <package> -c android.intent.category.LAUNCHER 1
```

## Профиль отвергнут ARCore (`FatalException` в `resume()`)

```
SESSION_CREATED_OK
onResume FAILED: com.google.ar.core.exceptions.FatalException
```

Профиль несёт не только калибровку, но и конфигурацию камеры. Профиль телефона с
другим camera pipeline может создать сессию, но не запустить камеру. Приложение это
ловит и предлагает повторно применить последний рабочий профиль.

Вручную вернуть рабочий профиль:

```bash
adb shell am force-stop com.google.ar.core
adb shell am start --activity-clear-task -n com.google.ar.core/com.google.ar.core.services.profiles.ProfileDownloadActivity \
  --es explicit-download-url "https://HOST/arcore/switch/<known-good>/<version>.zip"
```

> `ProfileDownloadActivity` переиспользует свою задачу: обычный `am start`, если она
> уже открыта, лишь поднимает её на передний план и **не передаёт новый URL**.
> Помогает `am force-stop com.google.ar.core` и/или `--activity-clear-task`.

## ANR (зависание на 10–30 с, «приложение не отвечает»)

Ищите в логе загрузку текстур или мира:

```
[ERROR PERF] Stalled the main thread by loading texture: ...
```

На телефоне с нехваткой памяти это превращается в ANR. Проверьте:

```bash
adb shell free -m
```

Закройте фоновые приложения / перезагрузитесь и повторите. `adb bugreport` на
загруженном устройстве часто падает («Failed to connect to dumpstatez service»).

## ARCore говорит, что устройство не поддерживается

* `checkAvailability` → `UNSUPPORTED_DEVICE_NOT_CAPABLE`: нет подходящего профиля.
  Установите его (см. [02-profiles.md](02-profiles.md)).
* Создание `Session` падает с `UnavailableDeviceNotCompatibleException`: профиль
  найден, но конфигурация камеры не совпадает (id камеры, размеры потоков) или
  сессия не смогла открыть камеру.
* Проверьте поиск:

```bash
adb logcat | grep -aE "Device profile for|Found nothing for|Using local profile"
```

Лог перечисляет все ключи, которые пробовал ARCore, — так и находятся
квалифицированные варианты с сенсорами.

## Плохой трекинг даже с профилем

1. Проверьте интринсики, которые реально использует ARCore (приложение печатает
   `focal=...`). Если они не совпадают с профилем — профиль не применился.
2. Сверьте фокус с характеристиками камеры (**Device info / intrinsics**).
3. Посмотрите сообщения VIO:

```bash
adb logcat | grep -aE "VIO|vio|RESOURCE_EXHAUSTED|desync|pose_manager"
```

`RESOURCE_EXHAUSTED: Behind by ...`, `VIO frequency low`, `Camera to IMU clock
offset` означают неверные таймстемпы/калибровку IMU — калибруйте
([04-calibration.md](04-calibration.md)).

4. Тепловой троттлинг: при 90 °C+ или <10 % заряда камера и VIO деградируют (частота
   падает с 30 до 7 fps). Зарядите и дайте остыть.

## Маркер не детектится

* Весь маркер в кадре, вместе с белой рамкой?
* Блики/глянец? Матовая бумага и рассеянный свет.
* Слишком далеко? Маркер 150 мм на 1.5 м — это ~45 px, на грани.
* Смотрите `dets=` в `shim.log` или строку с `err`/дистанцией в `MarkerActivity`.

## Особенности устройств

См. [devices.md](devices.md).
