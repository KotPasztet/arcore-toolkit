[English](../02-profiles.md) | **Русский**

# Профили ARCore

Всё здесь получено реверс-инжинирингом ARCore 1.5x. Названия полей выведены из
поведения; важны **номера** полей.

## 1. Ключ устройства

ARCore идентифицирует устройство по значению `ro.product.device` (`Build.DEVICE`):

```
key = base64url_nopad( md5( SALT || device ) )[:6]
SALT = 0c e8 8d 52 6f b7 40 d7
```

```bash
arcore-profile key duchamp HWMAR flame
# duchamp  pADuJF
# HWMAR    nJH8dB
# flame    Ub5OOR
```

На некоторых устройствах ARCore 1.57 пробует не только «голое» имя, но и варианты с
именами сенсоров, например для Huawei P30 Lite:

```
HWMAR                                             -> nJH8dB
HWMAR_SFP_accelerometer-bmi160_SUNNY_SFP          -> HX2bDI
MAR-LX1MRU/HWMAR                                  -> Q75qu9
MAR-LX1MRU/HWMAR_SFP_accelerometer-bmi160_SUNNY_SFP -> yFGZd1
```

Заранее неизвестно, какой сработает, поэтому кладите в ZIP **все** варианты
(`--device` принимает несколько значений, см. §5).

## 2. Формат базы

`assets/packed_profiles/profiles.toc` — protobuf:

```
repeated Entry {
  string key = 1;              // "<obf>:<suffix>"
  Value  value = 2;            // { 1: dat_index, 2: offset, 3: length }
}
```

`profiles_NNNNN.dat` — склеенные бинарные `DeviceProfile` (protobuf), не шифруются.
Суффикс после `:` — селектор варианта (уровень API, комбинация сенсоров, …); ARCore
перебирает несколько.

## 3. Поля DeviceProfile

| Поле | Тип | Смысл |
|---|---|---|
| **1** | message, repeated | камеры (`camera[0]` — основная) |
| **2** | message | карта параметров; ключ **100** = калибровка IMU |
| **3** | message, repeated | экстринсики камера↔IMU `{p, q}` |
| **4** | message, repeated | прочие блоки |

Камера (поле 1):

| Поле | Тип | Смысл |
|---|---|---|
| 1 | string | id камеры (`"0"`) |
| 3 | varint | rolling shutter skew, нс |
| 5 | int64 | сдвиг метки времени камеры, нс |
| 6 | varint | ширина (640) |
| 7 | varint | высота (480) |
| 8 | message `{1: fx, 2: fy}` | фокусное в пикселях |
| 9 | message `{1: cx, 2: cy}` | главная точка |
| 11 | message `{1: k0, 2: k1, 3: k2}` | радиальная дисторсия |
| 17 | varint | уровень доверия к таймбазе |
| 18 | varint | источник данных rolling shutter |
| 27 | varint | режим поддержки Depth API |

IMU (поле 2, ключ 100):

| Поле | Тип | Смысл |
|---|---|---|
| 1 | 3×double | масштаб/рассинхрон гироскопа |
| 2 | 3×double | misalignment гироскопа |
| 3 | 3×double | масштаб акселерометра |
| 4 | 3×double | misalignment акселерометра |
| 5 | 4×double | кватернион ориентации |
| 6 | double | шум гироскопа (noise density) |
| 7 | double | random walk гироскопа |
| 8 | double | шум акселерометра |
| 9 | double | random walk акселерометра |
| 10 | 3×double | bias гироскопа |
| 11 | 3×double | bias акселерометра |

## 4. CLI

```bash
arcore-profile inspect profile.pb            # человекочитаемо
arcore-profile inspect profile.pb --json     # машинночитаемо
arcore-profile inspect out.zip               # первый .dat из ZIP
```

Правка камеры:

```bash
arcore-profile patch base.pb -o out.pb \
    --fx 410.8 --fy 410.8 --cx 320 --cy 240 \
    --k0 0.001 --k1 0 --k2 0 \
    --rs 26763333 --tsa -20000000 --tb 2 --rss 1 --depth 2
```

Скопировать блок IMU из другого профиля (например, с проверенной калибровкой):

```bash
arcore-profile patch base.pb -o out.pb --fx 410.8 --imu-from donor.pb
```

Только IMU:

```bash
arcore-profile imu base.pb -o out.pb \
    --gyro-scale 1.0286,1.0151,1.002 --accel-scale 1.0,1.0,0.995 \
    --gyro-bias 0.0046,-0.0022,0.0015 \
    --gyro-noise 0.0005309 --accel-noise 0.0048836
```

Извлечь профиль из своего ARCore APK (сначала распакуйте `assets/packed_profiles`):

```bash
unzip -q com.google.ar.core.apk 'assets/packed_profiles/*' -d arcore
arcore-profile extract --assets arcore/assets/packed_profiles \
    --device flame -o flame.pb
```

## 5. Сборка ZIP для загрузки ARCore

```bash
arcore-profile zip out.pb -o out.zip --device HWMAR \
    --device HWMAR_SFP_accelerometer-bmi160_SUNNY_SFP \
    --device MAR-LX1MRU/HWMAR
# или готовыми ключами:
arcore-profile zip out.pb -o out.zip --key pADuJF
```

Внутри ZIP:

```
profiles.toc        записи "<key>:<suffix>" для 0..35, Q, 10..15, 34
profiles_00000.dat  сам профиль
```

## 6. Установка без root

У ARCore есть экспортированная activity, которая скачивает и распаковывает базу
профилей в своё приватное хранилище:

```bash
adb shell am start \
  -n com.google.ar.core/com.google.ar.core.services.profiles.ProfileDownloadActivity \
  --es explicit-download-url "https://HOST/arcore/switch/<name>/1.57.262640283.zip"
```

Требования:

* последний сегмент URL должен парситься как `major.minor.patch` (major.minor
  ARCore должны совпадать);
* `Content-Type: application/zip`;
* HTTPS (targetSdk ≥ 28 блокирует cleartext).

Проверка:

```bash
adb logcat | grep -aE "Device profile for|Using local profile database version"
# Device profile for HUAWEI/...: yFGZd1:10
# Using local profile database version: 1.57.262640900
```

Тестовое приложение показывает интринсики, которые реально использует ARCore
(`focal=...`), — это самый быстрый способ убедиться, что профиль активен.

## 7. Каталог профилей

Браузер профилей в приложении строится по каталогу с сервера:

```
GET /arcore/switch/profiles
{
  "profiles": [
    {"name": "chopin_patched", "fx": 464.56, "fy": 464.56, "cx": 320, "cy": 240},
    ...
  ],
  "default_key": "pADuJF"
}
```

Интринсики берутся из самих `.pb`, поэтому приложение может выбрать ближайшего
донора для текущей камеры и пометить его звёздочкой.

## 8. Сервер

`tools/server/switch_server.py` собирает ZIP по запросу:

```
GET /arcore/switch/<name>/<version>.zip[?dev=..|?key=..]
GET /arcore/switch/custom/<version>.zip?base=<name>&fx=..&fy=..&cx=..&cy=..
```

`profiles.json` сопоставляет имена и glob-шаблоны и может закреплять ключи устройств:

```json
{
  "profiles": {
    "chopin_patched": "profiles/chopinpatched_*.pb",
    "hwmar": "profiles/hwmar_*.pb"
  },
  "keys": {
    "hwmar": ["HWMAR", "HWMAR_SFP_accelerometer-bmi160_SUNNY_SFP"]
  },
  "default_key": "pADuJF"
}
```

Параметры `custom`: `fx fy cx cy k0 k1 k2 rs tsa tb rss depth gsx gsy gsz gn an`.
`?base=` выбирает исходный профиль.

За HTTPS, например nginx:

```nginx
location /arcore/switch/ {
    proxy_pass http://127.0.0.1:8099;
    proxy_read_timeout 30s;
    add_header Cache-Control "no-store";
}
```

## 9. Практика

* ARCore пробует ключ устройства **и** варианты с сенсорами — кладите все.
* «Случайный» профиль с похожего телефона часто работает (профиль — подсказка по
  калибровке, а не whitelist). Важнее всего интринсики: ошибка фокуса в 10 % — это
  ошибка масштаба в 10 % для всего, что строит ARCore.
* Версия в URL должна совпадать только по `major.minor`; больший patch допустим и
  заставляет ARCore предпочесть новую базу.
* Не выкладывайте проприетарные профили в публичный репозиторий — извлекайте их из
  своей установки.
