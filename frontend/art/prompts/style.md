# Campus asset prompt template

Last updated: 2026-11-07 23:00 +0900

Plan: `docs/261107-2236-campus_asset_composition_plan.md` (sections 5-1 to 5-4).
Every `generate_image` call for campus assets uses this template. Prompts are in English.

## Fixed call parameters

| Parameter | Value |
|---|---|
| `image_size` | `2K` |
| `aspect_ratio` | `1:1` unless the request says otherwise |
| `images` | `generated-images/20261107-225318-a1a-c3-clocktower-2k.png` (style anchor, once approved), plus `src/assets/images/campus_growth_5x2_final_1793921090067.jpg` |

## Style block (append to every prompt)

```
Isometric 2:1 pixel art matching the style of the reference images: cozy university campus, light from the upper left, 1px dark brown outlines, limited palette, crisp hard-edged pixels with no anti-aliasing and no blur. Same camera angle and pixel density as the reference clock tower building.
```

## Constraint block (append to every sprite prompt)

```
Absolutely no shadows of any kind: no cast shadow, no drop shadow, no ground shadow, no dark patch under the object. No ground tile, no grass, no soil base under the object. No text, no labels, no watermark, no grid lines.
```

## Background block (choose one)

Default (everything except pink or magenta-like subjects):

```
The background must be one solid flat magenta color #FF00FF filling the whole image, with no gradient, no texture, no vignette, and no other objects.
```

Pink subjects (cherry blossom trees, pink flowers, flowerbeds with pink or purple flowers):

```
The background must be one solid flat cyan color #00FFFF filling the whole image, with no gradient, no texture, no vignette, and no other objects.
```

Seamless textures (grass, dirt, paving, water, court, track): no background block. Use instead:

```
A seamless tileable square texture viewed from directly above, filling the entire image edge to edge, with no border, no vignette, no objects, and no lighting gradient, so that the left edge matches the right edge and the top edge matches the bottom edge.
```

## Layout block

Single sprite:

```
A single <subject>, as a standalone game sprite, centered with wide empty margin on all sides.
```

Sheet of N objects:

```
A sprite sheet of <N> separate game objects arranged in a <R> by <C> grid, each object centered in its own cell with wide empty margin, no object touching or overlapping another cell. <Row-by-row list>. All objects share the same scale and camera angle.
```
