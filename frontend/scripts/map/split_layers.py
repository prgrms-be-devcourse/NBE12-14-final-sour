"""Split the national map JPG into layers (T7, docs/261110-1005-mockup_remaining_tasks_plan.md §10-1, decision Q9 option C).

    python3 scripts/map/split_layers.py            # writes src/assets/images/map/* and prints the measurements
    python3 scripts/map/split_layers.py --check    # writes nothing; fails if the committed layers differ from a
                                                   # fresh split or the recomposition drifts from the original

Land and islands are cut from the original so school and cluster % coordinates stay valid. The sea is drawn by
the app (src/components/MapArt.tsx), so it is removed here, with the sea sparkles. Clouds over the sea become
separate sprites; the image edge cuts all three, so each is completed by mirroring its visible part across that
edge, and its shadow becomes a translucent overlay so it darkens the land it drifts over. The northern cloud bank
covers land, and there is no land art under it, so it stays one static layer (decision Q9).
Deterministic: numpy and Pillow only, no randomness, median-cut quantization without dithering.
Python rather than TypeScript because the project has no image decoder in node; numpy keeps the masks short.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'src/assets/images/korea_pixel_map_1793879803724.jpg'
OUT = ROOT / 'src/assets/images/map'

# Measured from the source: open sea is (97, 197, 250) ± 3 everywhere; rivers are (144, 216, 240) and lighter,
# cloud shadows (86, 160, 221). 30 keeps both out of the sea.
SEA_RGB = np.array([97, 197, 250])
SEA_DISTANCE = 30
SHADOW_RGB = np.array([86, 160, 221])
SHADOW_DISTANCE = 22
SHADOW_ALPHA = 0.3
# Per-layer palette sizes (median cut, no dither). Land at 255 colours: mean channel error 5.7 and 0.7% of land
# pixels off by more than 24, for 323KB (96 colours: 7.9, 2.9%, 252KB; 32: 11.9, 10.7%, 183KB; measured 2026-11-10).
COLORS = {'land': 255, 'islands': 64, 'cloud': 32, 'cloud-bank-north': 64}
# Free clouds in source order (top edge first): the right one at y≈500, the left one, the lower right one.
CLOUD_NAMES = ['clouds-right', 'clouds-left', 'clouds-bottom-right']
# --check limits: what this script produces now (land 5.69 / 0.7%), with a little room for Pillow versions.
LIMITS = {'landMeanAbs': 6.5, 'landOver24': 0.012, 'allMeanAbs': 5.0, 'bytesTotal': 720_390}


def neighbors8(mask: np.ndarray) -> list[np.ndarray]:
    padded = np.pad(mask, 1)
    h, w = mask.shape
    return [padded[1 + dy:1 + dy + h, 1 + dx:1 + dx + w] for dy in (-1, 0, 1) for dx in (-1, 0, 1)]


def erode(mask: np.ndarray) -> np.ndarray:
    return np.logical_and.reduce(neighbors8(mask))


def dilate(mask: np.ndarray) -> np.ndarray:
    return np.logical_or.reduce(neighbors8(mask))


def label(mask: np.ndarray) -> tuple[np.ndarray, list[int]]:
    """8-connected components in scan order (stable labels for a fixed input)."""
    h, w = mask.shape
    labels = np.zeros(mask.shape, np.int32)
    sizes: list[int] = []
    for y0, x0 in zip(*np.nonzero(mask)):
        if labels[y0, x0]:
            continue
        n = len(sizes) + 1
        labels[y0, x0] = n
        queue, size = deque([(y0, x0)]), 0
        while queue:
            y, x = queue.popleft()
            size += 1
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    yy, xx = y + dy, x + dx
                    if 0 <= yy < h and 0 <= xx < w and mask[yy, xx] and not labels[yy, xx]:
                        labels[yy, xx] = n
                        queue.append((yy, xx))
        sizes.append(size)
    return labels, sizes


def touching(labels: np.ndarray, seed: np.ndarray) -> np.ndarray:
    """Components of `labels` that contain any pixel of `seed`."""
    ids = np.unique(labels[seed & (labels > 0)])
    return np.isin(labels, ids) & (labels > 0)


def segment(rgb: np.ndarray) -> dict:
    h, w, _ = rgb.shape
    r, g, b = (rgb[..., i] for i in range(3))
    sea_like = np.sqrt(((rgb - SEA_RGB) ** 2).sum(-1)) < SEA_DISTANCE
    border = np.zeros((h, w), bool)
    border[0, :] = border[-1, :] = border[:, 0] = border[:, -1] = True
    sea_labels, _ = label(sea_like)
    sea = touching(sea_labels, border)  # rivers and lakes are enclosed, so they stay with the land
    solid = ~sea
    solid = dilate(erode(solid)) | (solid & erode(dilate(solid)) & ~sea_like)  # drop 1px JPG specks on the sea

    labels, sizes = label(solid)
    main_id = int(np.argmax(sizes)) + 1
    main = labels == main_id
    green = (g > r + 20) & (g > b + 20)

    # Cloud colours: near-white with a cold cast, and the blue shading and shadows under them.
    light = (np.minimum(np.minimum(r, g), b) >= 165) & (b >= r) & (b >= g - 6)
    shade = (r >= 60) & (r <= 150) & (g >= 130) & (g <= 205) & (b >= 190) & (b >= g + 15)
    cloudy = (light | shade) & solid
    top = np.zeros((h, w), bool)
    top[0, :] = True
    bank_labels, _ = label(cloudy & main)
    bank = touching(bank_labels, top)
    # A river shares the cloud colours and touches the bank (the Imjin at x≈140-240, y≈260-420). It is a few pixels
    # wide; the bank is not. Keep only what survives an opening of radius 4, plus the bank's own outline next to it.
    core = bank
    for _ in range(4):
        core = erode(core)
    for _ in range(8):
        core = dilate(core)
    bank = bank & core
    # Fill the bank's small interior gaps (outline pixels inside the cloud art), but never across land.
    bank = bank | (erode(dilate(bank)) & main & ~green)

    clouds, islands, dropped = [], np.zeros((h, w), bool), 0
    jeju, jeju_size = np.zeros((h, w), bool), 0
    for n, size in enumerate(sizes, start=1):
        if n == main_id:
            continue
        part = labels == n
        if (part & green).sum() >= 3:
            islands |= part
            if size > jeju_size:  # the largest island is 제주도
                jeju, jeju_size = part, size
        elif size >= 2000 and (part & cloudy).sum() > 0.5 * size:
            clouds.append(part)
        else:
            dropped += size  # a sea sparkle or JPG noise: the app's sea draws its own sparkles
    clouds.sort(key=lambda m: (int(np.nonzero(m)[0].min()), int(np.nonzero(m)[1].min())))
    return {'land': main & ~bank, 'bank': bank, 'islands': islands, 'jeju': jeju, 'clouds': clouds, 'dropped': dropped}


def island_outline(mask: np.ndarray) -> dict:
    """Box and per-row extent of one island in source pixels, for the 제주 camera and the school-on-land check
    (docs/261110-2005-visit_ui_jeju_map_plan.md §3). Row i spans y0 + i; [x0, x1] are inclusive."""
    ys, xs = np.nonzero(mask)
    y0, y1 = int(ys.min()), int(ys.max())
    rows = []
    for y in range(y0, y1 + 1):
        row = np.nonzero(mask[y])[0]
        rows.append([int(row.min()), int(row.max())])
    return {'x0': int(xs.min()), 'y0': y0, 'x1': int(xs.max()), 'y1': y1, 'rows': rows}


def rgba(rgb: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """Opaque where `mask`, fully transparent (and black, so it compresses) elsewhere."""
    out = np.zeros((*mask.shape, 4), np.uint8)
    out[mask, :3] = rgb[mask]
    out[mask, 3] = 255
    return out


def quantize(data: np.ndarray, colors: int) -> np.ndarray:
    """Median-cut palette built from the opaque pixels only; fully transparent pixels stay transparent."""
    opaque = data[..., 3] > 0
    if not opaque.any():
        return data
    palette = Image.fromarray(data[opaque][None, :, :3]).quantize(colors=colors, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    mapped = np.asarray(Image.fromarray(data[..., :3]).quantize(palette=palette, dither=Image.Dither.NONE).convert('RGB'))
    out = np.zeros_like(data)
    out[opaque, :3] = mapped[opaque]
    out[opaque, 3] = 255
    return out


def translucent_shadow(sprite: np.ndarray) -> np.ndarray:
    """Shadow pixels become a dark overlay that, over the plain sea, gives back the original shadow colour."""
    out = sprite.copy()
    rgb = sprite[..., :3].astype(float)
    shadow = (sprite[..., 3] > 0) & (np.sqrt(((rgb - SHADOW_RGB) ** 2).sum(-1)) < SHADOW_DISTANCE)
    overlay = SEA_RGB + (rgb[shadow] - SEA_RGB) / SHADOW_ALPHA
    out[shadow, :3] = np.clip(np.rint(overlay), 0, 255).astype(np.uint8)
    out[shadow, 3] = round(255 * SHADOW_ALPHA)
    return out


def to_palette(data: np.ndarray) -> Image.Image:
    """Exact indexed PNG of a quantized RGBA layer: index 0 is transparent, the rest are its colours."""
    visible = data[..., 3] > 0
    colors, inverse = np.unique(data[visible], axis=0, return_inverse=True)
    assert len(colors) <= 255, f'{len(colors)} colours do not fit a palette'
    index = np.zeros(visible.shape, np.uint8)
    index[visible] = inverse.reshape(-1) + 1
    out = Image.fromarray(index, 'P')
    out.putpalette([0, 0, 0] + colors[:, :3].reshape(-1).tolist())
    out.info['transparency'] = bytes([0] + colors[:, 3].tolist())
    return out


def mirror_complete(sprite: np.ndarray, side: str) -> np.ndarray:
    """Cloud cut by the image edge on `side`: append its mirror image there so the cut edge becomes a round end."""
    flipped = sprite[:, ::-1]
    return np.concatenate([flipped, sprite], axis=1) if side == 'left' else np.concatenate([sprite, flipped], axis=1)


def split() -> tuple[np.ndarray, dict[str, np.ndarray], dict]:
    rgb = np.asarray(Image.open(SOURCE).convert('RGB'))
    h, w, _ = rgb.shape
    parts = segment(rgb.astype(int))
    layers = {
        'land': quantize(rgba(rgb, parts['land']), COLORS['land']),
        'islands': quantize(rgba(rgb, parts['islands']), COLORS['islands']),
        'cloud-bank-north': quantize(rgba(rgb, parts['bank']), COLORS['cloud-bank-north']),
    }
    assert len(parts['clouds']) == len(CLOUD_NAMES), f"expected {len(CLOUD_NAMES)} free clouds, found {len(parts['clouds'])}"
    clouds = []
    for name, part in zip(CLOUD_NAMES, parts['clouds']):
        ys, xs = np.nonzero(part)
        y0, y1, x0, x1 = int(ys.min()), int(ys.max()) + 1, int(xs.min()), int(xs.max()) + 1
        side = 'left' if x0 == 0 else 'right' if x1 == w else None
        sprite = translucent_shadow(quantize(rgba(rgb, part), COLORS['cloud'])[y0:y1, x0:x1])
        if side:
            sprite = mirror_complete(sprite, side)
        # Box of the completed sprite in source pixels: a left-cut cloud now starts left of the image.
        left = x0 - (x1 - x0) if side == 'left' else x0
        layers[name] = sprite
        clouds.append({'id': name, 'x': left, 'y': y0, 'width': int(sprite.shape[1]), 'height': y1 - y0, 'mirrored': side})
    meta = {
        'source': SOURCE.name, 'width': w, 'height': h, 'sea': [int(c) for c in SEA_RGB], 'clouds': clouds,
        'jeju': island_outline(parts['jeju']), 'generatedBy': 'scripts/map/split_layers.py',
    }
    return rgb, layers, {**meta, 'droppedPixels': parts['dropped']}


def recompose(rgb: np.ndarray, layers: dict[str, np.ndarray], meta: dict) -> np.ndarray:
    """The app's stacking at rest (clouds at their source spots, plain sea): sea, land, islands, clouds, bank."""
    h, w, _ = rgb.shape
    canvas = Image.new('RGBA', (w, h), tuple(meta['sea']) + (255,))
    for name in ('land', 'islands'):
        canvas.alpha_composite(Image.fromarray(layers[name]))
    for cloud in meta['clouds']:
        sprite = Image.fromarray(layers[cloud['id']])
        cut = max(0, -cloud['x'])
        canvas.alpha_composite(sprite.crop((cut, 0, cloud['width'], cloud['height'])), (max(0, cloud['x']), cloud['y']))
    canvas.alpha_composite(Image.fromarray(layers['cloud-bank-north']))
    return np.asarray(canvas.convert('RGB'))


def measure(rgb: np.ndarray, layers: dict[str, np.ndarray], meta: dict) -> dict:
    diff = np.abs(recompose(rgb, layers, meta).astype(int) - rgb.astype(int)).max(-1)
    land = layers['land'][..., 3] > 0
    return {
        'landPixels': int(land.sum()), 'landMeanAbs': round(float(diff[land].mean()), 2), 'landOver24': round(float((diff[land] > 24).mean()), 4),
        'allMeanAbs': round(float(diff.mean()), 2), 'allOver24': round(float((diff > 24).mean()), 4), 'droppedPixels': meta['droppedPixels'],
    }


def dumps(data: dict) -> str:
    """Indented JSON with short number lists ([x0, x1] rows, the sea colour) kept on one line."""
    text = json.dumps(data, indent=2)
    return re.sub(r'\[\s+(-?\d+(?:,\s+-?\d+)*)\s+\]', lambda m: '[' + re.sub(r',\s+', ', ', m.group(1)) + ']', text) + '\n'


def read_layer(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path).convert('RGBA'))


def main() -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or '').split('\n')[0])
    parser.add_argument('--check', action='store_true', help='write nothing; compare with the committed layers')
    check = parser.parse_args().check
    rgb, layers, meta = split()
    report = measure(rgb, layers, meta)
    public = {k: v for k, v in meta.items() if k != 'droppedPixels'}
    problems = []
    if check:
        if json.loads((OUT / 'layers.json').read_text()) != public:
            problems.append('layers.json differs from a fresh split')
        for name, data in layers.items():
            path = OUT / f'{name}.png'
            if not path.exists() or not np.array_equal(read_layer(path), np.asarray(to_palette(data).convert('RGBA'))):
                problems.append(f'{path.name} differs from a fresh split')
    else:
        OUT.mkdir(parents=True, exist_ok=True)
        for name, data in layers.items():
            to_palette(data).save(OUT / f'{name}.png', optimize=True)
        (OUT / 'layers.json').write_text(dumps(public))
    report['bytes'] = {p.name: p.stat().st_size for p in sorted(OUT.glob('*.png'))}
    report['bytesTotal'] = sum(report['bytes'].values())
    for key, limit in LIMITS.items():
        if report[key] > limit:
            problems.append(f'{key} {report[key]} > {limit}')
    report['problems'] = problems
    print(json.dumps(report, indent=2))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
