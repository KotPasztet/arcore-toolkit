[English](../01-getting-started.md) | **Русский**

# Начало работы

## 1. Требования

| Компонент | Версия |
|---|---|
| Python | 3.9+ |
| JDK | 11+ (проверено на 21) |
| Android SDK | `platforms/android-35`, `build-tools/35.0.0` |
| Android NDK | r25+ (проверено на r27c) |
| Устройство | Android 10+, ADB, **без root** |

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_NDK_ROOT=$ANDROID_SDK_ROOT/android-ndk-r27c
```

## 2. Установка Python-инструментов

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -e .
pytest -q
```

## 3. ADB

USB:

```bash
adb devices -l
```

Wi-Fi (Android 11+): включите *Отладку по Wi-Fi*, затем

```bash
adb pair <ip>:<pair-port>     # один раз, с кодом из диалога
adb connect <ip>:<port>
```

Поиск по mDNS без знания порта:

```bash
avahi-browse -rt _adb-tls-connect._tcp
```

В Android 10 отдельного UI для Wi-Fi-отладки нет: подключите по USB и выполните
`adb tcpip 5555`, затем `adb connect <ip>:5555`.

## 4. Сборка и установка приложения

```bash
cd android
./build.sh          # зависимости скачиваются в android/deps/
adb install -r arcore-toolkit.apk
```

Запускайте с рабочего стола (или через `monkey`): на некоторых прошивках спящий
экран приводит к падению при старте — см. диагностику.

## 5. Установка ARCore и профиля

1. Установите официальный ARCore APK (подпись Google). Манифест приложения требует
   `min_apk_version=262640283` (ARCore 1.57).
2. Запустите сервер профилей (см. [02-profiles.md](02-profiles.md)) и отправьте
   профиль на устройство через `ProfileDownloadActivity`.
3. Откройте тестовое приложение: должно быть
   `availability=SUPPORTED_INSTALLED`, затем `SESSION_CREATED_OK`, а HUD покажет
   `Tracking: TRACKING`, когда камера увидит текстуры.

## 6. Типичный сценарий

```
характеристики камеры ──► интринсики ──► профиль ──► сессия ARCore
   (Device info)           (fx, fy)      (сервер)        (приложение)
                                                      └─► калибровка (Kalibr)
                                                      └─► шим (маркерный AR)
```

* Начните с профиля телефона с похожей камерой (главное — фокусное расстояние).
* Экран **Device info / intrinsics** посчитает точные `fx/fy` для вашей камеры.
* Если ARCore всё равно трекает плохо — калибруйте камеру и IMU по печатной мишени
  ([04-calibration.md](04-calibration.md)).
* Если устройство вообще не трекает — маркерный AR через шим
  ([05-marker-ar.md](05-marker-ar.md), [06-shim.md](06-shim.md)).

## 7. Правовая часть

Это исследовательский инструментарий для собственных устройств. ARCore, ARCore SDK
и данные профилей — проприетарное ПО Google; здесь они не распространяются. Для
сборки шима нужна SDK-библиотека ARCore, извлечённая из APK, который у вас уже есть
(или из публичного Maven-артефакта) — она остаётся на вашей машине.
