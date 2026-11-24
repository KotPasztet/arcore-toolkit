[English](../06-shim.md) | **Русский**

# Шим ARCore

`tools/shim` собирает замену SDK-библиотеке ARCore C API (`libarcore_sdk_c.so`),
которую загружает любое ARCore-приложение. Два режима:

| Режим | Что делает |
|---|---|
| **passthrough** | форвардит все символы в настоящий ARCore; нужен, чтобы заменить старую/сломанную SDK-библиотеку внутри приложения |
| **маркерный** | форвардит всё, но подменяет позы камеры/плоскости/якорей трекингом AprilTag |

## 1. Как устроено

```
app APK  lib/arm64-v8a/
├── libarcore_sdk_c.so        ← шим (тот же SONAME, те же экспортируемые символы)
└── libarcore_sdk_c_real.so   ← настоящая SDK-библиотека, SONAME изменён
```

* `gen_shim.py` парсит официальный `arcore_c_api.h`, читает список символов, которые
  импортирует целевое приложение (`arcore_imports.txt`), и генерирует `shim.c` —
  по форвардеру на символ плюс указатели `real_*`.
* `postprocess.py` добавляет поиск через `dladdr()`, чтобы шим находил
  `libarcore_sdk_c_real.so` рядом с собой (иначе `dlopen` по имени вернёт сам шим), и
  подключает два внутренних символа, которых нет в публичном заголовке
  (`ArCoreApk_getSymbolTable`, `ArSession_createWithFeaturesAndTrace`).
* `internal.S` — трамплин для второго.
* `vers.map` экспортирует только `Ar*`.
* `overrides.inc` — ручные переопределения; `passthrough.inc` — пустой вариант.

У настоящей библиотеки должен быть `SONAME = libarcore_sdk_c_real.so`, иначе
динамический линкер разрешит имя обратно в шим. `extract-real-lib.sh` извлекает её
(из AAR ARCore SDK или из любого приложения с этой библиотекой) и патчит через
`patchelf`.

## 2. Сборка

```bash
cd tools/shim
./extract-real-lib.sh ../../android/deps/arcore-1.57.0.aar
./build.sh                                   # маркерный шим, нужен AprilTag
SHIM_INC=passthrough.inc SHIM_NO_OVERRIDES=1 OUT=libarcore_sdk_c_stock.so ./build.sh
```

Переменные `build.sh`: `NDK`, `API` (по умолчанию 29), `OUT`, `SHIM_INC`,
`WITH_APRILTAG`, `DEFINES` (например `-DTARGET_PKG=\"com.example.app\"`).

## 3. Упаковка

```bash
SOURCE_APK=/path/to/app.apk \
REAL_LIB=libarcore_sdk_c_real.so \
OUT_APK=app-shim.apk \
./package_apk.sh
```

`package_apk.sh` убирает оригинальный `libarcore_sdk_c.so`, кладёт шим и настоящую
библиотеку, выравнивает и подписывает. Ключ по умолчанию —
`android/debug.keystore`; другой ключ означает сброс данных приложения при установке.

## 4. Что переопределяет маркерный шим

| Функция | Поведение |
|---|---|
| `ArSession_update` | настоящий update + детекция AprilTag каждый второй кадр |
| `ArCamera_getViewMatrix` / `ArCamera_getPose` | поза с маркера, с предсказанием между детекциями |
| `ArCamera_getTrackingState` | `TRACKING`, пока маркер виден; пауза через 1 с без него |
| `ArSession_getAllTrackables`, `ArTrackableList_*` | одна синтетическая плоскость при видимом маркере |
| `ArPlane_getCenterPose/getExtent/getType` | поза плоскости (identity в Y-up мире), размеры 1 м, horizontal-upward |
| `ArSession_acquireNewAnchor`, `ArAnchor_*` | статические якоря |
| `ArPose_create/getMatrix` | **7 чисел** (кватернион + сдвиг), не матрица |
| `ArFrame_getAndroidSensorPose` | та же поза камеры |
| `ArFrame_acquirePointCloud` | пустое облако |
| `ArSession_setDisplayGeometry` | запоминается и используется для поворота сенсор→экран |

Шим также делает мелочи, без которых трекинг непригоден:

* разужает углы маркера с калиброванной дисторсией перед `solvePnP`;
* отбраковывает детекции с `hamming != 0` или низким `decision_margin`;
* ограничивает ошибку репроекции (`rms_px = sqrt(err/4)·fx`, по умолчанию 2 px);
* фильтр One Euro по позиции/повороту + отбраковка выбросов;
* предсказывает позу между детекциями (`lead`), чтобы view-матрица была плавной на
  частоте рендера.

## 5. Системы координат

```
AprilTag (OpenCV)          GL-камера                 Мир (после Y-up)
X вправо                   X вправо                  Y' = -Z_tag   (вверх от стола)
Y вниз                     Y вверх                   X' =  X_tag
Z в поверхность            Z назад (в экран)         Z' =  Y_tag
```

View-матрица строится как `[F·R | F·t]`, `F = diag(1,-1,-1)`, затем поворот вокруг
оптической оси под ориентацию экрана (`-90°` для rotation 0), затем пере-базирование:
`R' = R · Aᵀ`, `A = [[1,0,0],[0,0,-1],[0,1,0]]`.

## 6. Флаги и логи

Шим читает необязательные файлы из папки данных целевого приложения
(`/sdcard/Android/data/<package>/files/`):

| Файл | Смысл |
|---|---|
| `world_mode` | `1` = Y-up мир (по умолчанию), `0` = сырая система маркера |
| `plane_mode` | `0` = identity-плоскость (Y-up), `1..4` = старые повороты |
| `freeze` | перестать забирать кадры камеры (A/B-тест) |
| `dist`, `dist_off` | коэффициенты дисторсии / отключить коррекцию |
| `tag_size` | напечатанный чёрный квадрат, мм |
| `margin`, `max_px`, `max_jump`, `max_ang` | пороги отбраковки |
| `filter_off`, `euro` | отключить / настроить One Euro |
| `predict_off`, `lead` | отключить / настроить предсказание позы |
| `shim.log`, `trace.log` | события и все вызовы ARCore (с id потока) |

`trace.log` — главный инструмент отладки: последняя строка перед зависанием или
падением и есть вызов, который не вернулся.

## 7. Известные грабли

* **Пустые списки.** Если вернуть фейковый трекейбл для индекса вне списка,
  приложение передаст его в настоящую функцию ARCore, которая разыменует указатель.
  Всегда возвращайте `NULL` за границами.
* **`ArPose_create` принимает 7 чисел**, не матрицу 4×4. Чтение 16 даёт координаты
  вида `-1.7e22` и падение `std::length_error` дальше по стеку.
* **Крен камеры.** AR-кадр приходит в ориентации сенсора; без поворота под экран весь
  мир выглядит повёрнутым на 90°.
* **Y-up.** Игры считают верхом `+Y`, у системы маркера верх — `−Z`. Проверяйте
  `camPos.y > 0` в логе, чтобы убедиться в пере-базировании.
* **Настоящий ARCore продолжает работать** (он даёт кадр камеры), поэтому его VIO
  всё равно ест CPU и батарею. Passthrough-сборка — способ использовать настоящий
  трекинг, когда он работает.

## 8. Как отлаживать

```bash
adb install -r app-shim.apk
adb shell 'rm -f /sdcard/Android/data/<pkg>/files/{shim.log,trace.log}'
adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1
# воспроизведите, затем:
adb shell 'tail -40 /sdcard/Android/data/<pkg>/files/shim.log'
adb shell 'tail -20 /sdcard/Android/data/<pkg>/files/trace.log'
adb shell dumpsys activity exit-info <pkg>
```
