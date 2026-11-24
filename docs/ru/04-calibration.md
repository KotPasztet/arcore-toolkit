[English](../04-calibration.md) | **Русский**

# Калибровка

Интринсики камеры и калибровка IMU в профиле определяют, насколько хорошо ARCore
трекает. Фабричные данные есть редко, поэтому их измеряют.

## 1. Печать мишени

```bash
python3 tools/calib/make_aprilgrid.py
# aprilgrid_a4_6x6_25mm.pdf / .png / .jpg
```

AprilGrid 6×6, тег 25 мм, зазор 0.3, A4 при 300 DPI. На листе есть линейка 100 мм:
измерьте её и масштабируйте числа мишени (печать обычно ~99 %, поэтому
`tagSize = 0.02475` в конфиге Kalibr).

`assets/` также содержит генератор одиночного маркера (`make_marker.py`, AprilTag
36h11 ID 0, 150 мм) для маркерного AR.

## 2. Запись сессии

В приложении: **Calibration** → пишите 60–90 с, медленно двигая телефон перед
мишенью во всех ориентациях, дистанции 0.3–0.8 м, без смаза.

Записывается:

```
files/calib/<session>/
├── frames/*.jpg
├── frames.csv      timestamp_ns,filename
├── imu.csv         timestamp_ns,type,gx,gy,gz,ax,ay,az
└── meta.txt        характеристики камеры
```

Вытащить:

```bash
adb exec-out run-as io.github.nikonektik.arcoretoolkit tar -c files/calib > calib.tar
tar -xf calib.tar
```

## 3. Конвертация в ROS bag

```bash
pip install -r tools/calib/requirements.txt   # numpy, pillow, rosbags
python3 tools/calib/calib_to_bag.py files/calib/<session> -o session.bag
```

Топики: `/cam0/image_raw` (`bgr8`) и `/imu0` (гироскоп с ближайшим отсчётом
акселерометра, СИ). Таймстемпы — часы телефона `REALTIME`, общие для обоих потоков:
именно это делает оценку сдвига времени в Kalibr осмысленной.

## 4. Kalibr

```bash
kalibr_calibrate_imu_camera \
  --bag session.bag \
  --cam camchain.yaml \
  --imu imu.yaml \
  --target tools/calib/kalibr_target_aprilgrid.yaml
```

Минимальный `camchain.yaml`:

```yaml
cam0:
  camera_model: pinhole
  intrinsics: [470, 470, 320, 240]
  distortion_model: radtan
  distortion_coeffs: [0.0, 0.0, 0.0, 0.0]
  resolution: [1280, 960]
  rostopic: /cam0/image_raw
```

`imu.yaml`:

```yaml
imu0:
  model: calibrated
  topic: /imu0
  update_rate: 400.0
  accelerometer_noise_density: 0.005
  accelerometer_random_walk: 0.01
  gyroscope_noise_density: 0.0005
  gyroscope_random_walk: 0.0001
  rostopic: /imu0
```

Kalibr выдаёт:

* `results-cam-*.txt` — интринсики в разрешении записи, дисторсия, ошибка
  репроекции;
* `results-imucam-*.txt` — трансформация камера↔IMU, сдвиг времени, остатки;
* `imu-*.yaml` — шумы и random walk.

## 5. Превращение результатов в профиль

1. **Интринсики для 640×480**: если писали в 1280×960, разделите `fx, fy, cx, cy`
   на два (профиль описывает поток ARCore 640×480).
2. **Дисторсия**: ARCore использует три радиальных коэффициента `k0, k1, k2`.
   Kalibr даёт `radtan` `k1, k2, p1, p2`; подставьте `k1, k2`, третий — следующим
   порядком (или ноль), и проверьте на практике.
3. **Timeshift** (cam→IMU, секунды) → поле `tsa`, наносекунды; знак проверяйте по
   качеству трекинга.
4. **IMU** → шум и random walk гиро/акселя (`gn`, `an`, ...).
5. **Rolling shutter**: skew из даташита/ HAL, если доступен.

```bash
arcore-profile patch base.pb -o device.pb \
    --fx 237.95 --fy 235.93 --cx 151.14 --cy 122.34 \
    --k0 0.1424 --k1 -0.2053 --k2 0.0043 \
    --tsa -10360000
arcore-profile imu device.pb -o device.pb \
    --gyro-noise 0.0005 --accel-noise 0.005
arcore-profile zip device.pb -o out.zip --device <ro.product.device>
```

## 6. Калибровка только камеры (OpenCV)

Если нужна только камера (без IMU), `tools/calib/calib_opencv.py` обрабатывает
папку кадров AprilGrid:

```bash
python3 tools/calib/calib_opencv.py files/calib/<session>/frames
```

Печатает `K` и коэффициенты дисторсии в разрешении записи; масштабируйте под 640×480.

## 7. Как читать числа

* Ошибка репроекции < 1 px — хорошо. Больше — мишень двигалась или кадры смазаны.
* Остаток гироскопа < 0.01 рад/с, акселерометра < 0.15 м/с² — типично для телефонов.
* Сдвиг времени 5–15 мс нормален. Ошибка >10 мс заметно дестабилизирует VIO при
  вращении.
