# Generated assets

Printable targets are generated, not committed: run
`python3 tools/calib/make_marker.py` for the single AprilTag A4 PDF (36h11,
ID 0, 150 mm) or `python3 tools/calib/make_aprilgrid.py` for the Kalibr
AprilGrid target. Inside the app you do not even need Python — the
**Print the tag (PDF)** button generates the same tag and saves it to
Downloads. Both scripts download tag images from the AprilRobotics/apriltag-imgs
repository and lay them out for A4 at 300 DPI with a 100 mm scale bar.
