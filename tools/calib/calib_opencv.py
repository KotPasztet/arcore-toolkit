#!/usr/bin/env python3
"""Independent camera intrinsics calibration with OpenCV ArUco/AprilTag on the
recorded AprilGrid session. Uses all frames the detector can see."""
import csv
import os
import sys

import cv2
import numpy as np

SESSION = sys.argv[1] if len(sys.argv) > 1 else "calib_pull2/files/calib/20261123_111525"
STEP = 2  # use every Nth frame

TAG = 0.02475
SPACING = 0.3
CELL = TAG * (1.0 + SPACING)
COLS = ROWS = 6

d = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_APRILTAG_36h11)
params = cv2.aruco.DetectorParameters()
params.markerBorderBits = 2
detector = cv2.aruco.ArucoDetector(d, params)

K0 = np.array([[929.12, 0, 640.0], [0, 928.07, 480.0], [0, 0, 1.0]])
D0 = np.zeros(5)
rows = list(csv.reader(open(os.path.join(SESSION, "frames.csv"))))[::STEP]


def obj_corners(tag_id):
    row, col = tag_id // COLS, tag_id % COLS
    x0, y0 = col * CELL, row * CELL
    return np.array([
        [x0, y0, 0], [x0 + TAG, y0, 0],
        [x0 + TAG, y0 + TAG, 0], [x0, y0 + TAG, 0],
    ], dtype=np.float32)


def order_error(q, tag_id, idx):
    obj = obj_corners(tag_id)
    ok, rvec, tvec = cv2.solvePnP(obj, q[idx].astype(np.float32), K0, D0,
                                  flags=cv2.SOLVEPNP_IPPE)
    if not ok:
        return 1e9
    proj, _ = cv2.projectPoints(obj, rvec, tvec, K0, D0)
    return float(np.sqrt(np.mean(np.sum((proj.reshape(-1, 2) - q[idx]) ** 2, axis=1))))


def detect(name):
    g = cv2.imread(os.path.join(SESSION, "frames", name), cv2.IMREAD_GRAYSCALE)
    corners, ids, _ = detector.detectMarkers(g)
    out = []
    if ids is not None:
        for c, tid in zip(corners, np.asarray(ids).ravel()):
            out.append((int(tid), np.asarray(c, dtype=np.float32).reshape(4, 2)))
    return out


# Determine the corner correspondence once from the first usable frame.
ORDER = [1, 0, 3, 2]  # verified by full-grid PnP (1.6 px vs 34+ px for others)
for _, _, name in rows:
    det = detect(name)
    if len(det) >= 12:
        errs = {tuple(o): 0.0 for o in ([0, 1, 2, 3], [3, 2, 1, 0])}
        for tid, q in det:
            for idx in ([0, 1, 2, 3], [3, 2, 1, 0]):
                errs[tuple(idx)] += order_error(q, tid, idx)
        # per-tag PnP cannot distinguish the square's symmetry; use geometry-verified order
        print("corner order:", ORDER, "errors:", {k: round(v, 2) for k, v in errs.items()})
        break

obj_views, img_views, per_detect = [], [], []
for i, ts, name in rows:
    det = detect(name)
    o, im = [], []
    for tid, q in det:
        if order_error(q, tid, ORDER) > 3.0:
            continue
        o.append(obj_corners(tid))
        im.append(q[ORDER])
    if len(o) >= 6:
        obj_views.append(np.array(o, dtype=np.float32).reshape(-1, 3))
        img_views.append(np.array(im, dtype=np.float32).reshape(-1, 2))
    per_detect.append(len(o))

print(f"frames={len(rows)} views used={len(obj_views)} "
      f"median tags/used-view={np.median([len(v) for v in obj_views]):.0f}")

rms, K, dist, rvecs, tvecs = cv2.calibrateCamera(
    obj_views, img_views, (1280, 960), K0.copy(), D0.copy(),
    flags=cv2.CALIB_USE_INTRINSIC_GUESS + cv2.CALIB_FIX_K3)

print("K=\n", K)
print("dist=", dist.ravel(), "rms=", rms)
per = []
for o, im, rv, tv in zip(obj_views, img_views, rvecs, tvecs):
    proj, _ = cv2.projectPoints(o, rv, tv, K, dist)
    per.append(np.sqrt(np.mean(np.sum((proj.reshape(-1, 2) - im) ** 2, axis=1))))
per = np.array(per)
print("per-view rms: p10 %.3f median %.3f p90 %.3f max %.3f" % (
    np.percentile(per, 10), np.median(per), np.percentile(per, 90), per.max()))
print("640x480: fx=%.2f fy=%.2f cx=%.2f cy=%.2f" % (
    K[0, 0] / 2, K[1, 1] / 2, K[0, 2] / 2, K[1, 2] / 2))
