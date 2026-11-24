#!/usr/bin/env python3
"""
Generate a 6x6 AprilGrid (tag36h11) calibration target for A4 at 300 DPI.

Layout: tagSize = 25 mm (black tag, 8x8 modules), tagSpacing = 0.3 (gap 7.5 mm),
6x6 tags, IDs 0..35 row-major. A 100 mm scale bar is printed at the bottom.

Outputs:
  aprilgrid_a4_6x6_25mm.pdf
  aprilgrid_a4_6x6_25mm.jpg
  aprilgrid_a4_6x6_25mm.png  (preview)
"""
import io
import math
import os
import urllib.request

from PIL import Image, ImageDraw, ImageFont

DPI = 300
A4_W = int(round(210 / 25.4 * DPI))   # 2480
A4_H = int(round(297 / 25.4 * DPI))   # 3508
TAG_MM = 25.0
GAP_MM = 7.5                          # 0.3 * tagSize
COLS = ROWS = 6
TAG_PX = int(round(TAG_MM / 25.4 * DPI))
GAP_PX = int(round(GAP_MM / 25.4 * DPI))
GRID_W = COLS * TAG_PX + (COLS - 1) * GAP_PX
GRID_H = ROWS * TAG_PX + (ROWS - 1) * GAP_PX
URL = "https://raw.githubusercontent.com/AprilRobotics/apriltag-imgs/master/tag36h11/tag36_11_{:05d}.png"
HERE = os.path.dirname(os.path.abspath(__file__))


def load_tag(idx: int) -> Image.Image:
    data = urllib.request.urlopen(URL.format(idx), timeout=30).read()
    im = Image.open(io.BytesIO(data)).convert("RGBA")
    bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
    im = Image.alpha_composite(bg, im).convert("L")
    w, h = im.size
    border = max(1, int(round(w / 10.0)))  # 1-module white quiet zone
    im = im.crop((border, border, w - border, h - border))  # 8x8: 6x6 data + 1-module black border
    # Kalibr's ETH AprilGrid detector expects a 2-module black border:
    # add one more black ring -> 10x10 tag (6x6 data + 2+2 border).
    tag = Image.new("L", (10, 10), 0)
    tag.paste(im, (1, 1))
    # Kalibr's own PDF generator prints the tag code rotated by 180 degrees
    # (generateAprilTag(..., rotation=2)); the detector's canonical corner order
    # depends on it, so match it exactly.
    return tag.resize((TAG_PX, TAG_PX), Image.NEAREST)


def main():
    canvas = Image.new("RGB", (A4_W, A4_H), "white")
    x0 = (A4_W - GRID_W) // 2
    y0 = 300
    for r in range(ROWS):
        for c in range(COLS):
            # Kalibr numbers tags row-major starting at the BOTTOM row (y axis up).
            idx = (ROWS - 1 - r) * COLS + c
            canvas.paste(load_tag(idx), (x0 + c * (TAG_PX + GAP_PX), y0 + r * (TAG_PX + GAP_PX)))

    draw = ImageDraw.Draw(canvas)
    # Black squares at the tag corners, like Kalibr's reference PDF. They make the
    # corner pattern symmetric, which improves subpixel refinement.
    for r in range(ROWS):
        for c in range(COLS):
            tx = x0 + c * (TAG_PX + GAP_PX)
            ty = y0 + r * (TAG_PX + GAP_PX)
            for sx, sy in ((tx - GAP_PX, ty - GAP_PX), (tx + TAG_PX, ty - GAP_PX),
                           (tx + TAG_PX, ty + TAG_PX), (tx - GAP_PX, ty + TAG_PX)):
                draw.rectangle([sx, sy, sx + GAP_PX - 1, sy + GAP_PX - 1], fill="black")

    font_path = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
    title = ImageFont.truetype(font_path, 40)
    small = ImageFont.truetype(font_path, 28)

    draw.text((x0, 90), "AprilGrid 6x6  |  tagSize 25 mm  |  tagSpacing 0.3", fill="black", font=title)
    draw.text((x0, 150), "IDs 0..35 (row-major, bottom row first). Print at 100%. Tag = 25 mm incl. 2-module border.", fill="black", font=small)

    # 100 mm scale bar
    bar_px = int(round(100 / 25.4 * DPI))
    bx = x0
    by = y0 + GRID_H + 100
    draw.line([(bx, by), (bx + bar_px, by)], fill="black", width=6)
    for t in range(0, 101, 10):
        tx = bx + int(round(t / 25.4 * DPI))
        draw.line([(tx, by - 16), (tx, by + 16)], fill="black", width=3)
    draw.text((bx, by + 30), "100 mm  —  measure this bar after printing", fill="black", font=small)
    draw.text((bx, by + 80),
              "If the bar is not 100 mm, scale tagSize: 25 mm * (measured_mm / 100).",
              fill="black", font=small)

    pdf = os.path.join(HERE, "aprilgrid_a4_6x6_25mm.pdf")
    jpg = os.path.join(HERE, "aprilgrid_a4_6x6_25mm.jpg")
    png = os.path.join(HERE, "aprilgrid_a4_6x6_25mm.png")
    canvas.save(pdf, "PDF", resolution=DPI)
    canvas.save(jpg, "JPEG", quality=95, dpi=(DPI, DPI))
    canvas.save(png, "PNG", dpi=(DPI, DPI))
    print("written:", pdf)
    print("written:", jpg)
    print("written:", png)
    print("A4 px:", A4_W, A4_H, "tag px:", TAG_PX, "gap px:", GAP_PX, "grid px:", GRID_W, GRID_H)


if __name__ == "__main__":
    main()
