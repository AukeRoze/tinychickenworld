#!/usr/bin/env python3
"""Draw a simple gold notification bell on a transparent background.

Usage: python3 make_bell.py [out.png]
Requires: pillow  (pip install pillow)

This is a clean starter asset for the end-card bell overlay
(bible/fx/bell.png). Swap it for a designed/stock bell anytime.
"""
import sys
from PIL import Image, ImageDraw

OUT = sys.argv[1] if len(sys.argv) > 1 else "bell.png"

W = H = 320
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

GOLD = (247, 201, 72, 255)
GOLD_DK = (214, 158, 31, 255)
DARK = (90, 60, 10, 255)

# Top handle (little loop)
d.arc([148, 28, 172, 58], start=200, end=520, fill=DARK, width=10)

# Dome (top half-circle of the bell)
d.pieslice([96, 52, 224, 180], 180, 360, fill=GOLD, outline=GOLD_DK, width=6)
# Body (flares outward toward the rim)
d.polygon([(100, 116), (80, 214), (240, 214), (220, 116)],
          fill=GOLD, outline=GOLD_DK)
# Flared rim at the bottom
d.rounded_rectangle([74, 208, 246, 240], radius=16, fill=GOLD, outline=GOLD_DK, width=6)
# Clapper (the little ball under the bell)
d.ellipse([148, 240, 172, 268], fill=DARK)

# A soft highlight for a 3D feel
d.ellipse([120, 80, 150, 150], fill=(255, 255, 255, 70))

img.save(OUT)
print(f"wrote {OUT}")
