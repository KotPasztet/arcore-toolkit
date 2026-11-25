**English** | [Русский](ru/05-marker-ar.md)

# Marker AR

When a device cannot track at all (no usable VIO, or ARCore refuses to run),
tracking can be replaced by a printed fiducial.  Two pieces:

* **MarkerActivity** in the test app — a standalone Camera2 + OpenCV prototype;
* **the shim** — replaces ARCore's C library inside an existing app and feeds it
  AprilTag poses (see [06-shim.md](06-shim.md)).

## 1. Marker

Recommended: the **hybrid A4 sheet** — one large tag for distance, four smaller
tags for accuracy, PnP disambiguation and partial occlusion.

```bash
python3 tools/calib/make_bundle.py
# bundle_a4_hybrid.pdf — A4 portrait, AprilTag 36h11
#   ID 0: 80 mm at the centre
#   IDs 1..4: 38 mm at (+/-75, +/-100) mm
# bundle_a4_hybrid_tags.txt — matching shim layout
```

The shim has the A4 layout built in.  For a printer/paper size that supports A3:

```bash
python3 tools/calib/make_bundle.py --paper a3
# central ID 0 = 110 mm at (0,0); corners IDs 1..4 = 55 mm at (+/-150, +/-104) mm
# bundle_a3_hybrid_tags.txt matches this sheet
```

To override the built-in layout, copy the matching manifest to the target app
and adjust it if needed:

```bash
adb push tools/calib/bundle_a4_hybrid_tags.txt \
  /sdcard/Android/data/<pkg>/files/tags
```

`tags` format (millimetres, sheet frame: X right, Y down, yaw around Z):

```
# id x_mm y_mm yaw_deg size_mm
0 0 0 0 80
1 -75 -100 0 38
...
```

The shim detects all configured IDs, maps each tag pose into the sheet frame and
fuses the agreeing poses (weighted by area, decision margin and reprojection
error).  A single wrong/ambiguous tag cannot pull the result away.

A single large tag is still supported:

```bash
python3 tools/calib/make_marker.py
# marker_a4_150mm.pdf — ID 0, outer black square 150 mm
# tags file: 0 0 0 0 150
```

Rules of thumb:

* print on matte paper, laser or inkjet, no gloss (reflections kill detection);
* glue it to something rigid — a bent sheet changes the pose;
* print at 100% / actual size; measure the 100 mm scale bar;
* if the print is scaled (e.g. 99%), scale **both** sizes and positions in
  `tags` by `measured/100`;
* keep the white border/quiet zone around every tag free of other tags and
  objects;
* bigger is better: the A4 central 80 mm tag covers roughly 0.3–1.2 m, the 38 mm
  corners are best below ~0.7 m; use `--paper a3` (110 mm / 55 mm) if the
  printer supports A3 and you need more range.

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

For the default A4 hybrid sheet, the central 80 mm tag is the far-range anchor;
the four 38 mm tags dominate the fused result up close and keep tracking alive
when one or two corners are hidden.  The A3 variant (110 mm / 55 mm) has more
range but needs A3 paper.

## 5. Tips

* Use `decision_margin` and `hamming == 0` to reject detections; a blurry tag
  can still be "detected" with a wrong pose.
* Low-pass the pose (One Euro) — single-frame pose noise is visible as content
  jitter.
* A flat tag has **two valid PnP solutions**.  Evaluate both and pick the one
  closest to the previous/predicted pose; otherwise the tag appears to flip
  ±15° when it is nearly edge-on.  A hybrid sheet helps: the other tags reject a
  wrong solution through the sheet-frame fusion.
* Run detection on a worker thread and always use the newest frame.  Detection
  at 10–20 Hz is not enough for 60 fps rendering, but a blocked render thread is
  worse: drop stale frames and predict between the remaining ones.
* Keep exposure low: motion blur costs more accuracy than noise.
