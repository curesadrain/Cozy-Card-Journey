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


def _components(mask: np.ndarray):
    """4-connected labelling (no scipy). Returns label image (0 = background) and count."""
    h, w = mask.shape
    lab = np.zeros((h, w), np.int32)
    n = 0
    for y0, x0 in zip(*np.nonzero(mask)):
        if lab[y0, x0]:
            continue
        n += 1
        stack = [(y0, x0)]
        lab[y0, x0] = n
        while stack:
            y, x = stack.pop()
            for yy, xx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if 0 <= yy < h and 0 <= xx < w and mask[yy, xx] and not lab[yy, xx]:
                    lab[yy, xx] = n
                    stack.append((yy, xx))
    return lab, n


def grid_sheet(file, keycol, prefix, rows=4, cols=4, size=320, ds=4, grow=3):
    """Grid sheet whose objects may cross cell borders: connected blobs (on a downsampled,
    slightly dilated mask so loose petals stick to their object) are assigned to the grid
    cell containing their centre of mass. Sprites keep their aspect ratio."""
    rgba = key(Image.open(os.path.join(SRC, file)), keycol)
    h, w = rgba.shape[:2]
    small = rgba[::ds, ::ds, 3] > 0.06
    m = small.copy()
    for _ in range(grow):
        m = m | np.roll(m, 1, 0) | np.roll(m, -1, 0) | np.roll(m, 1, 1) | np.roll(m, -1, 1)
    lab, n = _components(m)
    owner = np.zeros(n + 1, np.int32) - 1
    for i in range(1, n + 1):
        ys, xs = np.nonzero(lab == i)
        if len(ys) < 6:
            continue
        r = min(rows - 1, int(ys.mean() * ds * rows / h))
        c = min(cols - 1, int(xs.mean() * ds * cols / w))
        owner[i] = r * cols + c
    full = np.repeat(np.repeat(owner[lab], ds, 0), ds, 1)[:h, :w]
    names = []
    for k in range(rows * cols):
        sel = full == k
        part = rgba.copy()
        part[~sel] = 0
        m2 = part[..., 3] > 0.06
        ys, xs = np.where(m2)
        part = part[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
        name = f"{prefix}_{k:02d}"
        names.append(name)
        print(f"  {name:16s} {save(part, name, size, square=False)}")
    return names


def ground(file, name, period=1024, out=768, wash=0.12):
    """Vertically tileable ground strip: one period, wrap seam cross-faded, lightly washed
    towards cream so the path and level medallions stay readable."""
    im = np.asarray(Image.open(os.path.join(SRC, file)).convert("RGB"), np.float32)
    ov = 96
    r0 = (im.shape[0] - period - ov) // 2
    strip = im[r0:r0 + period + ov, :period].copy()
    t = np.linspace(0, 1, ov, dtype=np.float32)[:, None, None]
    strip[:ov] = strip[period:period + ov] * (1 - t) + strip[:ov] * t
    tile = strip[:period]
    tile = tile * (1 - wash) + np.array([255, 246, 232], np.float32) * wash
    Image.fromarray(np.clip(tile, 0, 255).astype(np.uint8)).resize((out, out), Image.LANCZOS) \
        .save(os.path.join(OUT, name + ".webp"), "WEBP", quality=82, method=6)
    print(f"  {name} {out}x{out}")


def logo(file):
    """App logo painted on a dark vignette: soft luminance key, colour un-mixing against the
    backdrop, then the in-app sprite and the adaptive launcher icon layers."""
    im = np.asarray(Image.open(os.path.join(SRC, file)).convert("RGB"), np.float32)
    bg = np.array([21, 20, 26], np.float32)
    d = np.abs(im - bg).max(-1)
    a = np.clip((d - 26) / 34, 0, 1)[..., None]
    rgb = np.where(a > 0.01, (im - (1 - a) * bg) / np.maximum(a, 0.01), 0)
    rgba = np.concatenate([np.clip(rgb, 0, 255), a * 255], -1).astype(np.uint8)
    img = Image.fromarray(rgba, "RGBA")
    img = img.crop(img.getbbox())
    w, h = img.size
    side = max(w, h)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.paste(img, ((side - w) // 2, (side - h) // 2))
    sq.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, "logo.webp"), "WEBP", quality=90, method=6)
    res = os.path.join(ROOT, "app", "src", "main", "res", "mipmap-xxxhdpi")
    os.makedirs(res, exist_ok=True)
    # Adaptive icon: 108dp canvas (432px), art kept inside ~74dp so masks only trim petals.
    fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    art = sq.resize((330, 330), Image.LANCZOS)
    fg.paste(art, (51, 51), art)
    fg.save(os.path.join(res, "ic_launcher_foreground.png"))
    mono = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    alpha = fg.split()[3]
    mono.paste(Image.new("RGBA", (432, 432), (255, 255, 255, 255)), (0, 0), alpha)
    mono.save(os.path.join(res, "ic_launcher_monochrome.png"))
    print("  logo + launcher icon")


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
    print("logo")
    logo("logo_dark.jpg")
    print("map decor")
    grid_sheet("decor_garden_a_green.jpg", GREEN, "decor_a")
    grid_sheet("decor_garden_b_green.jpg", GREEN, "decor_b")
    ground("ground_garden.jpg", "ground_garden")
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
