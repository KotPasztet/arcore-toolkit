# Generated assets

Printable targets are generated, not committed:

```bash
python3 tools/calib/make_marker.py      # marker_a4_150mm.pdf (single tag, ID 0)
python3 tools/calib/make_bundle.py      # bundle_a3_hybrid.pdf (5-tag hybrid sheet)
python3 tools/calib/make_aprilgrid.py   # aprilgrid_a4_6x6_25mm.pdf (Kalibr target)
```

The scripts download tag images from the AprilRobotics/apriltag-imgs repository
and lay them out at 300 DPI with a 100 mm scale bar.  `make_bundle.py` prints on
A3 landscape and also writes `tools/calib/bundle_a3_hybrid_tags.txt` for the
shim's `tags` override.
