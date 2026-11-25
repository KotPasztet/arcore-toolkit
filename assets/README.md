# Generated assets

Printable targets are generated, not committed:

```bash
python3 tools/calib/make_marker.py      # marker_a4_150mm.pdf (AprilTag 36h11 ID 0)
python3 tools/calib/make_aprilgrid.py   # aprilgrid_a4_6x6_25mm.pdf (Kalibr target)
```

Both scripts download the tag images from the AprilRobotics/apriltag-imgs
repository and lay them out for A4 at 300 DPI with a 100 mm scale bar.
