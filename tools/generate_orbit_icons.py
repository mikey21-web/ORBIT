from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android-app" / "app" / "src" / "main" / "res"
SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
S = 512
CENTER = (16, 26, 46)
EDGE = (6, 10, 20)
ACCENT = (88, 215, 255)
ACCENT_SOFT = (123, 140, 255)


def rounded_mask(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return mask


def background() -> Image.Image:
    ramp = Image.radial_gradient("L").resize((S, S), Image.Resampling.BICUBIC)
    base = Image.composite(
        Image.new("RGB", (S, S), EDGE),
        Image.new("RGB", (S, S), CENTER),
        ramp,
    ).convert("RGBA")
    base.putalpha(rounded_mask(S, int(S * 0.22)))
    return base


def orbit_ring(angle: float, width: float, color: tuple[int, int, int, int], blur: float = 0) -> Image.Image:
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    box = (S * 0.17, S * 0.35, S * 0.83, S * 0.65)
    draw.ellipse(box, outline=color, width=max(1, int(S * width)))
    layer = layer.rotate(angle, resample=Image.Resampling.BICUBIC)
    if blur:
        layer = layer.filter(ImageFilter.GaussianBlur(S * blur))
    return layer


def core() -> Image.Image:
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    r = S * 0.088
    draw.ellipse((S / 2 - r, S / 2 - r, S / 2 + r, S / 2 + r), fill=(*ACCENT, 255))
    r2 = S * 0.045
    draw.ellipse((S / 2 - r2, S / 2 - r2, S / 2 + r2, S / 2 + r2), fill=(245, 252, 255, 255))
    return layer


def satellite(angle: float) -> Image.Image:
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    theta = math.radians(38)
    x = S / 2 + (S * 0.33) * math.cos(theta)
    y = S / 2 + (S * 0.15) * math.sin(theta)
    dx, dy = x - S / 2, y - S / 2
    rad = math.radians(angle)
    x = S / 2 + dx * math.cos(rad) - dy * math.sin(rad)
    y = S / 2 + dx * math.sin(rad) + dy * math.cos(rad)
    r = S * 0.028
    draw = ImageDraw.Draw(layer)
    draw.ellipse((x - r, y - r, x + r, y + r), fill=(*ACCENT_SOFT, 255))
    return layer


def mark() -> Image.Image:
    angle = -26
    image = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    image = Image.alpha_composite(image, orbit_ring(angle, 0.03, (*ACCENT, 70), 0.018))
    image = Image.alpha_composite(image, orbit_ring(angle, 0.022, (*ACCENT_SOFT, 120), 0.008))
    image = Image.alpha_composite(image, orbit_ring(angle, 0.011, (*ACCENT, 255)))
    core_glow = core().filter(ImageFilter.GaussianBlur(S * 0.022))
    image = Image.alpha_composite(image, core_glow)
    image = Image.alpha_composite(image, core())
    image = Image.alpha_composite(image, satellite(angle))
    return image


def build() -> Image.Image:
    return Image.alpha_composite(background(), mark())


def foreground(master: Image.Image) -> Image.Image:
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    size = int(S * 0.66)
    scaled = master.resize((size, size), Image.Resampling.LANCZOS)
    layer.alpha_composite(scaled, ((S - size) // 2, (S - size) // 2))
    return layer


def main() -> None:
    master = build()
    for folder, size in SIZES.items():
        target = RES / folder
        target.mkdir(parents=True, exist_ok=True)
        out = master.resize((size, size), Image.Resampling.LANCZOS)
        path = target / "ic_launcher.png"
        out.save(path, format="PNG", optimize=True)
        print(f"{folder:16} {size}x{size}  {path.stat().st_size} bytes")
    fg_dir = RES / "drawable-nodpi"
    fg_dir.mkdir(parents=True, exist_ok=True)
    fg_path = fg_dir / "ic_launcher_foreground.png"
    foreground(mark()).save(fg_path, format="PNG", optimize=True)
    print(f"foreground       {S}x{S}  {fg_path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
