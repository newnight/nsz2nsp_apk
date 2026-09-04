#!/usr/bin/env python3
"""Generate 1280x640 GitHub social preview images for nsz2nsp (macOS) and nsz2nsp_apk (Android)."""
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from pathlib import Path

W, H = 1280, 640
FONTS = Path("/System/Library/Fonts/Supplemental")
ARIAL_BLACK = str(FONTS / "Arial Black.ttf")
ARIAL_BOLD = str(FONTS / "Arial Bold.ttf")
ARIAL = str(FONTS / "Arial.ttf")
ARIAL_ROUNDED = str(FONTS / "Arial Rounded Bold.ttf")

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def gradient_bg(c_top, c_bottom):
    img = Image.new("RGB", (W, H), c_top)
    d = ImageDraw.Draw(img)
    for y in range(H):
        d.line([(0, y), (W, y)], fill=lerp(c_top, c_bottom, y / H))
    return img

def add_glow(img, cx, cy, r, color, alpha=90):
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color + (alpha,))
    glow = glow.filter(ImageFilter.GaussianBlur(120))
    img.paste(Image.alpha_composite(img.convert("RGBA"), glow).convert("RGB"), (0, 0))
    return img

def add_grid(img, color, alpha=26, step=64):
    ov = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    for x in range(0, W, step):
        d.line([(x, 0), (x, H)], fill=color + (alpha,), width=1)
    for y in range(0, H, step):
        d.line([(0, y), (W, y)], fill=color + (alpha,), width=1)
    img.paste(Image.alpha_composite(img.convert("RGBA"), ov).convert("RGB"), (0, 0))
    return img

def rounded_icon(src, size, radius_ratio=0.22):
    icon = Image.open(src).convert("RGBA").resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size, size], radius=int(size * radius_ratio), fill=255)
    icon.putalpha(mask)
    return icon

def paste_icon_with_shadow(img, icon, x, y):
    size = icon.width
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(overlay).rounded_rectangle([x + 10, y + 24, x + size + 10, y + size + 24],
                                              radius=int(size * 0.22), fill=(0, 0, 0, 130))
    overlay = overlay.filter(ImageFilter.GaussianBlur(22))
    overlay.paste(icon, (x, y), icon)
    img.paste(Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB"), (0, 0))
    return img

def chip(d, x, y, text, font, fg, bg, pad_x=18, h=46, radius=23):
    tw = d.textlength(text, font=font)
    d.rounded_rectangle([x, y, x + tw + pad_x * 2, y + h], radius=radius, fill=bg)
    d.text((x + pad_x, y + (h - font.size) / 2 - 2), text, font=font, fill=fg)
    return x + tw + pad_x * 2

def generate(out, icon_src, title, subtitle, tags, accent, c_top, c_bottom, powered):
    img = gradient_bg(c_top, c_bottom)
    img = add_glow(img, 260, 200, 260, accent, 70)
    img = add_glow(img, 1100, 560, 240, accent, 45)
    img = add_grid(img, (255, 255, 255))
    d = ImageDraw.Draw(img)

    icon = rounded_icon(icon_src, 300)
    paste_icon_with_shadow(img, icon, 96, 170)

    tx = 470
    d.text((tx, 150), title, font=ImageFont.truetype(ARIAL_BLACK, 92), fill=(255, 255, 255))
    sub_font_size = 34
    max_w = W - tx - 48
    while sub_font_size > 20:
        sf = ImageFont.truetype(ARIAL_BOLD, sub_font_size)
        if d.textlength(subtitle, font=sf) <= max_w:
            break
        sub_font_size -= 2
    d.text((tx, 285), subtitle, font=sf, fill=lerp((255, 255, 255), accent, 0.55))

    cx = tx
    cy = 380
    tag_font = ImageFont.truetype(ARIAL_BOLD, 24)
    for t in tags:
        w = chip(d, cx, cy, t, tag_font, (255, 255, 255), accent + (150,))
        cx = w + 14
        if cx > W - 320:
            cx = tx; cy += 62

    d.text((tx, H - 78), powered, font=ImageFont.truetype(ARIAL, 24), fill=(255, 255, 255, 200))
    gh = "github.com/newnight"
    ghf = ImageFont.truetype(ARIAL_BOLD, 24)
    d.text((W - 40 - d.textlength(gh, font=ghf), 44), gh, font=ghf, fill=lerp((255, 255, 255), accent, 0.45))

    img.save(out, "PNG")
    print("saved", out, img.size)

ROOT = Path("/Users/biubiubiu/WorkBuddy/2026-08-31-11-04-55")
TEAL = (52, 176, 163)
VIOLET = (139, 122, 235)

# macOS repo (violet accent to match its purple icon)
generate(
    out=str(ROOT / "nszcli/social-preview.png"),
    icon_src=str(ROOT / "nszcli/Resources/AppIcon.png"),
    title="Nsz2Nsp",
    subtitle="Native macOS NSZ / NCZ decompressor - Swift + SPM",
    tags=["Drag & Drop GUI", "Finder Quick Action", "CLI", "SHA-256 Verified"],
    accent=VIOLET, c_top=(16, 14, 34), c_bottom=(34, 30, 58),
    powered="macOS 13+  -  Apple Silicon & Intel  -  MIT",
)

# Android repo (teal accent)
generate(
    out=str(ROOT / "nszapp/social-preview.png"),
    icon_src=str(ROOT / "nszapp/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"),
    title="Nsz for Android",
    subtitle="Native Android NSZ / NCZ decompressor - Kotlin",
    tags=["Batch Scan", "SAF", "Live Progress", "SHA-256 Verified"],
    accent=TEAL, c_top=(10, 30, 40), c_bottom=(22, 55, 60),
    powered="Android 8.0+  -  arm64 / armv7 / x86 / x86_64  -  MIT",
)

