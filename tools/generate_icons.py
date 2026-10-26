#!/usr/bin/env python3
"""Generate the source-controlled UBT Android launcher and Android TV assets."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
REGULAR = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

NAVY = (12, 32, 49)
NAVY_LIGHT = (24, 59, 82)
TEAL = (43, 202, 205)
WHITE = (246, 250, 252)
MUTED = (176, 202, 215)


def font(path: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size=size)


def draw_badge(size: int) -> Image.Image:
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    center = size / 2
    radius = size * 0.30
    box = (center - radius, center - radius, center + radius, center + radius)
    draw.ellipse(box, fill=NAVY_LIGHT + (255,), outline=TEAL + (255,), width=max(3, round(size * 0.022)))

    mark_font = font(FONT, round(size * 0.225))
    draw.text((center, center - size * 0.035), "UBT", font=mark_font, fill=WHITE + (255,), anchor="mm")
    bar_width = round(size * 0.22)
    bar_height = max(5, round(size * 0.022))
    bar_y = round(center + size * 0.17)
    draw.rounded_rectangle(
        (round(center - bar_width / 2), bar_y, round(center + bar_width / 2), bar_y + bar_height),
        radius=bar_height // 2,
        fill=TEAL + (255,),
    )
    return image


def create_launcher_foreground() -> None:
    image = draw_badge(432)
    out = RES / "drawable-xxxhdpi" / "ic_launcher_foreground.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    image.save(out, format="PNG", optimize=True)


def create_tv_banner() -> None:
    width, height = 1280, 720
    image = Image.new("RGB", (width, height))
    draw = ImageDraw.Draw(image)
    for x in range(width):
        t = x / (width - 1)
        color = tuple(round(NAVY[i] * (1 - t) + NAVY_LIGHT[i] * t) for i in range(3))
        draw.line((x, 0, x, height), fill=color)

    # Keep the banner clean and readable at Android TV launcher scale.
    draw.rectangle((0, 0, 14, height), fill=TEAL)
    badge = draw_badge(380)
    image.paste(badge, (48, 170), badge)

    title_font = font(FONT, 92)
    subtitle_font = font(REGULAR, 38)
    draw.text((485, 270), "UBT Android", font=title_font, fill=WHITE)
    draw.rounded_rectangle((490, 390, 720, 402), radius=6, fill=TEAL)
    draw.text((490, 435), "Amlogic USB Burning Tool", font=subtitle_font, fill=MUTED)

    out = RES / "drawable-xxxhdpi" / "tv_banner.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    image.save(out, format="PNG", optimize=True)


if __name__ == "__main__":
    create_launcher_foreground()
    create_tv_banner()
