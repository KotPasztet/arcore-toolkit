# Marker tracking and printing the tag

When a device cannot track at all — no usable VIO, or ARCore refuses to run —
tracking can be replaced by a printed AprilTag marker. Two pieces use it:

* the **Marker AR** screen in the app (plain camera + OpenCV, no ARCore) —
  the quick way to check the marker is readable;
* the **shim** — injects AprilTag poses into any ARCore app
  ([docs/06-shim.md](06-shim.md), advanced).

## Step 1: Print the tag

No tools needed — the app prints it for you.

1. Tap **Print the tag (PDF)** on the main screen, or **Print tag (A4 PDF)**
   on the Marker AR screen.
2. Pick the tag size in millimetres (40–300, default 150) and tap
   **Create PDF**. The app generates an A4 PDF — AprilTag 36h11, ID 0, the
   outer black square exactly at the size you entered, a 100 mm scale bar and
   print instructions. A size up to 160 mm fits on A4 with its white border.
3. The PDF is saved to **Downloads**. You can share it straight from the
   dialog to a print shop or another device.
4. Print at **100% / Actual size** (not "fit to page"), on matte paper.
5. Glue the sheet to something rigid — cardboard or a clipboard. A bent sheet
   bends the pose too.

For other paper sizes, other tag sizes or several tags on one sheet, use the
Python generator instead. Install the dependencies (numpy, pillow) and run
the script — it writes `marker_a4_150mm.pdf` and `.png`:

```bash
pip install -r tools/calib/requirements.txt
python3 tools/calib/make_marker.py
```

## Step 2: Measure the 100 mm scale bar

Home printers rarely print at exactly 100%. The PDF has a 100 mm bar for
exactly this reason: measure the printed bar with a ruler, and scale the tag
size by the same ratio. If the bar measures 99 mm, the nominal 150 mm square
is actually 148.5 mm.

Tell the toolkit the real size:

* **In the app**: enter the measured size in the **Tag size (mm)** field on
  the Marker AR screen (default 150).
* **For the shim**: write the size in millimetres to the `tag_size` file in
  the target app's data directory ([docs/06-shim.md](06-shim.md)).

Wrong size = wrong distance scale, so measure once and be done.

## Step 3: Track it

Open **Marker AR** from the main screen and point the camera at the tag.
The overlay shows the tag ID, distance and reprojection error, for example
`ID 0  dist 0.52 m  err 0.41 px`, plus a running `frames / detected` counter
and a cube drawn on the tag. Tracking here does not involve ARCore, so it
works even on phones where ARCore refuses to start.

## How accurate is it?

The camera image is scanned for AprilTag 36h11 markers; the four corners feed
`solvePnP`, which yields position and orientation. Expected accuracy:
reprojection error 0.3–1 px at 0.3–0.7 m, position error a few millimetres at
0.5 m with a 150 mm tag.

For a tag lying face-up on a table, "up from the table" is −Z in the AprilTag
frame. The toolkit re-bases the world so that +Y is up, as ARCore and games
expect.

Tag size vs comfortable working distance:

| Distance | Tag 150 mm | Tag 300 mm |
|---|---|---|
| 0.3 m | excellent | overkill |
| 0.5 m | good | excellent |
| 1.0 m | usable (~50 px) | good |
| 2.0 m | marginal | usable |

Bigger is better: 150–300 mm covers 0.3–2 m comfortably.

## Tips

* Matte paper, diffuse light, no gloss — reflections kill detection.
* Keep the whole tag in frame, white border included.
* Use a size your camera resolves: a 150 mm tag at 1.5 m is only ~45 px.
* A blurry tag can still "detect" with a wrong pose — check the reprojection
  error on screen.

Tag not detected? See [docs/03-troubleshooting.md](03-troubleshooting.md).
Ready to use the tag inside other AR apps? That is the shim:
[docs/06-shim.md](06-shim.md) (advanced).
