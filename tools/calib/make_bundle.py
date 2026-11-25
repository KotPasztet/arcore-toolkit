#!/usr/bin/env python3
"""Render the hybrid AprilTag 36h11 marker sheet (A3 landscape, 300 DPI).

Layout: one 110 mm tag (ID 0) in the centre and four 55 mm tags (IDs 1..4) at
(+/-150, +/-104) mm.  The shim has the same layout built in; the generated
`bundle_a3_hybrid_tags.txt` can be copied to the target app's files directory as
`tags` to override it.

A 100 mm scale bar is printed in the top margin.  Print at 100% / actual size.
"""
import io
import os
import urllib.request

from PIL import Image, ImageDraw, ImageFont

DPI = 300
SHEET_W_MM = 420.0
SHEET_H_MM = 297.0
SHEET_W = int(round(SHEET_W_MM / 25.4 * DPI))
SHEET_H = int(round(SHEET_H_MM / 25.4 * DPI))

# id, x_mm, y_mm, yaw_deg, black-square size_mm
TAGS = [
    (0, 0.0, 0.0, 0.0, 110.0),
    (1, -150.0, -104.0, 0.0, 55.0),
    (2, 150.0, -104.0, 0.0, 55.0),
    (3, -150.0, 104.0, 0.0, 55.0),
    (4, 150.0, 104.0, 0.0, 55.0),
]
URL = "https://raw.githubusercontent.com/AprilRobotics/apriltag-imgs/master/tag36h11/tag36_11_{:05d}.png"

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


def mm(value: float) -> int:
    return int(round(value / 25.4 * DPI))


def load_tag(idx: int) -> Image.Image:
    data = urllib.request.urlopen(URL.format(idx), timeout=30).read()
    im = Image.open(io.BytesIO(data)).convert("RGBA")
    bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
    return Image.alpha_composite(bg, im).convert("L")


def font(size_px: int):
    try:
        return ImageFont.truetype(FONT, size_px)
    except Exception:
        return ImageFont.load_default()


def main() -> None:
    canvas = Image.new("RGB", (SHEET_W, SHEET_H), "white")
    cx = SHEET_W / 2.0
    cy = SHEET_H / 2.0

    for tag_id, x_mm, y_mm, _yaw, size_mm in TAGS:
        image = load_tag(tag_id)
        total_mm = size_mm * 10.0 / 8.0      # 10 modules incl. white margin
        total_px = mm(total_mm)
        image = image.resize((total_px, total_px), Image.NEAREST)
        x = int(round(cx + mm(x_mm) - total_px / 2.0))
        y = int(round(cy + mm(y_mm) - total_px / 2.0))
        canvas.paste(image, (x, y))

    draw = ImageDraw.Draw(canvas)
    title = font(40)
    small = font(26)
    draw.text((mm(12), mm(2)),
              "AprilTag 36h11 hybrid | center 110 mm | corners 55 mm | A3 100%",
              fill="black", font=title)

    # 100 mm scale bar in the top-right margin.
    bx = mm(286)
    by = mm(5)
    draw.line([(bx, by), (bx + mm(100), by)], fill="black", width=5)
    for t in range(0, 101, 10):
        tx = bx + mm(t)
        draw.line([(tx, by - mm(2)), (tx, by + mm(2))], fill="black", width=2)
    draw.text((bx, by + mm(2.5)), "100 mm scale bar", fill="black", font=small)

    pdf = os.path.join(HERE, "bundle_a3_hybrid.pdf")
    png = os.path.join(HERE, "bundle_a3_hybrid.png")
    manifest = os.path.join(HERE, "bundle_a3_hybrid_tags.txt")
    canvas.save(pdf, "PDF", resolution=DPI)
    canvas.save(png, "PNG", dpi=(DPI, DPI))
    with open(manifest, "w", encoding="ascii") as fh:
        fh.write("# id x_mm y_mm yaw_deg size_mm\n")
        for tag_id, x_mm, y_mm, yaw, size_mm in TAGS:
            fh.write(f"{tag_id} {x_mm:g} {y_mm:g} {yaw:g} {size_mm:g}\n")
    print("written", pdf, png, manifest)
    print("A3 landscape", SHEET_W, "x", SHEET_H, "px at", DPI, "DPI")


if __name__ == "__main__":
    main()
