#!/usr/bin/env python3
"""Render a single large AprilTag 36h11 (ID 0) for marker-AR tests.

A4 @300 DPI. The 10x10 source image (1-module white margin + 8x8 tag with a
1-module black border) is scaled so the outer black square is 150 mm.
A 100 mm scale bar is printed below for verifying the print scale.
"""
import io
import os
import urllib.request

from PIL import Image, ImageDraw, ImageFont

DPI = 300
A4_W = int(round(210 / 25.4 * DPI))
A4_H = int(round(297 / 25.4 * DPI))
TAG_MM = 150.0                 # outer black square
TEN_MOD_MM = TAG_MM * 10 / 8   # 10-module image incl. white margin
TEN_MOD_PX = int(round(TEN_MOD_MM / 25.4 * DPI))
TAG_PX = int(round(TAG_MM / 25.4 * DPI))
URL = "https://raw.githubusercontent.com/AprilRobotics/apriltag-imgs/master/tag36h11/tag36_11_00000.png"
HERE = os.path.dirname(os.path.abspath(__file__))

data = urllib.request.urlopen(URL, timeout=30).read()
im = Image.open(io.BytesIO(data)).convert("RGBA")
bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
im = Image.alpha_composite(bg, im).convert("L")
im = im.resize((TEN_MOD_PX, TEN_MOD_PX), Image.NEAREST)

canvas = Image.new("RGB", (A4_W, A4_H), "white")
x0 = (A4_W - TEN_MOD_PX) // 2
y0 = 300
canvas.paste(im, (x0, y0))

draw = ImageDraw.Draw(canvas)
font_path = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
title = ImageFont.truetype(font_path, 44)
small = ImageFont.truetype(font_path, 30)
draw.text((x0, 110), "AprilTag 36h11  |  ID 0  |  tag 150 mm", fill="black", font=title)
draw.text((x0, 175), "Print at 100% / Actual size. Outer black square = 150 mm.", fill="black", font=small)

bar_px = int(round(100 / 25.4 * DPI))
bx = x0
by = y0 + TEN_MOD_PX + 90
draw.line([(bx, by), (bx + bar_px, by)], fill="black", width=7)
for t in range(0, 101, 10):
    tx = bx + int(round(t / 25.4 * DPI))
    draw.line([(tx, by - 18), (tx, by + 18)], fill="black", width=3)
draw.text((bx, by + 30), "100 mm - measure after printing", fill="black", font=small)
draw.text((bx, by + 80), "If not 100 mm, tagSize = 150 mm * measured/100.", fill="black", font=small)

pdf = os.path.join(HERE, "marker_a4_150mm.pdf")
png = os.path.join(HERE, "marker_a4_150mm.png")
canvas.save(pdf, "PDF", resolution=DPI)
canvas.save(png, "PNG", dpi=(DPI, DPI))
print("written", pdf, png)
print("A4", A4_W, A4_H, "10mod_px", TEN_MOD_PX, "tag_px", TAG_PX)
