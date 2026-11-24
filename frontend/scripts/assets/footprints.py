#!/usr/bin/env python3
"""Measure isometric ground footprints of building-like campus sprites.

Plan: docs/261108-0128-campus_scene_layout_fix_plan.md, section 6-2.

A sprite's anchor is the bottom-center of its bounding box, but an isometric building's front
corner is usually off-center, so paths and plazas placed at the anchor miss the building. For
each sprite this script reads the lowest opaque pixel of every column and takes:
  front = the lowest point (mean x of the columns within 1px of it),
  left  = the leftmost opaque column's lowest pixel,
  right = the rightmost opaque column's lowest pixel.
The back corner is left + right - front. Sprites whose outline is not a box (oval stadium,
overhanging wings) use OVERRIDES, read off the image by eye.

Output: src/assets/campus/footprints.json, {id: {left, front, right}} in sprite pixels.
Usage: python3 -I scripts/assets/footprints.py
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSET_DIR = ROOT / "src/assets/campus"
OUT = ASSET_DIR / "footprints.json"

IDS = [
    "dorm-a", "dorm-b", "hall-extended", "hall-small", "lecture-2", "lecture-2-r", "lecture-3", "library", "library-r",
    "main-clocktower", "sports-hall", "stadium", "student-hall", "tent", "twin-tower", "fountain", "grand-hall",
]

# Outlines that the column scan misreads: dorm-b's left wing and student-hall's awning hang
# above the ground line, sports-hall's roof trim overhangs its left wall, and the stadium is an oval (its footprint is the enclosing diamond).
OVERRIDES: dict[str, dict[str, list[int]]] = {
    "dorm-b": {"left": [0, 88], "front": [39, 107], "right": [69, 92]},
    "student-hall": {"left": [0, 74], "front": [47, 97], "right": [83, 79]},
    "stadium": {"left": [0, 84], "front": [86, 127], "right": [149, 96]},
    # sports-hall's leftmost column is the roof trim (bottom y=25); the wall base starts at x=1.
    "sports-hall": {"left": [1, 53], "front": [66, 85], "right": [95, 65]},
    # grand-hall is an L seen from inside: left/right are the outer wing ends, front is the empty corner
    # where the 2:1 lines from them meet (below the sprite), inner is the wall base behind the steps.
    "grand-hall": {"left": [0, 159], "front": [125, 221], "right": [249, 159], "inner": [124, 139]},
}


def measure(file: str) -> dict[str, list[int]]:
    alpha = np.asarray(Image.open(ASSET_DIR / file).convert("RGBA"))[:, :, 3] > 128
    width = alpha.shape[1]
    bottom = np.array([np.nonzero(alpha[:, x])[0].max() if alpha[:, x].any() else -1 for x in range(width)])
    cols = np.nonzero(bottom >= 0)[0]
    x0, x1 = int(cols.min()), int(cols.max())
    near = np.nonzero(bottom >= bottom.max() - 1)[0]
    return {"left": [x0, int(bottom[x0])], "front": [int(round(near.mean())), int(bottom.max())], "right": [x1, int(bottom[x1])]}


def main() -> None:
    manifest = json.loads((ASSET_DIR / "manifest.json").read_text())
    out = {}
    for asset_id in IDS:
        out[asset_id] = OVERRIDES.get(asset_id) or measure(manifest[asset_id]["file"])
        print(asset_id, out[asset_id])
    OUT.write_text(json.dumps(out, indent=2) + "\n")
    print(f"wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
