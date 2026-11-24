// Checks the processed campus asset PNGs against src/assets/campus/manifest.json.
// Plan: docs/261107-2236-campus_asset_composition_plan.md, section 8 ("에셋 파일").
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { inflateSync } from 'node:zlib';

const CAMPUS_DIR = join(import.meta.dirname, '..', 'src', 'assets', 'campus');
const MANIFEST_PATH = join(CAMPUS_DIR, 'manifest.json');
const KEY_COLORS = [[255, 0, 255], [0, 255, 255]] as const;
const KEY_DISTANCE = 100;
const MAX_KEY_RATIO = 0.005;
const MAX_ISOLATED_PIXELS = 2;
const MIN_SEAM_LIMIT = 2;

interface AssetEntry { file: string; kind: 'sprite' | 'texture'; width: number; height: number; anchor?: [number, number]; lights?: string }
interface DecodedPng { width: number; height: number; colorType: number; bitDepth: number; pixels: Uint8Array }

/** Minimal decoder for non-interlaced 8-bit RGBA PNGs, which is what scripts/assets/process.py writes. */
function decodePng(buffer: Buffer): DecodedPng {
  assert.equal(buffer.subarray(0, 8).toString('hex'), '89504e470d0a1a0a', 'not a PNG signature');
  let offset = 8;
  let width = 0, height = 0, bitDepth = 0, colorType = 0, interlace = 0;
  const idat: Buffer[] = [];
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0); height = data.readUInt32BE(4);
      bitDepth = data[8]; colorType = data[9]; interlace = data[12];
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    offset += 12 + length;
  }
  if (colorType !== 6 || bitDepth !== 8 || interlace !== 0) return { width, height, colorType, bitDepth, pixels: new Uint8Array() };
  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * 4;
  const pixels = new Uint8Array(height * stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    for (let x = 0; x < stride; x++) {
      const a = x >= 4 ? pixels[y * stride + x - 4] : 0;
      const b = y > 0 ? pixels[(y - 1) * stride + x] : 0;
      const c = x >= 4 && y > 0 ? pixels[(y - 1) * stride + x - 4] : 0;
      let value = line[x];
      if (filter === 1) value += a;
      else if (filter === 2) value += b;
      else if (filter === 3) value += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        value += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      pixels[y * stride + x] = value & 0xff;
    }
  }
  return { width, height, colorType, bitDepth, pixels };
}

test('shared palette keeps a near-white color so white details do not turn pink', () => {
  const palette = decodePngRgb(readFileSync(join(import.meta.dirname, '..', 'art', 'palette.png')));
  const nearWhite = palette.some(([r, g, b]) => r >= 235 && g >= 235 && b >= 225 && Math.max(r, g, b) - Math.min(r, g, b) <= 20);
  assert.ok(nearWhite, 'art/palette.png has no near-white color; keep FIXED_COLORS in scripts/assets/build_palette.py and rebuild the palette');
});

/** RGB triples of a palette PNG (RGB or RGBA, 8-bit, non-interlaced; one row). */
function decodePngRgb(buffer: Buffer): [number, number, number][] {
  let offset = 8, width = 0, colorType = 0;
  const idat: Buffer[] = [];
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') { width = data.readUInt32BE(0); colorType = data[9]; }
    else if (type === 'IDAT') idat.push(data);
    offset += 12 + length;
  }
  const channels = colorType === 6 ? 4 : 3;
  const row = inflateSync(Buffer.concat(idat)).subarray(0, 1 + width * channels);
  assert.ok(row[0] === 0 || row[0] === 1, 'palette row uses an unsupported PNG filter');
  const out: [number, number, number][] = [];
  const prev = [0, 0, 0, 0];
  for (let x = 0; x < width; x++) {
    const px = [0, 1, 2].map((c) => (row[1 + x * channels + c] + (row[0] === 1 ? prev[c] : 0)) & 0xff);
    px.forEach((v, c) => { prev[c] = v; });
    out.push(px as [number, number, number]);
  }
  return out;
}

const manifest: Record<string, AssetEntry> = existsSync(MANIFEST_PATH) ? JSON.parse(readFileSync(MANIFEST_PATH, 'utf8')) : {};

test('campus asset manifest exists and lists assets', () => {
  assert.ok(existsSync(MANIFEST_PATH), `missing ${MANIFEST_PATH}; run python3 -I scripts/assets/process.py`);
  assert.ok(Object.keys(manifest).length > 0);
});

for (const [id, entry] of Object.entries(manifest)) {
  test(`campus asset ${id} is a clean RGBA PNG matching the manifest`, () => {
    const png = decodePng(readFileSync(join(CAMPUS_DIR, entry.file)));
    assert.equal(png.colorType, 6, `${id}: expected RGBA (color type 6); re-run scripts/assets/process.py`);
    assert.equal(png.bitDepth, 8);
    assert.deepEqual([png.width, png.height], [entry.width, entry.height], `${id}: size differs from manifest`);

    let opaque = 0, keyLike = 0;
    for (let i = 0; i < png.pixels.length; i += 4) {
      if (png.pixels[i + 3] === 0) continue;
      opaque++;
      const [r, g, b] = [png.pixels[i], png.pixels[i + 1], png.pixels[i + 2]];
      if (KEY_COLORS.some(([kr, kg, kb]) => Math.hypot(r - kr, g - kg, b - kb) <= KEY_DISTANCE)) keyLike++;
    }
    assert.ok(opaque > 0, `${id}: image is fully transparent`);
    assert.ok(keyLike / opaque <= MAX_KEY_RATIO,
      `${id}: ${keyLike}/${opaque} opaque pixels are near the magenta/cyan key color; raise keyTolerance or regenerate the source`);

    if (entry.kind === 'texture') {
      assert.equal(opaque, png.width * png.height, `${id}: textures must be fully opaque`);
      // Seam check: the wrap-around difference (last column vs first, last row vs first) must stay
      // close to the ordinary difference between neighboring pixels inside the texture.
      const rgbAt = (x: number, y: number) => png.pixels.subarray((y * png.width + x) * 4, (y * png.width + x) * 4 + 3);
      const diff = (p: Uint8Array, q: Uint8Array) => (Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]) + Math.abs(p[2] - q[2])) / 3;
      let inner = 0, innerCount = 0, seamX = 0, seamY = 0;
      for (let y = 0; y < png.height; y++) {
        for (let x = 1; x < png.width; x++) { inner += diff(rgbAt(x, y), rgbAt(x - 1, y)); innerCount++; }
        seamX += diff(rgbAt(0, y), rgbAt(png.width - 1, y));
      }
      for (let x = 0; x < png.width; x++) seamY += diff(rgbAt(x, 0), rgbAt(x, png.height - 1));
      const limit = Math.max(MIN_SEAM_LIMIT, 2 * (inner / innerCount));
      assert.ok(seamX / png.height <= limit && seamY / png.width <= limit,
        `${id}: visible tiling seam (left-right ${(seamX / png.height).toFixed(2)}, top-bottom ${(seamY / png.width).toFixed(2)}, limit ${limit.toFixed(2)}); regenerate the texture or blend its edges`);
    } else {
      // Palette quantization turns leftover key color into ordinary palette colors, so the color
      // check above cannot see incomplete keying. Its visible symptom is stray specks: they widen the
      // bounding box and shrink the real object. Count opaque pixels with no opaque 4-neighbor.
      const isOpaque = (x: number, y: number) => x >= 0 && y >= 0 && x < png.width && y < png.height && png.pixels[(y * png.width + x) * 4 + 3] > 0;
      let isolated = 0;
      for (let y = 0; y < png.height; y++) {
        for (let x = 0; x < png.width; x++) {
          if (isOpaque(x, y) && !isOpaque(x - 1, y) && !isOpaque(x + 1, y) && !isOpaque(x, y - 1) && !isOpaque(x, y + 1)) isolated++;
        }
      }
      assert.ok(isolated <= MAX_ISOLATED_PIXELS,
        `${id}: ${isolated} isolated opaque pixels; the background was not fully removed. Raise keyTolerance in scripts/assets/manifest.json or regenerate the source`);
      assert.ok(opaque / (png.width * png.height) < 0.95, `${id}: sprite is almost fully opaque; background removal likely failed`);
      assert.ok(entry.anchor, `${id}: sprites need an anchor`);
      const [ax, ay] = entry.anchor!;
      assert.ok(ax >= 0 && ax < png.width && ay >= 0 && ay < png.height, `${id}: anchor outside the image`);
      if (entry.lights) {
        // The night mask is overlaid at the sprite's position, so it must match its size and only
        // light pixels that belong to the building.
        const mask = decodePng(readFileSync(join(CAMPUS_DIR, entry.lights)));
        assert.deepEqual([mask.width, mask.height], [png.width, png.height], `${id}: light mask size differs from the sprite; re-run scripts/assets/process.py`);
        let lit = 0, outside = 0;
        for (let i = 3; i < mask.pixels.length; i += 4) {
          if (mask.pixels[i] === 0) continue;
          lit++;
          if (png.pixels[i] === 0) outside++;
        }
        assert.ok(lit > 0, `${id}: light mask is empty`);
        assert.equal(outside, 0, `${id}: ${outside} light pixels fall outside the building`);
      }
    }
  });
}

// Footprints (scripts/assets/footprints.py) must sit on the sprite's drawn ground line: the front
// corner is the lowest opaque pixel of its column. Plan: docs/261108-0128-campus_scene_layout_fix_plan.md 6-2.
const FOOTPRINTS_PATH = join(CAMPUS_DIR, 'footprints.json');
const footprints: Record<string, Record<'left' | 'front' | 'right', [number, number]> & { inner?: [number, number] }> = existsSync(FOOTPRINTS_PATH) ? JSON.parse(readFileSync(FOOTPRINTS_PATH, 'utf8')) : {};

test('campus footprints exist for building sprites', () => {
  assert.ok(existsSync(FOOTPRINTS_PATH), `missing ${FOOTPRINTS_PATH}; run python3 -I scripts/assets/footprints.py`);
  for (const id of ['hall-small', 'hall-extended', 'main-clocktower', 'library', 'library-r', 'lecture-2', 'lecture-2-r', 'sports-hall']) assert.ok(footprints[id], `${id} has no footprint`);
});

for (const [id, fp] of Object.entries(footprints)) {
  test(`campus footprint ${id} lies on the sprite's ground line`, () => {
    const entry = manifest[id];
    assert.ok(entry, `${id}: footprint for an asset missing from the manifest`);
    // An L-shaped footprint's front corner is the empty courtyard corner, below the sprite.
    const onSprite = Object.entries(fp).filter(([name]) => !(fp.inner && name === 'front')).map(([, p]) => p);
    for (const [x, y] of onSprite) assert.ok(x >= 0 && y >= 0 && x < entry.width && y < entry.height, `${id}: footprint point ${x},${y} is outside the ${entry.width}x${entry.height} sprite`);
    const png = decodePng(readFileSync(join(CAMPUS_DIR, entry.file)));
    const lowestAt = (x: number) => {
      let lowest = -1;
      for (let y = 0; y < png.height; y++) if (png.pixels[(y * png.width + Math.round(x)) * 4 + 3] > 128) lowest = y;
      return lowest;
    };
    const [fx, fy] = fp.front;
    if (fp.inner) {
      // The wall base behind the entrance steps: the steps may hang up to 8px below it, never above.
      const [ix, iy] = fp.inner;
      const below = lowestAt(ix) - iy;
      assert.ok(below >= 0 && below <= 8, `${id}: inner corner y ${iy} but the lowest opaque pixel of column ${ix} is ${lowestAt(ix)}; fix the grand-hall OVERRIDES entry`);
      // Each wing's front corner is on the ground line: back = left + right - front, and the wings
      // are as deep as the inner corner sits from the back along U and V (footprintParts).
      const back = [fp.left[0] + fp.right[0] - fx, fp.left[1] + fp.right[1] - fy];
      const [dx, dy] = [ix - back[0], iy - back[1]];
      const [u, v] = [dx / 2 + dy, dy - dx / 2];
      for (const [x, y] of [[fp.left[0] + u, fp.left[1] + u / 2], [fp.right[0] - v, fp.right[1] + v / 2]]) {
        assert.ok(Math.abs(lowestAt(x) - y) <= 4, `${id}: wing corner ${x.toFixed(0)},${y.toFixed(0)} is off the ground line (lowest pixel ${lowestAt(x)})`);
      }
    } else {
      assert.ok(Math.abs(lowestAt(fx) - fy) <= 1, `${id}: front corner y ${fy} but the lowest opaque pixel of column ${fx} is ${lowestAt(fx)}; re-run scripts/assets/footprints.py`);
    }
    assert.ok(fp.left[0] < fx && fx < fp.right[0], `${id}: front corner must lie between the left and right corners`);
    // Both ground edges follow the 2:1 grid. A steep edge means a corner was read off a roof
    // overhang (sports-hall's roof trim, 2026-11-09); add an OVERRIDES entry in footprints.py.
    for (const [name, a, b] of [['left', fp.left, fp.front], ['right', fp.right, fp.front]] as const) {
      const slope = Math.abs(b[1] - a[1]) / Math.abs(b[0] - a[0]);
      assert.ok(slope >= 0.35 && slope <= 0.75, `${id}: ${name} ground edge slope ${slope.toFixed(2)} is off the 2:1 grid; check the ${name} corner and add an OVERRIDES entry in scripts/assets/footprints.py`);
    }
  });
}

// grand-hall's slate roofs and spires are nearly as blue as glass; "lightsBlue": 35 in
// scripts/assets/manifest.json keeps them dark at night (2026-11-10). The spires fill the top 15% of
// the sprite with no window, so no light may fall there in any copy.
test('grand-hall night lights stay off the spires and roofs', () => {
  const HD_DIR = join(import.meta.dirname, '..', 'src', 'assets', 'campus-hd');
  for (const path of [join(CAMPUS_DIR, 'buildings', 'grand-hall.lights.png'), ...[3, 6].map((s) => join(HD_DIR, String(s), 'buildings', 'grand-hall.lights.png'))]) {
    const mask = decodePng(readFileSync(path));
    const rows = Math.floor(mask.height * 0.15);
    let lit = 0;
    for (let i = 3; i < rows * mask.width * 4; i += 4) if (mask.pixels[i] > 0) lit++;
    assert.equal(lit, 0, `${path}: ${lit} lit pixels on the spires; raise lightsBlue for grand-hall and re-run process.py (1x and --hd)`);
  }
});

// Method B is the default display (docs/261108-1014-campus_hd_default_plan.md 4-5). The scene draws an
// HD file into the 1x box, so a 1x sprite regenerated at a new size without rerunning --hd would
// stretch its HD copies; this check catches that (deferred D4 of the method B trial).
test('every sprite with hdScales has HD files of exactly the 1x size times the scale', () => {
  const HD_DIR = join(import.meta.dirname, '..', 'src', 'assets', 'campus-hd');
  const manifest = JSON.parse(readFileSync(MANIFEST_PATH, 'utf8')) as Record<string, AssetEntry & { hdScales?: number[] }>;
  const sizeOf = (path: string) => {
    const buffer = readFileSync(path);
    assert.equal(buffer.subarray(0, 8).toString('hex'), '89504e470d0a1a0a', `${path} is not a PNG`);
    return [buffer.readUInt32BE(16), buffer.readUInt32BE(20)];
  };
  const fix = 'rerun python3 -I scripts/assets/process.py --hd';
  let checked = 0;
  for (const [id, entry] of Object.entries(manifest)) {
    if (!entry.hdScales) continue;
    assert.equal(entry.kind, 'sprite', `${id}: only sprites have HD copies`);
    assert.ok(entry.hdScales.length > 0, `${id}: empty hdScales`);
    for (const scale of entry.hdScales) {
      for (const file of [entry.file, ...(entry.lights ? [entry.lights] : [])]) {
        const path = join(HD_DIR, String(scale), file);
        assert.ok(existsSync(path), `${id}: missing ${path}; ${fix}`);
        assert.deepEqual(sizeOf(path), [entry.width * scale, entry.height * scale], `${id}: ${scale}x ${file} is not ${scale} × ${entry.width}x${entry.height}; ${fix}`);
        checked++;
      }
    }
  }
  assert.ok(checked > 0, 'no sprite lists hdScales');
});
