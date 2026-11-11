#!/usr/bin/env python3
"""Turns the generated art sheets in art/source into game-ready sprites.

* Chroma key (green #00FF00 or magenta #FF00FF) with soft alpha, colour un-mixing of
  semi-transparent edges (removes the green/pink halo) and JPEG-noise cleanup.
* Splits grid sheets into single sprites (gap detection on the alpha mask), trims, pads,
  resizes and writes lossy WebP with alpha to app/src/main/assets/sprites/.
* Re-encodes chapter backgrounds to app/src/main/assets/bg/.

Run: python3 tools/process_art.py   (requires numpy + Pillow)
"""
import os
import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "art", "source")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sprites")
BG_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "bg")

GREEN = np.array([0, 255, 0], np.float32)
MAGENTA = np.array([255, 0, 255], np.float32)


def key(img: Image.Image, keycol, lo=70.0, hi=150.0) -> np.ndarray:
    """Returns float RGBA (0..1) with background removed."""
    rgb = np.asarray(img.convert("RGB"), np.float32)
    d = np.sqrt(((rgb - keycol) ** 2).sum(-1))
    a = np.clip((d - lo) / (hi - lo), 0.0, 1.0)
    # Un-mix: observed = a*C + (1-a)*K  ->  C = (observed - (1-a)K) / a
    safe = np.maximum(a, 1e-3)[..., None]
    col = (rgb - (1 - a)[..., None] * keycol) / safe
    col = np.clip(col, 0, 255)
    # Despill near edges: no channel pattern of the key colour may dominate.
    if keycol is GREEN:
        lim = np.maximum(col[..., 0], col[..., 2])
        spill = col[..., 1] > lim + 8
        edge = a < 0.999
        col[..., 1] = np.where(spill & edge, lim + 8, col[..., 1])
    else:
        lim = col[..., 1]
        for ch in (0, 2):
            m = (col[..., ch] > lim + 40) & (col[..., 0] > 200) & (col[..., 2] > 200) & (a < 0.999)
            col[..., ch] = np.where(m, lim + 40, col[..., ch])
    # Kill speckles: tiny alpha in background noise.
    a = np.where(a < 0.06, 0.0, a)
    return np.dstack([col / 255.0, a])


def segments(profile: np.ndarray, min_gap: int, min_len: int):
    on = profile > 0
    segs, start, gap = [], None, 0
    for i, v in enumerate(on):
        if v:
            if start is None:
                start = i
            gap = 0
        elif start is not None:
            gap += 1
            if gap >= min_gap:
                end = i - gap + 1
                if end - start >= min_len:
                    segs.append((start, end))
                start, gap = None, 0
    if start is not None and len(on) - start >= min_len:
        segs.append((start, len(on)))
    return segs


def split_grid(rgba: np.ndarray, min_gap=25):
    """Rows first, then columns inside each row. Returns sprites in reading order."""
    mask = (rgba[..., 3] > 0.25).astype(np.int32)
    out = []
    for (r0, r1) in segments(mask.sum(1), min_gap, 40):
        band = mask[r0:r1]
        for (c0, c1) in segments(band.sum(0), min_gap, 40):
            cell = rgba[r0:r1, c0:c1]
            m = cell[..., 3] > 0.02
            ys, xs = np.where(m)
            out.append(cell[ys.min():ys.max() + 1, xs.min():xs.max() + 1])
    return out


def to_image(rgba: np.ndarray) -> Image.Image:
    return Image.fromarray((np.clip(rgba, 0, 1) * 255).astype(np.uint8), "RGBA")


def save(sprite: np.ndarray, name: str, size: int, rotate: float = 0.0, square=True, pad=0.04):
    im = to_image(sprite)
    if rotate:
        im = im.rotate(rotate, resample=Image.BICUBIC, expand=True)
        bbox = im.getbbox()
        im = im.crop(bbox)
    w, h = im.size
    if square:
        side = int(max(w, h) * (1 + 2 * pad))
        canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        canvas.paste(im, ((side - w) // 2, (side - h) // 2))
        im = canvas.resize((size, size), Image.LANCZOS)
    else:
        scale = size / max(w, h)
        im = im.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)
    im.save(os.path.join(OUT, name + ".webp"), "WEBP", quality=92, method=6)
    return im.size


def sheet(file, keycol, names, size, overrides=None, min_gap=25):
    rgba = key(Image.open(os.path.join(SRC, file)), keycol)
    parts = split_grid(rgba, min_gap)
    assert len(parts) == len(names), f"{file}: found {len(parts)} sprites, expected {len(names)}"
    for part, name in zip(parts, names):
        if name is None:
            continue
        opts = (overrides or {}).get(name, {})
        print(f"  {name:16s} {save(part, name, opts.get('size', size), opts.get('rotate', 0.0), opts.get('square', True), opts.get('pad', 0.04))}")


def main(only=None):
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(BG_OUT, exist_ok=True)
    print("tiles")
    sheet("sheet1_tiles_magenta.jpg", MAGENTA, ["tile_0", "tile_1", "tile_2", "tile_3", "tile_4", "tile_5"], 192)
    print("specials")
    rgba = key(Image.open(os.path.join(SRC, "sheet2_specials_green.jpg")), GREEN)
    rocket, bomb, rainbow, plane = split_grid(rgba)
    # The rocket was drawn diagonally (pointing up-right): rotate for both orientations.
    print("  rocket_h", save(rocket, "rocket_h", 192, rotate=-45))
    print("  rocket_v", save(rocket, "rocket_v", 192, rotate=45))
    print("  bomb", save(bomb, "bomb", 192))
    print("  rainbow", save(rainbow, "rainbow", 192))
    print("  plane", save(plane, "plane", 192))
    print("obstacles")
    sheet("sheet3a_obstacles_green.jpg", GREEN, [None, None, "crate_1", "crate_2", "crate_3", None], 192, {
        "crate_1": {"pad": 0.0}, "crate_2": {"pad": 0.0}, "crate_3": {"pad": 0.0}})
    sheet("sheet3c_ice_stone_magenta.jpg", MAGENTA, ["ice_1", "ice_2", "stone"], 192, {
        "ice_1": {"pad": 0.0}, "ice_2": {"pad": 0.0}, "stone": {"pad": 0.0}})
    sheet("sheet3b_overlays_green.jpg", GREEN, ["chain_1", "chain_2", "web", "lock", "charm", "key"], 192, {
        "chain_1": {"pad": 0.0}, "chain_2": {"pad": 0.0}, "web": {"pad": 0.0}, "lock": {"pad": 0.0}, "key": {"size": 128}})
    print("board")
    sheet("sheet4_board_green.jpg", GREEN, ["cell_light", "cell_pink", "portal", "conveyor"], 160, {
        "cell_light": {"pad": 0.0}, "cell_pink": {"pad": 0.0}, "conveyor": {"pad": 0.0}})
    print("particles")
    sheet("sheet5_particles_green.jpg", GREEN, [None, None, "p_petal", "p_leaf", "p_splinter", "p_shard", "p_smoke", "p_coin"], 96)
    print("ui")
    sheet("sheet6_ui_green.jpg", GREEN, ["btn_pink", "btn_lavender", "ui_coin", "ui_token", "ui_panel", "ui_star"], 192, {
        "btn_pink": {"size": 480, "square": False}, "btn_lavender": {"size": 480, "square": False},
        "ui_panel": {"size": 384, "square": False}})
    print("icons")
    sheet("sheet7_icons_magenta.jpg", MAGENTA, [
        "icon_hammer", "icon_super_hammer", "icon_glove", "icon_shuffle",
        "icon_area", "icon_moves", "nav_map", "nav_collection",
        "nav_loot", "nav_shop", "nav_profile", "nav_settings",
        "chest_common", "chest_rare", "chest_epic", "chest_legendary"], 160)
    print("backgrounds")
    for n in range(1, 21):
        im = Image.open(os.path.join(SRC, f"bg_chapter_{n:02d}.jpg")).convert("RGB")
        w = 1080
        im = im.resize((w, round(im.size[1] * w / im.size[0])), Image.LANCZOS)
        im.save(os.path.join(BG_OUT, f"chapter_{n:02d}.webp"), "WEBP", quality=80, method=6)
        thumb = im.resize((360, round(im.size[1] * 360 / im.size[0])), Image.LANCZOS)
        thumb.save(os.path.join(BG_OUT, f"chapter_{n:02d}_thumb.webp"), "WEBP", quality=75, method=6)
    print("done")


if __name__ == "__main__":
    main()
