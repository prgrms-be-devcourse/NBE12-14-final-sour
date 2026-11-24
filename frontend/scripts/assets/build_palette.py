#!/usr/bin/env python3
"""Build the shared campus palette (art/palette.png) from keyed raw images.

Each source contributes the same number of samples, so a large flat source (the old growth sheet,
grass) cannot crowd out small but important hues (cherry pink, water blue).

Usage: python3 -I scripts/assets/build_palette.py [colors]
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from process import border_color, find_source, key_out  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SAMPLES_PER_SOURCE = 40000
EXTRA_SOURCES = ["src/assets/images/campus_growth_5x2_final_1793921090067.jpg"]
# Colors every palette keeps. Median cut merges small near-white areas (clock faces, cupolas,
# clouds) into the dominant pinkish brick tones, which turned white details pink (deferred #1), and
# the small sun/lamp yellows into orange.
FIXED_COLORS = [(246, 243, 232), (214, 218, 222), (160, 166, 172), (34, 30, 36), (255, 212, 72), (255, 236, 150)]


def main(argv: list[str]) -> int:
    colors = int(argv[0]) if argv else 48
    cfg = json.loads((ROOT / "scripts/assets/manifest.json").read_text(encoding="utf-8"))
    rng = np.random.default_rng(0)
    pools: list[np.ndarray] = []
    seen: set[str] = set()
    for spec in cfg["assets"]:
        if spec["source"] in seen:
            continue
        seen.add(spec["source"])
        try:
            src = find_source(ROOT / cfg["rawDir"], spec["source"])
        except FileNotFoundError:
            continue
        rgb = np.asarray(Image.open(src).convert("RGB"), dtype=np.int32)
        if spec["kind"] == "texture":
            pixels = rgb.reshape(-1, 3)
        else:
            alpha = key_out(rgb, border_color(rgb, spec.get("keyEdge", "all")), cfg["keyTolerance"], cfg["fringeTolerance"])
            pixels = rgb[alpha]
        pools.append(pixels[rng.choice(len(pixels), min(SAMPLES_PER_SOURCE, len(pixels)), replace=False)])
    for extra in EXTRA_SOURCES:
        pixels = np.asarray(Image.open(ROOT / extra).convert("RGB"), dtype=np.int32).reshape(-1, 3)
        pools.append(pixels[rng.choice(len(pixels), SAMPLES_PER_SOURCE, replace=False)])
    samples = np.concatenate(pools).astype(np.uint8)
    img = Image.fromarray(samples.reshape(1, -1, 3), "RGB")
    free = colors - len(FIXED_COLORS)
    full = img.quantize(free, method=Image.Quantize.MEDIANCUT).getpalette()
    if full is None:
        raise RuntimeError("quantize() returned an image without a palette")
    pal = full[: free * 3] + [v for c in FIXED_COLORS for v in c]
    out = Image.fromarray(np.array(pal, dtype=np.uint8).reshape(1, colors, 3), "RGB")
    out.save(ROOT / cfg["palette"])
    print(f"wrote {cfg['palette']}: {colors} colors from {len(pools)} sources")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
