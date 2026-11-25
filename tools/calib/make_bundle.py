#!/usr/bin/env python3
"""Render the hybrid AprilTag 36h11 marker sheet (300 DPI).

A4 portrait (default): one 80 mm tag (ID 0) in the centre and four 38 mm tags
(IDs 1..4) at (+/-75, +/-100) mm.

A3 landscape (`--paper a3`): one 110 mm tag in the centre and four 55 mm tags at
(+/-150, +/-104) mm.

The shim's built-in layout is the A4 one.  The generated
`bundle_<paper>_hybrid_tags.txt` can be copied to the target app's files
directory as `tags` to override the layout.

A 100 mm scale bar is printed in the top margin.  Print at 100% / actual size.
"""
import argparse
import io
import os
import urllib.request

from PIL import Image, ImageDraw, ImageFont

DPI = 300
URL = "https://raw.githubusercontent.com/AprilRobotics/apriltag-imgs/master/tag36h11/tag36_11_{:05d}.png"
HERE = os.path.dirname(os.path.abspath(__file__))
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

PAPERS = {
    "a3": {
        "width": 420.0,
        "height": 297.0,
        "tags": [
            (0, 0.0, 0.0, 0.0, 110.0),
            (1, -150.0, -104.0, 0.0, 55.0),
            (2, 150.0, -104.0, 0.0, 55.0),
            (3, -150.0, 104.0, 0.0, 55.0),
            (4, 150.0, 104.0, 0.0, 55.0),
        ],
        "title": "AprilTag 36h11 hybrid | center 110 mm | corners 55 mm | A3 100%",
        "title_size": 40,
        "small_size": 26,
        "bar_x": 286.0,
        "bar_y": 5.0,
    },
    "a4": {
        "width": 210.0,
        "height": 297.0,
        "tags": [
            (0, 0.0, 0.0, 0.0, 80.0),
            (1, -75.0, -100.0, 0.0, 38.0),
            (2, 75.0, -100.0, 0.0, 38.0),
            (3, -75.0, 100.0, 0.0, 38.0),
            (4, 75.0, 100.0, 0.0, 38.0),
        ],
        "title": "AprilTag 36h11 hybrid | center 80 mm | corners 38 mm | A4 100%",
        "title_size": 28,
        "small_size": 20,
        "bar_x": 80.0,
        "bar_y": 13.0,
    },
}


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
    parser = argparse.ArgumentParser(description="Render a hybrid AprilTag sheet")
    parser.add_argument("--paper", choices=sorted(PAPERS), default="a4")
    args = parser.parse_args()

    paper = PAPERS[args.paper]
    sheet_w = int(round(paper["width"] / 25.4 * DPI))
    sheet_h = int(round(paper["height"] / 25.4 * DPI))
    canvas = Image.new("RGB", (sheet_w, sheet_h), "white")
    cx = sheet_w / 2.0
    cy = sheet_h / 2.0

    for tag_id, x_mm, y_mm, _yaw, size_mm in paper["tags"]:
        image = load_tag(tag_id)
        total_mm = size_mm * 10.0 / 8.0      # 10 modules incl. white margin
        total_px = mm(total_mm)
        image = image.resize((total_px, total_px), Image.NEAREST)
        x = int(round(cx + mm(x_mm) - total_px / 2.0))
        y = int(round(cy + mm(y_mm) - total_px / 2.0))
        canvas.paste(image, (x, y))

    draw = ImageDraw.Draw(canvas)
    title = font(paper["title_size"])
    small = font(paper["small_size"])
    draw.text((mm(8), mm(2)), paper["title"], fill="black", font=title)

    bx = mm(paper["bar_x"])
    by = mm(paper["bar_y"])
    draw.line([(bx, by), (bx + mm(100), by)], fill="black", width=5)
    for t in range(0, 101, 10):
        tx = bx + mm(t)
        draw.line([(tx, by - mm(2)), (tx, by + mm(2))], fill="black", width=2)
    draw.text((bx, by + mm(2.5)), "100 mm scale bar", fill="black", font=small)

    base = os.path.join(HERE, f"bundle_{args.paper}_hybrid")
    canvas.save(base + ".pdf", "PDF", resolution=DPI)
    canvas.save(base + ".png", "PNG", dpi=(DPI, DPI))
    with open(base + "_tags.txt", "w", encoding="ascii") as fh:
        fh.write("# id x_mm y_mm yaw_deg size_mm\n")
        for tag_id, x_mm, y_mm, yaw, size_mm in paper["tags"]:
            fh.write(f"{tag_id} {x_mm:g} {y_mm:g} {yaw:g} {size_mm:g}\n")
    print("written", base + ".pdf", base + ".png", base + "_tags.txt")
    print(args.paper.upper(), paper["width"], "x", paper["height"], "mm",
          sheet_w, "x", sheet_h, "px at", DPI, "DPI")


if __name__ == "__main__":
    main()
