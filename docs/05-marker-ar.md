# Marker AR

When a device cannot track at all (no usable VIO, or ARCore refuses to run),
tracking can be replaced by a printed fiducial.  Two pieces:

* **MarkerActivity** in the test app — a standalone Camera2 + OpenCV prototype;
* **the shim** — replaces ARCore's C library inside an existing app and feeds it
  AprilTag poses (see [06-shim.md](06-shim.md)).

## 1. Marker

```bash
python3 tools/calib/make_marker.py
# marker_a4_150mm.pdf — AprilTag 36h11, ID 0, outer black square 150 mm
```

Rules of thumb:

* print on matte paper, laser or inkjet, no gloss (reflections kill detection);
* glue it to something rigid — a bent sheet changes the pose;
* measure the printed black square and use the real size: the generator prints a
  100 mm scale bar; if it measures 99 mm, set the tag size to `0.150 × 0.99 =
  0.1485 m`;
* bigger is better: 150–300 mm covers 0.3–2 m distances with good accuracy;
* keep the whole tag in frame (the white border matters).

## 2. Prototype (no ARCore)

`MarkerActivity`:

* Camera2 `YUV_420_888`, 640×480, `ImageReader` with 4 buffers;
* YUV → ARGB → `Bitmap` preview (preview, detection and overlay share one
  coordinate system);
* OpenCV `ArucoDetector` with `DICT_APRILTAG_36h11`, `solvePnP` on the 4 corners;
* a cube drawn on the tag, plus reprojection error and distance.

The camera image is rotated **90° clockwise** for portrait (the sensor is
landscape).  This is the reference for the shim's display-rotation handling.

Expected numbers: reprojection error 0.3–1 px at 0.3–0.7 m.

## 3. Coordinates

AprilTag pose (`estimate_tag_pose`) uses the OpenCV convention:

* X right, Y down in the tag image;
* **Z away from the camera**, i.e. into the surface the tag is printed on.

For a tag lying face-up on a table, "up from the table" is **−Z**.  ARCore and
games assume **+Y is up**, so the shim re-bases the world:

```
x' = x,   y' = -z,   z' = y
```

## 4. Tag size and accuracy

| Distance | Tag 150 mm | Tag 300 mm |
|---|---|---|
| 0.3 m | excellent | overkill |
| 0.5 m | good | excellent |
| 1.0 m | usable (~50 px) | good |
| 2.0 m | marginal | usable |

Accuracy is roughly `0.3 × distance² / (f × tag_size)` for position; at 0.5 m
with a 150 mm tag it is a few millimetres.

## 5. Tips

* Use `decision_margin` and `hamming == 0` to reject detections; a blurry tag
  can still be "detected" with a wrong pose.
* Low-pass the pose (One Euro) — single-frame pose noise is visible as content
  jitter.
* If you need smooth motion, track the tag corners with optical flow between
  detections; detection at 10–20 Hz is not enough for 60 fps rendering.
* Keep exposure low: motion blur costs more accuracy than noise.
