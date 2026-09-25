"""Builds the Nuvio RS launcher and splash icons from nuvio-rs-logo.png (logo on a flat #191E25 background).

Usage: python3 branding/generate_icons.py branding/nuvio-rs-logo.png composeApp/src/androidMain/res preview.png
"""
import sys, os
import numpy as np
from PIL import Image, ImageDraw

src = Image.open(sys.argv[1]).convert("RGB")
res = sys.argv[2]
BG = np.array([25, 30, 37], dtype=float)
CENTER = (652, 615)          # centre of the logo body in the source
CANVAS = 1560                # source px mapped to the 108dp adaptive canvas / 240dp splash

# Colour-to-alpha against the flat background, so the glow stays soft on any background.
px = np.asarray(src).astype(float)
up = np.where(px > BG, (px - BG) / (255 - BG), (BG - px) / BG)
raw = up.max(axis=2)
safe = np.maximum(raw, 1e-6)[..., None]
alpha = np.clip((raw - 0.03) / 0.97, 0, 1)
rgb = np.clip((px - BG) / safe + BG, 0, 255)
rgba = np.dstack([rgb, alpha * 255]).astype(np.uint8)
logo = Image.fromarray(rgba, "RGBA")

canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
canvas.paste(logo, (CANVAS // 2 - CENTER[0], CANVAS // 2 - CENTER[1]), logo)

# Monochrome: solid logo body (ring + play triangle), no glow.
a = np.asarray(canvas)[..., 3].astype(float) / 255
mono_a = np.clip((a - 0.55) / 0.25, 0, 1)
mono = Image.fromarray(np.dstack([np.full_like(mono_a, 255)] * 3 + [mono_a * 255]).astype(np.uint8), "RGBA")

# Legacy (pre-26) square icon: the original artwork, cropped around the logo.
side = 1070
legacy = src.crop((CENTER[0] - side // 2, CENTER[1] - side // 2, CENTER[0] + side // 2, CENTER[1] + side // 2))

densities = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for name, scale in densities.items():
    folder = os.path.join(res, f"mipmap-{name}")
    fg = round(108 * scale)
    canvas.resize((fg, fg), Image.LANCZOS).save(os.path.join(folder, "ic_launcher_foreground.webp"), lossless=True)
    mono.resize((fg, fg), Image.LANCZOS).save(os.path.join(folder, "ic_launcher_monochrome.webp"), lossless=True)
    size = round(48 * scale)
    square = legacy.resize((size, size), Image.LANCZOS)
    square.save(os.path.join(folder, "ic_launcher.webp"), lossless=True)
    big = legacy.resize((size * 4, size * 4), Image.LANCZOS).convert("RGBA")
    mask = Image.new("L", big.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, big.width - 1, big.height - 1), fill=255)
    big.putalpha(mask)
    big.resize((size, size), Image.LANCZOS).save(os.path.join(folder, "ic_launcher_round.webp"), lossless=True)

canvas.resize((1080, 1080), Image.LANCZOS).save(os.path.join(res, "drawable-nodpi", "ic_splash_logo.webp"), lossless=True)
# Previews for checking by eye.
prev = Image.new("RGBA", (1400, 700), (13, 13, 13, 255))
prev.paste(canvas.resize((700, 700)), (0, 0), canvas.resize((700, 700)))
bgc = Image.new("RGBA", (700, 700), (25, 30, 37, 255)); m = Image.new("L", (700, 700), 0)
ImageDraw.Draw(m).ellipse((700*18//108, 700*18//108, 700*90//108, 700*90//108), fill=255)
fgc = Image.alpha_composite(bgc, canvas.resize((700, 700))); fgc.putalpha(m)
prev.paste(fgc, (700, 0), fgc)
prev.save(sys.argv[3])
mono.resize((400, 400)).save(sys.argv[3].replace(".png", "-mono.png"))
