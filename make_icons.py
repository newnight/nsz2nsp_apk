#!/usr/bin/env python3
"""Generate Android adaptive-icon foreground PNGs from the legacy launcher PNG.

The adaptive foreground layer is 108x108dp with a ~72dp visible area and a
66dp safe-zone circle. We paste the artwork scaled to 62% of the canvas,
centered, so nothing gets clipped by launcher masks.
Also prints the average border color of the source art (for the background color).
"""
from PIL import Image
from pathlib import Path

RES = Path("/Users/biubiubiu/WorkBuddy/2026-08-31-11-04-55/nszapp/app/src/main/res")
SRC = RES / "mipmap-xxxhdpi" / "ic_launcher.png"  # 192x192
SCALE = 0.62

# density -> foreground canvas px (108dp)
SIZES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

src = Image.open(SRC).convert("RGBA")
print("source size:", src.size)

# average border color (background layer color, seamless with art edges)
px = src.load()
w, h = src.size
border = [px[x, 0] for x in range(w)] + [px[x, h - 1] for x in range(w)] + \
         [px[0, y] for y in range(h)] + [px[w - 1, y] for y in range(h)]
avg = tuple(sum(c[i] for c in border) // len(border) for i in range(3))
print("border avg color: #%02X%02X%02X" % avg)

for density, canvas in SIZES.items():
    art = src.resize((round(canvas * SCALE), round(canvas * SCALE)), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    off = (canvas - art.width) // 2
    out.paste(art, (off, off), art)
    d = RES / f"mipmap-{density}"
    if not d.exists():
        d.mkdir()
    out.save(d / "ic_launcher_foreground.png")
    print(f"wrote mipmap-{density}/ic_launcher_foreground.png ({canvas}x{canvas})")
