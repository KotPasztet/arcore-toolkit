[English](../reverse-engineering.md) | **Русский**

# Реверс-инжиниринг ARCore 1.57

Это исходный журнал исследований, из которого вырос тулкит: как устроена база
профилей устройств, как обфусцирован ключ и как поставить профиль без root. Названия
полей выведены из поведения; инструменты используют номера полей.

## Цель

- Пакет: `com.google.ar.core` («Google Play Services for AR»)
- Версия: **1.57.262640283** (versionCode 262640283)
- minSdk 29, targetSdk 37
- APK SHA-256: `ae3ba8901ced29dee1314da7d9854a87d5dee681f23343c6b18c89fe3d0ceae8`
- Подпись (apksigner): `CN=Android, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US`
  - SHA-256 сертификата: `6e913f65ea53321ead6932ff777e1b7f94bbba7e2b0dafdd8494b100101e3797`
  - => оригинальная подпись Google, APKPure не переподписывал.
- Источник: APKPure через `apkeep -d apk-pure` (xapk). К APKPure в целом стоит
  относиться настороженно; подпись выше — и есть проверка.

## Разбор APK (главное)

- `lib/arm64-v8a/libarcore_c.so` — 36 МБ, рантайм (stripped, обфусцирован)
- `lib/arm64-v8a/libhardware_buffer_stream_jni.so` — 18 КБ
- `assets/packed_profiles/profiles.toc` — 79 КБ, protobuf-индекс
- `assets/packed_profiles/profiles_00000.dat … profiles_00050.dat` — **51 файл**
- `assets/device_profile_max_compatibility.textproto` — универсальный профиль, ссылается
  на `github.com/tomthecarrot/arcore-for-all/issues/139`
- ML-модели: depth, semantics, face, deeplight и т.д.
- 3 dex: 65 КБ + 5.2 МБ + 385 КБ

## Формат базы профилей

`profiles.toc` — protobuf: повторяющиеся записи `field 1`, каждая:

```
1 {
  1: "<obf>:<suffix>"        # obf-ключ (иногда упакован как uint64 в поле 13)
  2 { 1: <dat_index> 2: <offset> 3: <length> }
}
```

- 3383 записи, 3173 уникальных 6-символьных ключа
- `profiles_NNNNN.dat` = склеенные **бинарные DeviceProfile** (не шифруются)
- 51 dat-файл, ~45 КБ каждый

## Обфускация ключа (взломана)

Нативный JNI-экспорт
`Java_com_google_ar_core_services_profiles_ProfileDownloadHelper_obfuscateDevice`
(0x11f3200) делает:

```
obf(device) = base64url_nopad( MD5( SALT || device ) )[:6]
SALT = 0c e8 8d 52 6f b7 40 d7
```

Подтверждено дизассемблером:
- `EVP_DigestInit_ex(&ctx_, EVP_md5(), nullptr) == 1`
- update(salt, 8); update(device_name, len); final (16-байтный дайджест)
- алфавит base64url `A–Za–z0–9-_`, без padding
- берутся первые **6** символов

`device` = `Build.DEVICE` = `ro.product.device`.

## Проверка (какие устройства есть в базе)

| устройство (ro.product.device) | obf | в базе |
|---|---|---|
| flame (Pixel 4) | Ub5OOR | да |
| blueline (Pixel 3) | ox1uFp | да |
| crosshatch (Pixel 3 XL) | 7l8C9M | да |
| coral (Pixel 4 XL) | za-jzj | да |
| marble (POCO F5) | fJKExA | да |
| alioth (POCO F3) | k-Shfr | да |
| vayu (POCO X3 Pro) | pUVpQN | да |
| sweet (Redmi Note 10 Pro) | OCG0Cn | да |
| chopin (POCO X3 GT) | Wg0hne | да |
| toco / cepheus / violet / begonia / karna / redwood / munch | — | да |
| **duchamp (POCO X6 Pro)** | **pADuJF** | **НЕТ** |

`duchamp`, `duchamp_global`, `2311DRK48G` — ни одного (побайтовый поиск, независимо
от парсера). => ARCore 1.57 не содержит профиля для POCO X6 Pro, как и ожидалось.

Суффикс после `:` — метаданные (слабо коррелируют с версией Android, например
`marble:13`, `alioth:11`); поиск идёт по 6-символьному префиксу.

## Извлечённые профили (камера 0, 640×480)

| устройство | фокус (px) | главная точка | дисторсия k0,k1,k2 | RS readout |
|---|---|---|---|---|
| marble (POCO F5) | 463.3 / 464.2 | 321.64 / 239.97 | 0.1548, −0.4610, 0.5188 | 30.9 мс |
| alioth (POCO F3) | 478.2 / 476.7 | 319.76 / 239.41 | 0.1528, −0.4842, 0.4745 | 32.7 мс |
| vayu (POCO X3 Pro) | 468.5 / 470.0 | 318.52 / 239.67 | 0.1639, −0.4177, 0.3474 | 32.7 мс |
| flame (Pixel 4) | 498.7 / 498.7 | 322.21 / 237.08 | 0.0855, −0.1757, 0.1031 | 32.2 мс |

POCO X6 Pro и POCO F5 используют один сенсор — 64 МП **OV64B** (1/2", 0.7 µm) +
8 МП + 2 МП. Поэтому `marble` — ближайшая сертифицированная калибровка для старта.

## Механизм кастомного профиля (в 1.57 ещё есть)

Нативный `hasCustomDeviceProfile()` =
`access("/data/data/com.google.ar.core/files/custom_device_profile.textproto", F_OK)`.
Если файл есть, ARCore загружает его раньше встроенной базы (описано командой ARCore
в `tomthecarrot/arcore-for-all#139`, декабрь 2018; ассет до сих пор ссылается на
этот механизм).

Требует записи в приватную папку приложения => **root** на современном Android
(старый трюк с `adb backup`/restore фактически мёртв на Android 12+). Может
понадобиться и правка SELinux-контекста.

## URL OTA-профиля

```java
"https://www.gstatic.com/arcore/profiles/latest.zip?v=" +
    new ctt(context).b() + "&d=" + obfuscateDevice(Build.DEVICE)
```

`ctt.b()` = «major.minor» версии ARCore (например, `1.57`).
Чистый `latest.zip` сейчас отдаёт 404; сервер, вероятно, проверяет `v`/`d`.
`ProfileDownloadActivity` / `ProfileDownloadJobService` / `ArCoreContentProvider`
реализуют загрузку и content provider `device_profile`.

## Что из этого выросло

Путь без root — через `ProfileDownloadActivity` (см.
[02-profiles.md](02-profiles.md)): ARCore сам скачивает ZIP с `.toc`/`.dat` в свою
приватную папку. Именно так профиль ставится на заблокированное устройство.
