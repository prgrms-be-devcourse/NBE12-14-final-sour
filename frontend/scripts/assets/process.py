#!/usr/bin/env python3
"""Turn raw Nano Banana images into 1x pixel-art campus assets.

Plan: docs/261107-2236-campus_asset_composition_plan.md, section 5-4.

Steps per asset:
  1. Key out the background: the most common border color, within keyTolerance (RGB distance).
  2. Remove fringe: opaque pixels touching transparency whose color is within fringeTolerance
     of the background and lean toward its hue.
  3. Sheets: take the requested grid cell, or an explicit "box" crop for irregular sheets. Warn when the object touches the cell edge.
  4. Quantize to the shared palette (no dithering), then downscale to targetWidth by picking
     the most common color in each block. Blocks that are mostly transparent stay transparent.
  5. Record size and bottom-center anchor in <outDir>/manifest.json.
  6. Textures: no keying; downscale the whole image and report edge seam differences.
  7. Buildings with "lights": true also get <id>.lights.png: the glass-colored (blue/teal) window
     pixels of the final sprite, recolored warm yellow, for the night window layer (plan 4-2).
     "lightsMirrorOf": "<id>" instead mirrors that asset's finished mask, for a sprite redrawn as its
     mirror image whose relit windows lost the glass color (library-r, docs/261109-1950-campus_lead_feedback_plan.md U5).
     Build the source asset first; the sizes must match.

Usage: python3 -I scripts/assets/process.py [asset-id ...]
       python3 -I scripts/assets/process.py --raw [asset-id ...]

--raw (experiment A, issue #22, section 4-1): steps 1-3 only, at the
source resolution. No palette, no downscale: the browser does the resizing. Sprites go to
public/campus-raw/<category>/<id>.png (git-ignored) with lights masks and a manifest.json; textures are
skipped. Fails when a raw sprite's aspect differs from its 1x sprite by more than the 1x rounding (0.5 px).
The app no longer reads these files; they serve offline comparisons (e.g. as the reference in benchmarks).

--hd (method B, the default display since docs/261108-1014-campus_hd_default_plan.md): the same cut,
resized (LANCZOS, premultiplied alpha) to exactly HD_SCALES x the 1x sprite size, into
src/assets/campus-hd/<scale>/<category>/ (committed), and records "hdScales" on each sprite in
src/assets/campus/manifest.json. Run it after the 1x step whenever a 1x sprite changes size
(tests/campusAssets.test.ts checks the sizes). Light masks mark every output pixel whose window-glass coverage is at least
HD_LIGHT_COVERAGE, so a window stays a solid block instead of fading when the browser shrinks it.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "scripts/assets/manifest.json"


def load_palette(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.int32).reshape(-1, 3)


def find_source(raw_dir: Path, suffix: str) -> Path:
    matches = sorted(raw_dir.glob(f"*-{suffix}.png"))
    if not matches:
        raise FileNotFoundError(f"No raw image matching *-{suffix}.png in {raw_dir}")
    return matches[-1]  # newest by timestamp prefix


def border_color(rgb: np.ndarray, edge: str = "all") -> np.ndarray:
    """Most common border color. edge="top" for strips whose other edges are mostly subject (horizon)."""
    border = rgb[0] if edge == "top" else np.concatenate([rgb[0], rgb[-1], rgb[:, 0], rgb[:, -1]])
    colors, counts = np.unique(border, axis=0, return_counts=True)
    return colors[counts.argmax()]


def key_out(rgb: np.ndarray, bg: np.ndarray, tol: float, fringe_tol: float) -> np.ndarray:
    dist = np.sqrt(((rgb - bg) ** 2).sum(axis=2))
    alpha = dist > tol
    # Fringe: opaque pixels next to transparency that are still close to the key color.
    transparent = ~alpha
    near = np.zeros_like(transparent)
    near[1:] |= transparent[:-1]
    near[:-1] |= transparent[1:]
    near[:, 1:] |= transparent[:, :-1]
    near[:, :-1] |= transparent[:, 1:]
    fringe = alpha & near & (dist <= fringe_tol)
    return alpha & ~fringe


def quantize(rgb: np.ndarray, palette: np.ndarray) -> np.ndarray:
    """Return palette indices for every pixel (nearest color, no dithering)."""
    flat = rgb.reshape(-1, 3)
    out = np.empty(len(flat), dtype=np.int32)
    for start in range(0, len(flat), 262144):
        chunk = flat[start:start + 262144]
        d = ((chunk[:, None, :] - palette[None, :, :]) ** 2).sum(axis=2)
        out[start:start + 262144] = d.argmin(axis=1)
    return out.reshape(rgb.shape[:2])


def block_mode(idx: np.ndarray, alpha: np.ndarray, out_w: int, out_h: int, n_colors: int):
    h, w = idx.shape
    xs = np.linspace(0, w, out_w + 1).round().astype(int)
    ys = np.linspace(0, h, out_h + 1).round().astype(int)
    res_idx = np.zeros((out_h, out_w), dtype=np.int32)
    res_a = np.zeros((out_h, out_w), dtype=bool)
    for j in range(out_h):
        for i in range(out_w):
            a = alpha[ys[j]:ys[j + 1], xs[i]:xs[i + 1]]
            if a.size == 0 or a.mean() <= 0.5:
                continue
            block = idx[ys[j]:ys[j + 1], xs[i]:xs[i + 1]][a]
            res_idx[j, i] = np.bincount(block, minlength=n_colors).argmax()
            res_a[j, i] = True
    return res_idx, res_a


def to_image(idx: np.ndarray, alpha: np.ndarray, palette: np.ndarray) -> Image.Image:
    rgba = np.zeros((*idx.shape, 4), dtype=np.uint8)
    rgba[..., :3] = palette[idx]
    rgba[..., 3] = np.where(alpha, 255, 0)
    return Image.fromarray(rgba, "RGBA")


def cut_sprite(spec: dict, rgb: np.ndarray, cfg: dict) -> tuple[np.ndarray, np.ndarray, np.ndarray, list[str]]:
    """Steps 1-3: key out the background, take the sheet cell or box, and crop to the object.

    Returns the cropped RGB, its alpha, the key color and notes. Shared by the 1x sprites and --raw.
    """
    notes: list[str] = []
    if spec["kind"] == "sheet":
        h, w = rgb.shape[:2]
        if "box" in spec:
            # Irregular sheets (the model ignored the grid): an explicit crop as fractions [x0, y0, x1, y1].
            fx0, fy0, fx1, fy1 = spec["box"]
            x0, y0, x1, y1 = round(w * fx0), round(h * fy0), round(w * fx1), round(h * fy1)
        else:
            rows, cols = spec["grid"]
            r, c = spec["cell"]
            y0, y1 = h * r // rows, h * (r + 1) // rows
            x0, x1 = w * c // cols, w * (c + 1) // cols
        bg = border_color(rgb, spec.get("keyEdge", "all"))
        rgb = rgb[y0:y1, x0:x1]
    else:
        bg = border_color(rgb, spec.get("keyEdge", "all"))
    alpha = key_out(rgb, bg, cfg["keyTolerance"], cfg["fringeTolerance"])
    ys, xs = np.nonzero(alpha)
    if len(xs) == 0:
        raise ValueError(f"{spec['id']}: nothing left after keying")
    if spec["kind"] == "sheet" and (xs.min() == 0 or ys.min() == 0 or xs.max() == rgb.shape[1] - 1 or ys.max() == rgb.shape[0] - 1):
        notes.append("object touches its grid cell edge; it may be cut off or include a neighbor")
    rgb = rgb[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    alpha = alpha[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    return rgb, alpha, bg, notes


def process_sprite(spec: dict, rgb: np.ndarray, cfg: dict, palette: np.ndarray) -> tuple[Image.Image, dict]:
    rgb, alpha, bg, notes = cut_sprite(spec, rgb, cfg)
    # Thin objects (shovel, sapling) are sized by height; everything else by width.
    if "targetHeight" in spec:
        out_h = spec["targetHeight"]
        out_w = max(1, round(alpha.shape[1] * out_h / alpha.shape[0]))
    else:
        out_w = spec["targetWidth"]
        out_h = max(1, round(alpha.shape[0] * out_w / alpha.shape[1]))
    idx = quantize(rgb, palette)
    small_idx, small_a = block_mode(idx, alpha, out_w, out_h, len(palette))
    img = to_image(small_idx, small_a, palette)
    # Anchor: horizontal center of the bounding box, lowest opaque row. The renderer places this
    # point on the scene coordinate and sorts depth by it. The lowest row alone is not used for x
    # because isometric footprints put the lowest pixel at a corner, not the center.
    info = {
        "background": [int(v) for v in bg],
        "anchor": [out_w // 2, out_h - 1],
        "notes": notes,
    }
    return img, info


LIGHT_COLOR = (255, 214, 120)


def window_mask(img: Image.Image, below: float = 0.0, blue: int = 25) -> tuple[Image.Image, int]:
    """Window glass pixels (blue/teal, clearly bluer than red) of a processed sprite as a warm light mask.

    below: ignore the top fraction of the sprite, for roofs in glass-like colors (the library's copper dome).
    blue: how much bluer than red glass must be ("lightsBlue"); grand-hall raises it to 35 because its
    slate roofs sit at b - r 26-33 while its windows sit at 40-90 (docs/261109-1950-campus_lead_feedback_plan.md 9-15).
    """
    px = np.asarray(img, dtype=np.int32)
    r, g, b, a = px[..., 0], px[..., 1], px[..., 2], px[..., 3]
    glass = (a > 0) & (b > r + blue) & (g > r + 5)
    glass[: int(img.height * below)] = False
    out = np.zeros(px.shape, dtype=np.uint8)
    out[glass] = (*LIGHT_COLOR, 255)
    return Image.fromarray(out, "RGBA"), int(glass.sum())


def mirrored_mask(path: Path, size: tuple[int, int]) -> tuple[Image.Image, int]:
    """The finished light mask at `path`, mirrored left-right (lightsMirrorOf)."""
    mask = ImageOps.mirror(Image.open(path).convert("RGBA"))
    if mask.size != size:
        raise ValueError(f"{path.name} is {mask.size}, the sprite is {size}")
    return mask, int((np.asarray(mask)[..., 3] > 0).sum())


def process_texture(spec: dict, rgb: np.ndarray, palette: np.ndarray) -> tuple[Image.Image, dict]:
    size = spec["targetWidth"]
    idx = quantize(rgb, palette)
    small_idx, small_a = block_mode(idx, np.ones(idx.shape, dtype=bool), size, size, len(palette))
    img = to_image(small_idx, small_a, palette)
    px = np.asarray(img.convert("RGB"), dtype=np.int32)
    seam_x = float(np.abs(px[:, 0] - px[:, -1]).mean())
    seam_y = float(np.abs(px[0] - px[-1]).mean())
    inner = float(np.abs(px[:, 1:] - px[:, :-1]).mean())
    return img, {"seam": {"leftRight": round(seam_x, 2), "topBottom": round(seam_y, 2), "innerNeighbor": round(inner, 2)}, "notes": []}


def main(argv: list[str]) -> int:
    cfg = json.loads(MANIFEST.read_text(encoding="utf-8"))
    palette = load_palette(ROOT / cfg["palette"])
    raw_dir = ROOT / cfg["rawDir"]
    out_dir = ROOT / cfg["outDir"]
    wanted = set(argv)
    out_manifest_path = out_dir / "manifest.json"
    out_manifest = json.loads(out_manifest_path.read_text(encoding="utf-8")) if out_manifest_path.exists() else {}
    failed = False
    for spec in cfg["assets"]:
        if wanted and spec["id"] not in wanted:
            continue
        try:
            src = find_source(raw_dir, spec["source"])
        except FileNotFoundError as err:
            print(f"SKIP {spec['id']}: {err}")
            continue
        rgb = np.asarray(Image.open(src).convert("RGB"), dtype=np.int32)
        if spec["kind"] == "texture":
            img, info = process_texture(spec, rgb, palette)
        else:
            img, info = process_sprite(spec, rgb, cfg, palette)
        target = out_dir / spec["category"] / f"{spec['id']}.png"
        target.parent.mkdir(parents=True, exist_ok=True)
        img.save(target, optimize=True)
        if spec.get("lights"):
            if "lightsMirrorOf" in spec:
                mask, count = mirrored_mask(out_dir / spec["category"] / f"{spec['lightsMirrorOf']}.lights.png", img.size)
            else:
                mask, count = window_mask(img, spec.get("lightsBelow", 0.0), spec.get("lightsBlue", 25))
            mask.save(target.with_suffix(".lights.png"), optimize=True)
            info["lights"] = f"{spec['category']}/{spec['id']}.lights.png"
            info["lightPixels"] = count
            if count < spec.get("minLightPixels", 6):
                info["notes"].append(f"only {count} window pixels found for the night light mask")
        # Keep the --hd record: if this run changed the 1x size, tests/campusAssets.test.ts fails on
        # the stale HD files until --hd runs again (docs/261108-1014-campus_hd_default_plan.md 4-1).
        hd = {"hdScales": out_manifest[spec["id"]]["hdScales"]} if "hdScales" in out_manifest.get(spec["id"], {}) else {}
        out_manifest[spec["id"]] = {
            "file": f"{spec['category']}/{spec['id']}.png",
            "kind": "texture" if spec["kind"] == "texture" else "sprite",
            "width": img.width,
            "height": img.height,
            "source": src.name,
            **{k: v for k, v in info.items() if k != "notes"},
            **hd,
        }
        status = "WARN" if info["notes"] else "OK"
        failed |= bool(info["notes"])
        print(f"{status} {spec['id']}: {img.width}x{img.height} from {src.name} {info.get('seam', '')} {'; '.join(info['notes'])}")
    write_manifest(out_dir, out_manifest)
    return 1 if failed else 0


def write_manifest(out_dir: Path, out_manifest: dict) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    body = json.dumps(dict(sorted(out_manifest.items())), indent=2, ensure_ascii=False)
    (out_dir / "manifest.json").write_text(body + "\n", encoding="utf-8")
    # TypeScript copy for the app (the project does not enable resolveJsonModule).
    (out_dir / "manifest.ts").write_text(
        "// Generated by scripts/assets/process.py from manifest.json. Do not edit by hand.\n"
        f"export const CAMPUS_ASSET_MANIFEST = {body} as const;\n",
        encoding="utf-8",
    )


RAW_OUT_DIR = ROOT / "public/campus-raw"


def aspect_mismatch(raw_w: int, raw_h: int, w: int, h: int) -> float:
    """Rounding error, in 1x px, of the 1x sprite's derived side (0 = same aspect).

    The 1x step fixes one side (targetWidth or targetHeight) and rounds the other, so a sprite made
    from the same crop is off by at most 0.5 px on one of the two sides.
    """
    return min(abs(raw_w * h / raw_h - w), abs(raw_h * w / raw_w - h))


def cut_rgba(spec: dict, src: Path, cfg: dict) -> tuple[Image.Image, list[str]]:
    """The keyed, cropped sprite at source resolution (transparent pixels keep the key color's RGB)."""
    rgb, alpha, _, notes = cut_sprite(spec, np.asarray(Image.open(src).convert("RGB"), dtype=np.int32), cfg)
    rgba = np.zeros((*alpha.shape, 4), dtype=np.uint8)
    rgba[..., :3] = rgb
    rgba[..., 3] = np.where(alpha, 255, 0)
    return Image.fromarray(rgba, "RGBA"), notes


def main_raw(argv: list[str]) -> int:
    cfg = json.loads(MANIFEST.read_text(encoding="utf-8"))
    raw_dir = ROOT / cfg["rawDir"]
    small = json.loads((ROOT / cfg["outDir"] / "manifest.json").read_text(encoding="utf-8"))
    wanted = set(argv)
    manifest_path = RAW_OUT_DIR / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    failed = False
    for spec in cfg["assets"]:
        if spec["kind"] == "texture" or (wanted and spec["id"] not in wanted):
            continue
        if spec["id"] not in small:
            print(f"SKIP {spec['id']}: no 1x sprite in {cfg['outDir']}/manifest.json")
            continue
        try:
            src = find_source(raw_dir, spec["source"])
        except FileNotFoundError as err:
            print(f"SKIP {spec['id']}: {err}")
            continue
        img, notes = cut_rgba(spec, src, cfg)
        rel = f"{spec['category']}/{spec['id']}.png"
        target = RAW_OUT_DIR / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        img.save(target, optimize=True)
        entry = {"file": rel, "width": img.width, "height": img.height}
        if spec.get("lights"):
            mask, count = window_mask(img, spec.get("lightsBelow", 0.0), spec.get("lightsBlue", 25))
            mask.save(target.with_suffix(".lights.png"), optimize=True)
            entry["lights"] = f"{spec['category']}/{spec['id']}.lights.png"
            entry["lightPixels"] = count
        error = aspect_mismatch(img.width, img.height, small[spec["id"]]["width"], small[spec["id"]]["height"])
        if error > 0.51:
            notes.append(f"aspect differs from the 1x sprite by {error:.2f} px; the 1x sprite is stale, rerun process.py {spec['id']} first")
            failed = True
        manifest[spec["id"]] = entry
        print(f"{'WARN' if notes else 'OK'} {spec['id']}: {img.width}x{img.height} ({target.stat().st_size // 1024} KiB) {'; '.join(notes)}")
    RAW_OUT_DIR.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(dict(sorted(manifest.items())), indent=2) + "\n", encoding="utf-8")
    return 1 if failed else 0


HD_OUT_DIR = ROOT / "src/assets/campus-hd"
HD_SCALES = (3, 6)
# Share of an output pixel that must be window glass for it to light up (plan 4-2). Tuned by eye
# against the Lv.8 night comparison; lower lights more of each window frame.
HD_LIGHT_COVERAGE = 0.25


def hd_light_mask(raw: Image.Image, size: tuple[int, int], below: float, blue: int = 25) -> tuple[Image.Image, int]:
    """Window lights at `size`: solid where the source glass covers at least HD_LIGHT_COVERAGE."""
    glass, _ = window_mask(raw, below, blue)
    coverage = np.asarray(glass.getchannel("A").resize(size, Image.BOX), dtype=np.float32) / 255
    lit = coverage >= HD_LIGHT_COVERAGE
    out = np.zeros((size[1], size[0], 4), dtype=np.uint8)
    out[lit] = (*LIGHT_COLOR, 255)
    return Image.fromarray(out, "RGBA"), int(lit.sum())


def main_hd(argv: list[str]) -> int:
    cfg = json.loads(MANIFEST.read_text(encoding="utf-8"))
    raw_dir = ROOT / cfg["rawDir"]
    small = json.loads((ROOT / cfg["outDir"] / "manifest.json").read_text(encoding="utf-8"))
    wanted = set(argv)
    failed = False
    for spec in cfg["assets"]:
        if spec["kind"] == "texture" or (wanted and spec["id"] not in wanted):
            continue
        if spec["id"] not in small:
            print(f"SKIP {spec['id']}: no 1x sprite in {cfg['outDir']}/manifest.json")
            continue
        try:
            src = find_source(raw_dir, spec["source"])
        except FileNotFoundError as err:
            print(f"SKIP {spec['id']}: {err}")
            continue
        raw, notes = cut_rgba(spec, src, cfg)
        w1, h1 = small[spec["id"]]["width"], small[spec["id"]]["height"]
        if aspect_mismatch(raw.width, raw.height, w1, h1) > 0.51:
            notes.append(f"aspect differs from the 1x sprite; the 1x sprite is stale, rerun process.py {spec['id']} first")
            failed = True
        rel = f"{spec['category']}/{spec['id']}.png"
        sizes = []
        for scale in HD_SCALES:
            size = (w1 * scale, h1 * scale)
            if size[0] > raw.width or size[1] > raw.height:
                notes.append(f"source {raw.width}x{raw.height} is smaller than {scale}x ({size[0]}x{size[1]}); upscaled")
            img = raw.resize(size, Image.LANCZOS, reducing_gap=3.0)
            target = HD_OUT_DIR / str(scale) / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            img.save(target, optimize=True)
            if img.size != size:
                notes.append(f"{scale}x output is {img.size}, expected {size}")
                failed = True
            if spec.get("lights"):
                if "lightsMirrorOf" in spec:
                    mask, count = mirrored_mask(HD_OUT_DIR / str(scale) / spec["category"] / f"{spec['lightsMirrorOf']}.lights.png", size)
                else:
                    mask, count = hd_light_mask(raw, size, spec.get("lightsBelow", 0.0), spec.get("lightsBlue", 25))
                if mask.size != img.size:
                    notes.append(f"{scale}x light mask size {mask.size} differs from the sprite {img.size}")
                    failed = True
                mask.save(target.with_suffix(".lights.png"), optimize=True)
                sizes.append(f"lights {count} px")
            sizes.append(f"{scale}x {size[0]}x{size[1]} ({target.stat().st_size // 1024} KiB)")
        small[spec["id"]]["hdScales"] = list(HD_SCALES)
        print(f"{'WARN' if notes else 'OK'} {spec['id']}: {', '.join(sizes)} {'; '.join(notes)}")
    write_manifest(ROOT / cfg["outDir"], small)
    return 1 if failed else 0


if __name__ == "__main__":
    args = sys.argv[1:]
    rest = [a for a in args if a not in ("--raw", "--hd")]
    sys.exit(main_raw(rest) if "--raw" in args else main_hd(rest) if "--hd" in args else main(args))
