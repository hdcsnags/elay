#!/usr/bin/env python3
"""Render the ELAY "el." monogram to every raster launcher asset (Stage 6 D3).

The mark is the word "el." set in Newsreader Italic — the SAME bundled face the
app ships (shared/src/commonMain/composeResources/font/newsreader_italic.ttf),
so vector (extracted glyph outlines in ic_launcher_foreground.xml) and raster
assets come from one source of truth. The Rose Fig (#DF9EAC) full stop uses the
font's own period glyph, recoloured.

Produces:
  - androidApp/src/main/res/mipmap-{m,h,xh,xxh,xxx}dpi/ic_launcher.png and
    ic_launcher_round.png (identical content; launchers mask the shape) at
    48/72/96/144/192 px: flat Obsidian Truffle (#171415) ground, mark scaled to
    the adaptive-icon safe-circle proportion (~61% of canvas), Cashmere Silk
    (#F3EFEA) letterforms.
  - iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png at
    1024x1024, sRGB, NO alpha channel, square corners (Apple masks it).

Requires Pillow. Anti-aliased by drawing at 4x and downsampling with LANCZOS.
Usage: python scripts/render-icon.py
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

REPO_ROOT = Path(__file__).resolve().parent.parent
FONT_PATH = (
    REPO_ROOT
    / "shared"
    / "src"
    / "commonMain"
    / "composeResources"
    / "font"
    / "newsreader_italic.ttf"
)

BACKGROUND = "#171415"  # Obsidian Truffle
MARK = "#F3EFEA"  # Cashmere Silk
DOT = "#DF9EAC"  # Rose Fig

# The mark ("el" + period) occupies the adaptive safe-circle proportion of the
# canvas: total mark box <= ~61% of the edge, matching the vector composition
# (46.7 units on the 108 grid, centred at 54,54).
MARK_FRACTION = 46.7 / 108.0


def render(size_px, no_alpha=False, supersample=4):
    S = size_px * supersample
    img = Image.new("RGBA", (S, S), BACKGROUND)
    d = ImageDraw.Draw(img)

    text = "el."
    # binary-search a font size whose "el." bbox width hits MARK_FRACTION * S
    target_w = S * MARK_FRACTION
    lo, hi = 4, S
    while hi - lo > 1:
        mid = (lo + hi) // 2
        f = ImageFont.truetype(str(FONT_PATH), mid)
        bbox = d.textbbox((0, 0), text, font=f)
        if bbox[2] - bbox[0] <= target_w:
            lo = mid
        else:
            hi = mid
    f = ImageFont.truetype(str(FONT_PATH), lo)

    # draw "el" silk and the period rose, centring the FULL "el." box
    full = d.textbbox((0, 0), text, font=f)
    el = d.textbbox((0, 0), "el", font=f)
    ox = (S - (full[2] - full[0])) / 2 - full[0]
    oy = (S - (full[3] - full[1])) / 2 - full[1]
    d.text((ox, oy), "el", font=f, fill=MARK)
    # the period is drawn as part of the same string for exact metrics: overdraw
    # the period area by rendering the full string in rose on a scratch layer and
    # masking out the "el" extent would be lossy; instead draw the period at the
    # advance of "el".
    adv_el = d.textlength("el", font=f)
    d.text((ox + adv_el, oy), ".", font=f, fill=DOT)

    img = img.resize((size_px, size_px), Image.LANCZOS)
    if no_alpha:
        img = img.convert("RGB")
    return img


MIPMAP_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def main():
    res_dir = REPO_ROOT / "androidApp" / "src" / "main" / "res"
    for dirname, size in MIPMAP_SIZES.items():
        out_dir = res_dir / dirname
        out_dir.mkdir(parents=True, exist_ok=True)
        icon = render(size)
        icon.save(out_dir / "ic_launcher.png")
        icon.save(out_dir / "ic_launcher_round.png")
        print(f"wrote {out_dir} ic_launcher(.png/_round.png) {size}x{size}")

    ios_path = (
        REPO_ROOT
        / "iosApp"
        / "iosApp"
        / "Assets.xcassets"
        / "AppIcon.appiconset"
        / "app-icon-1024.png"
    )
    ios_icon = render(1024, no_alpha=True)
    assert ios_icon.mode == "RGB", "iOS icon must have no alpha channel"
    ios_icon.save(ios_path)
    print(f"wrote {ios_path} (1024x1024, mode={ios_icon.mode})")


if __name__ == "__main__":
    main()
