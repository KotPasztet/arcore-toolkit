#!/usr/bin/env python3
"""Verify the AprilTag 36h11 ID 0 matrix hardcoded in TagPdfExporter.java.

Strategy:
1. Derive the 10x10 print-layout matrix from the vendored C source
   (third_party/apriltag/tag36h11.c): codedata[0] = 0x0000000d7e00984b,
   nbits=36, bit_x/bit_y give each bit's position inside the 8-module
   border grid, a "1" code bit = WHITE module. width_at_border=8,
   total_width=10 (1-module white quiet ring).
2. Cross-check against OpenCV's own DICT_APRILTAG_36h11 bit list for ID 0.
3. Render the EXACT Java constant (canonically copied below) to a PNG at
   high resolution and detect it with cv2.aruco.ArucoDetector
   (DICT_APRILTAG_36h11). Assert: exactly one marker, id == 0, and the
   detected corners coincide with the printed outer black square.

CANONICAL MATRIX (must match TagPdfExporter.TAG36H11_ID0 byte for byte;
true = black; the outer ring is the 1-module white quiet zone):
row0: 0 0 0 0 0 0 0 0 0 0
row1: 0 1 1 1 1 1 1 1 1 0
row2: 0 1 0 0 1 0 1 0 1 0
row3: 0 1 1 0 0 0 1 0 1 0
row4: 0 1 1 0 0 1 1 1 1 0
row5: 0 1 0 1 0 1 1 1 1 0
row6: 0 1 1 0 1 0 0 1 1 0
row7: 0 1 1 1 1 0 1 1 1 0
row8: 0 1 1 1 1 1 1 1 1 0
row9: 0 0 0 0 0 0 0 0 0 0
"""
import sys

CODE0 = 0x0000000D7E00984B & ((1 << 36) - 1)  # codedata[0], lower 36 bits

# bit_x[i], bit_y[i] from tag36h11_create() in third_party/apriltag/tag36h11.c
BIT_X = [1, 2, 3, 4, 5, 2, 3, 4, 3, 6, 6, 6, 6, 6, 5, 5, 5, 4, 6, 5,
         4, 3, 2, 5, 4, 3, 4, 1, 1, 1, 1, 1, 2, 2, 2, 3]
BIT_Y = [1, 1, 1, 1, 1, 2, 2, 2, 3, 1, 2, 3, 4, 5, 2, 3, 4, 3, 6, 6,
         6, 6, 6, 5, 5, 5, 4, 6, 5, 4, 3, 2, 5, 4, 3, 4]
WIDTH_AT_BORDER = 8
TOTAL_WIDTH = 10

# The Java constant, transcribed verbatim from TagPdfExporter.java.
JAVA_MATRIX = [
    [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 1, 1, 1, 1, 1, 1, 1, 1, 0],
    [0, 1, 0, 0, 1, 0, 1, 0, 1, 0],
    [0, 1, 1, 0, 0, 0, 1, 0, 1, 0],
    [0, 1, 1, 0, 0, 1, 1, 1, 1, 0],
    [0, 1, 0, 1, 0, 1, 1, 1, 1, 0],
    [0, 1, 1, 0, 1, 0, 0, 1, 1, 0],
    [0, 1, 1, 1, 1, 0, 1, 1, 1, 0],
    [0, 1, 1, 1, 1, 1, 1, 1, 1, 0],
    [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
]


def derive_from_c():
    """Build the 8x8 border grid from the C code: bit '1' = white."""
    tag = [[False] * WIDTH_AT_BORDER for _ in range(WIDTH_AT_BORDER)]
    for row in range(WIDTH_AT_BORDER):
        for col in range(WIDTH_AT_BORDER):
            tag[row][col] = True  # black border ring
    for i in range(36):
        bit = (CODE0 >> (35 - i)) & 1  # MSB first
        x, y = BIT_X[i], BIT_Y[i]
        tag[y][x] = (bit == 0)  # code bit 1 = white module -> False (not black)
    # 10x10 print layout with a 1-module white quiet ring
    grid = [[False] * TOTAL_WIDTH for _ in range(TOTAL_WIDTH)]
    for row in range(WIDTH_AT_BORDER):
        for col in range(WIDTH_AT_BORDER):
            grid[row + 1][col + 1] = tag[row][col]
    return grid


def grids_equal(a, b):
    return len(a) == len(b) and all(
        len(ra) == len(rb) and all(a[r][c] == b[r][c] for c in range(len(ra)))
        for r, (ra, rb) in enumerate(zip(a, b)))


def opencv_dict_data_grid(cv2):
    """OpenCV's canonical 6x6 data grid for APRILTAG_36h11 ID 0.

    OpenCV (>=4.7) stores AprilTag families as markerSize=6 dictionaries
    (just the data field; the 1-module black border is implicit). Read the
    grid back from generateImageMarker pixels instead of unpacking
    bytesList by hand.
    """
    import numpy as np
    d = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_APRILTAG_36h11)
    print(f"opencv dict: markerSize={d.markerSize} bytesList.shape={d.bytesList.shape}")
    total = d.markerSize + 2  # data + 1-module black border on each side
    cell = 80
    img = cv2.aruco.generateImageMarker(d, 0, total * cell, 1)
    binimg = (img < 128).astype(np.uint8)
    grid = [[int(binimg[r * cell + cell // 2][c * cell + cell // 2])
             for c in range(total)] for r in range(total)]
    # Strip the black border ring -> 6x6 data grid (1 = black).
    return [[grid[r + 1][c + 1] for c in range(d.markerSize)]
            for r in range(d.markerSize)]


def rotate90(grid):
    n = len(grid)
    return [[grid[n - 1 - c][r] for c in range(n)] for r in range(n)]


def main():
    import numpy as np
    import cv2
    from PIL import Image

    derived = derive_from_c()
    print("== matrix derived from tag36h11.c (1=black, row-major) ==")
    for row in derived:
        print("".join("B" if v else "W" for v in row))

    same = grids_equal(derived, [[bool(v) for v in row] for row in JAVA_MATRIX])
    print("java matrix identical to C-derived matrix:", same)
    if not same:
        print("FATAL: TagPdfExporter.TAG36H11_ID0 does not match the C code")
        sys.exit(1)

    # Cross-check with OpenCV's own dictionary data field.
    dict_grid = opencv_dict_data_grid(cv2)
    print("== OpenCV DICT_APRILTAG_36h11 ID0 data grid (1=black) ==")
    for row in dict_grid:
        print("".join("B" if v else "W" for v in row))
    # Derived data field = 6x6 interior of the 8-module border grid
    # (10x10 rows/cols 2..7).
    derived_data = [[1 if derived[r][c] else 0
                     for c in range(2, 8)] for r in range(2, 8)]
    print("== derived 6x6 data field (1=black) ==")
    for row in derived_data:
        print("".join("B" if v else "W" for v in row))
    rotations = [derived_data,
                 rotate90(derived_data),
                 rotate90(rotate90(derived_data)),
                 rotate90(rotate90(rotate90(derived_data)))]
    match = any(grids_equal([[bool(v) for v in r] for r in rot],
                            [[bool(v) for v in r] for r in dict_grid])
                for rot in rotations)
    print("opencv dict data grid matches derived data field (up to rotation):", match)

    # Render the EXACT Java matrix and detect it.
    module = 100  # px per module
    quiet = 3 * module  # extra white padding around the printed block
    px = TOTAL_WIDTH * module
    img = np.full((px + 2 * quiet, px + 2 * quiet), 255, dtype=np.uint8)
    for r in range(TOTAL_WIDTH):
        for c in range(TOTAL_WIDTH):
            if JAVA_MATRIX[r][c]:
                y = quiet + r * module
                x = quiet + c * module
                img[y:y + module, x:x + module] = 0
    Image.fromarray(img).save("/tmp/tag_matrix_render.png")

    detector = cv2.aruco.ArucoDetector(
        cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_APRILTAG_36h11),
        cv2.aruco.DetectorParameters())
    corners, ids, _ = detector.detectMarkers(img)
    if ids is None or len(np.asarray(ids).reshape(-1)) != 1:
        print("FAIL: detections:", 0 if ids is None else len(np.asarray(ids).reshape(-1)))
        sys.exit(2)
    # Detector-independent proof: decode the 36-bit code straight from the
    # rendered image using the C reading order and compare with codedata[0].
    rcode = 0
    for i in range(36):
        x, y = BIT_X[i], BIT_Y[i]
        px_x = quiet + (x + 1) * module + module // 2  # +1: white ring offset
        px_y = quiet + (y + 1) * module + module // 2
        is_white = img[px_y][px_x] > 128
        rcode = (rcode << 1) | (1 if is_white else 0)
    print("code decoded from rendered image: 0x%09x" % rcode)
    print("code from tag36h11.c codedata[0]: 0x%09x" % CODE0)
    code_ok = rcode == CODE0
    print("exact code match:", code_ok)

    det_id = int(np.asarray(ids).reshape(-1)[0])
    quad = np.asarray(corners[0], dtype=float).reshape(-1, 2)[:4]  # 4x2
    print("detected id:", det_id)
    print("corners (x,y):")
    for p in quad:
        print(f"  ({p[0]:.1f}, {p[1]:.1f})")

    # Expected outer black square: matrix rows/cols 1..8. OpenCV corner
    # ordering depends on the detected orientation, so match as a set.
    exp = np.array([
        [quiet + 1 * module, quiet + 1 * module],
        [quiet + 9 * module, quiet + 1 * module],
        [quiet + 9 * module, quiet + 9 * module],
        [quiet + 1 * module, quiet + 9 * module],
    ], dtype=float)
    dists = []
    for p in quad:
        dists.append(float(np.min(np.linalg.norm(exp - p, axis=1))))
    print("per-corner nearest expected-corner distances (px):",
          [f"{d:.1f}" for d in dists])
    scale_ok = all(d < module * 0.2 for d in dists)
    print("corners match outer black square within 0.2 module:", scale_ok)
    print("black-square width px:", 8 * module)

    ok = det_id == 0 and same and scale_ok and code_ok
    print("VERDICT:", "PASS" if ok else "FAIL")

    # Cross-check against the OFFICIAL tag36h11_00000.png from
    # AprilRobotics/apriltag-imgs (1 pixel = 1 module, RGBA).
    official_path = "/tmp/tagverify/tag36h11_00000.png"
    official = np.array(Image.open(official_path).convert("L"))
    print("official PNG size:", official.shape)
    if official.shape == (TOTAL_WIDTH, TOTAL_WIDTH):
        official_black = official < 128
        png_ok = grids_equal([[bool(v) for v in row] for row in official_black],
                             [[bool(v) for v in row] for row in JAVA_MATRIX])
        if not png_ok:
            print("  differing cells (row, col):",
                  [[r, c] for r in range(TOTAL_WIDTH) for c in range(TOTAL_WIDTH)
                   if bool(official_black[r][c]) != bool(JAVA_MATRIX[r][c])])
        print("official PNG == Java matrix (module by module):", png_ok)
        corners3, ids3, _ = detector.detectMarkers(
            np.kron(official, np.ones((40, 40), dtype=np.uint8)))
        png_ids = (np.asarray(ids3).reshape(-1).tolist() if ids3 is not None else [])
        print("OpenCV detection on official PNG: ids =", [int(i) for i in png_ids])
        ok = ok and png_ok and png_ids == [0]
        print("FINAL VERDICT:", "PASS" if ok else "FAIL")
    else:
        print("official PNG has unexpected size; skipped module comparison")
    sys.exit(0 if ok else 3)


if __name__ == "__main__":
    main()
