#!/usr/bin/env python3
"""Generates the launcher icons from design/logo-source.png (needs Pillow).

- res/mipmap-*/ic_launcher.png and ic_launcher_round.png: the full logo (legacy icons, API < 26).
- res/mipmap-*/ic_launcher_foreground.png: the logo scaled so its rounded tile fits the 66 dp safe zone of the
  108 dp adaptive canvas (the tile is about 88% of the image), on a transparent canvas.
The adaptive background is the solid color #000209 (res/values/colors.xml).
Usage: python3 scripts/make-app-icon.py
"""
import os
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = Image.open(os.path.join(ROOT, "design/logo-source.png")).convert("RGB")
RES = os.path.join(ROOT, "app/src/main/res")
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
TILE_FRACTION = 0.88  # width of the rounded tile relative to the image

for density, px in LEGACY.items():
    out = os.path.join(RES, f"mipmap-{density}")
    os.makedirs(out, exist_ok=True)
    icon = SRC.resize((px, px), Image.LANCZOS)
    icon.save(os.path.join(out, "ic_launcher.png"))
    icon.save(os.path.join(out, "ic_launcher_round.png"))
    canvas = px * 108 // 48
    logo_px = round(canvas * (66 / 108) / TILE_FRACTION)
    fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    scaled = SRC.resize((logo_px, logo_px), Image.LANCZOS).convert("RGBA")
    fg.paste(scaled, ((canvas - logo_px) // 2, (canvas - logo_px) // 2))
    fg.save(os.path.join(out, "ic_launcher_foreground.png"))
print("done")
