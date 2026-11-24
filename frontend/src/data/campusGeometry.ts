// Camera, isometric helpers, footprints and UI safe areas for composed campus scenes.
// Plan: docs/261108-0128-campus_scene_layout_fix_plan.md, section 6. All numbers are mockup values.
import { CAMPUS_ASSETS, type CampusAssetId } from './campusAssets';

export type Point = readonly [number, number];

/** Frame units across the campus frame. The sky layer is drawn in these units, unzoomed. */
export const CAMPUS_FRAME_WIDTH = 360;
/** Sky band height in frame units; the world's horizon line sits at this height on screen. */
export const CAMPUS_SKY_HEIGHT = 96;
/** Height / width of the in-game campus frame (358x367 CSS px inside its border, measured). */
export const CAMPUS_DEFAULT_ASPECT = 367 / 358;
/** Growth-stage screenshot frame (CSS px) and capture pixel ratio: the in-game frame, so shots show the in-game view. */
export const CAMPUS_SHOT_SIZE = { width: 358, height: 367, scale: 2 } as const;
/** Tallest supported frame (sm breakpoint, 358x392 CSS px, measured); used for bottom-edge checks. */
export const CAMPUS_TALL_ASPECT = 392 / 358;

/** Where the camera looks: world x at the frame center, world y of the horizon, and magnification. */
export interface CampusCamera {
  cx: number;
  horizon: number;
  zoom: number;
  /** Sky band height in frame units; CAMPUS_SKY_HEIGHT unless a scene needs room above the hills (title scene). */
  skyHeight?: number;
}

/** Sky band height of a camera in frame units. */
export const skyHeightOf = (camera: CampusCamera) => camera.skyHeight ?? CAMPUS_SKY_HEIGHT;

export interface CampusView {
  x: number;
  y: number;
  width: number;
  height: number;
  /** World y of the sky band's top edge (the in-game frame's top), wherever the view starts. */
  skyTop: number;
}

/**
 * World rectangle the frame shows. Taller frames than the in-game one show more ground below with
 * the horizon in place; flatter frames (visiting-school banners) show the vertical middle of the
 * in-game view, as the old centered slice did, so they show buildings rather than only sky.
 */
export function campusView(camera: CampusCamera, aspect = CAMPUS_DEFAULT_ASPECT): CampusView {
  const width = CAMPUS_FRAME_WIDTH / camera.zoom;
  const skyTop = camera.horizon - skyHeightOf(camera) / camera.zoom;
  const height = width * aspect;
  const y = aspect < CAMPUS_DEFAULT_ASPECT ? skyTop + (width * CAMPUS_DEFAULT_ASPECT - height) / 2 : skyTop;
  return { x: camera.cx - width / 2, y, width, height, skyTop };
}

/**
 * In-game UI drawn over the frame (CampusView.tsx), as [left, top, right, bottom] fractions of
 * the frame. Measured with Playwright at 430 and 700 px viewports and rounded outward; label
 * widths vary with the stage name, so the label boxes take the widest case plus a margin.
 */
export const CAMPUS_UI_OVERLAYS: Readonly<Record<string, readonly [number, number, number, number]>> = {
  'level-label': [0, 0, 0.46, 0.1],
  'feature-label-top': [0.7, 0, 1, 0.1],
  // Stats sign (T5), replacing the bottom feature label. Measured at frames 320–396px wide and 291–433px tall
  // (T2 heights): left 0.699–0.758, top 0.816–0.878; rounded outward.
  'stats-button': [0.68, 0.8, 1, 1],
  guestbook: [0, 0.82, 0.2, 1],
  'demo-hand': [0.5, 0.45, 0.65, 0.58],
};

const r = (n: number) => Math.round(n);
export const pt = (x: number, y: number): Point => [r(x), r(y)];

// Isometric ground axes (2:1). U runs down-right, V runs down-left; both have x length 1.
export const ISO_U: Point = [1, 0.5];
export const ISO_V: Point = [-1, 0.5];
export const add = (p: Point, q: Point, k = 1): Point => [p[0] + q[0] * k, p[1] + q[1] * k];
export const mid = (p: Point, q: Point, t = 0.5): Point => [p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t];

/** Isometric rectangle centered on c with half-lengths a along U and b along V (x px). */
export function isoRect(c: Point, a: number, b = a): Point[] {
  return [add(add(c, ISO_U, -a), ISO_V, -b), add(add(c, ISO_U, a), ISO_V, -b), add(add(c, ISO_U, a), ISO_V, b), add(add(c, ISO_U, -a), ISO_V, b)]
    .map(([x, y]) => pt(x, y));
}

/** Straight path from a to b along U or V, half-width hw (x px). */
export function isoStrip(a: Point, b: Point, hw: number): Point[] {
  const alongU = (b[0] - a[0]) * (b[1] - a[1]) > 0;
  const side = alongU ? ISO_V : ISO_U;
  return [add(a, side, -hw), add(b, side, -hw), add(b, side, hw), add(a, side, hw)].map(([x, y]) => pt(x, y));
}

/** Point reached from p by moving `u` along U and `v` along V. */
export const iso = (p: Point, u: number, v = 0): Point => add(add(p, ISO_U, u), ISO_V, v);

/** Irregular pond outline: an octagon squashed to the ground plane. */
export function pond(cx: number, cy: number, rx: number, ry: number): Point[] {
  const wobble = [1, 0.86, 1.05, 0.92, 1, 0.9, 1.08, 0.88];
  return wobble.map((w, i) => pt(cx + Math.cos((i / 8) * Math.PI * 2) * rx * w, cy + Math.sin((i / 8) * Math.PI * 2) * ry * w));
}

export interface Placed {
  asset: CampusAssetId;
  x: number;
  y: number;
  flip?: boolean;
  /** Size multiplier about the anchor (buildings only, lead feedback Q4). */
  scale?: number;
}

/** World point of a sprite pixel for a placed sprite. */
export function spriteToWorld(item: Placed, [px, py]: Point): Point {
  const [ax, ay] = CAMPUS_ASSETS[item.asset].anchor;
  const s = item.scale ?? 1;
  return [item.flip ? item.x + (ax - px) * s : item.x + (px - ax) * s, item.y + (py - ay) * s];
}

/** World bounding box [left, top, right, bottom] of a placed sprite. */
export function spriteBox(item: Placed): [number, number, number, number] {
  const { width, height, anchor: [ax, ay] } = CAMPUS_ASSETS[item.asset];
  const s = item.scale ?? 1;
  const left = item.flip ? item.x + (ax - width) * s : item.x - ax * s;
  return [left, item.y - ay * s, left + width * s, item.y + (height - ay) * s];
}

export interface WorldFootprint {
  left: Point;
  front: Point;
  right: Point;
  back: Point;
  /** Inside corner of an L-shaped building; the courtyard runs from here to `front`. */
  inner?: Point;
}

/** Ground footprint of a building-like sprite in world coordinates, or undefined for props. */
export function footprintOf(item: Placed): WorldFootprint | undefined {
  const fp = CAMPUS_ASSETS[item.asset].footprint;
  if (!fp) return undefined;
  let left = spriteToWorld(item, fp.left);
  let right = spriteToWorld(item, fp.right);
  if (item.flip) [left, right] = [right, left];
  const front = spriteToWorld(item, fp.front);
  const back: Point = [left[0] + right[0] - front[0], left[1] + right[1] - front[1]];
  return { left, front, right, back, ...(fp.inner ? { inner: spriteToWorld(item, fp.inner) } : {}) };
}

/** U and V lengths of the move from p to q (inverse of iso). */
const isoParts = (p: Point, q: Point): [number, number] => {
  const [dx, dy] = [q[0] - p[0], q[1] - p[1]];
  return [dx / 2 + dy, dy - dx / 2];
};

/**
 * Convex parts of a footprint: the diamond itself, or for an L-shaped building its two wings
 * (back-left wing along V and back-right wing along U, overlapping in the corner block).
 */
export function footprintParts(f: WorldFootprint): Point[][] {
  if (!f.inner) return [[f.back, f.right, f.front, f.left]];
  const [u, v] = isoParts(f.back, f.inner);
  return [[f.back, add(f.back, ISO_U, u), add(f.left, ISO_U, u), f.left], [f.back, f.right, add(f.right, ISO_V, v), add(f.back, ISO_V, v)]];
}

/** Ground outline: the diamond, or the L-shaped hexagon around the courtyard. */
export function footprintPolygon(f: WorldFootprint): Point[] {
  if (!f.inner) return [f.back, f.right, f.front, f.left];
  const [u, v] = isoParts(f.back, f.inner);
  return [f.back, f.right, add(f.right, ISO_V, v), f.inner, add(f.left, ISO_U, u), f.left];
}

/** Each footprint part shrunk toward its own center: the parts no path or prop may enter. */
export function footprintCores(f: WorldFootprint, keep = 0.7): Point[][] {
  return footprintParts(f).map((part) => {
    const c = mid(part[1], part[3]);
    return part.map((p) => mid(c, p, keep));
  });
}

/** Midpoint of a building's front-left or front-right face at ground level: where a path meets it. */
export function faceMid(item: Placed, side: 'left' | 'right'): Point {
  const f = footprintOf(item);
  if (!f) throw new Error(`${item.asset} has no footprint`);
  const p = mid(f.front, side === 'left' ? f.left : f.right);
  return pt(p[0], p[1]);
}

/** Entrance points (faceMid of each door face) of a placed building; empty when it has no door. */
export function doorPoints(item: Placed): Point[] {
  const door = CAMPUS_ASSETS[item.asset].door;
  const f = footprintOf(item);
  if (!door || !f) return [];
  if (door === 'inner') return f.inner ? [f.inner] : [];
  const flipSide = (s: 'left' | 'right') => (item.flip ? (s === 'left' ? 'right' : 'left') : s);
  const sides: ('left' | 'right')[] = door === 'both' ? ['left', 'right'] : [flipSide(door)];
  return sides.map((s) => faceMid(item, s));
}

/** Distance from p to segment ab. */
function segmentDistance(p: Point, a: Point, b: Point): number {
  const [dx, dy] = [b[0] - a[0], b[1] - a[1]];
  const len2 = dx * dx + dy * dy;
  const t = len2 ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2)) : 0;
  return Math.hypot(p[0] - (a[0] + dx * t), p[1] - (a[1] + dy * t));
}

const edges = (poly: readonly Point[]) => poly.map((p, i) => [p, poly[(i + 1) % poly.length]] as const);

/** Distance from p to a polygon: 0 inside, else to the nearest edge. */
export function polygonDistance(p: Point, poly: readonly Point[]): number {
  if (pointInPolygon(p, poly)) return 0;
  return Math.min(...edges(poly).map(([a, b]) => segmentDistance(p, a, b)));
}

/** Gap between two polygons: 0 when they overlap or touch, else the smallest vertex-to-edge distance. */
export function polygonGap(a: readonly Point[], b: readonly Point[]): number {
  const crosses = edges(a).some(([p, q]) => edges(b).some(([r, s]) => {
    const side = (o: Point, x: Point, y: Point) => Math.sign((x[0] - o[0]) * (y[1] - o[1]) - (x[1] - o[1]) * (y[0] - o[0]));
    return side(p, q, r) * side(p, q, s) < 0 && side(r, s, p) * side(r, s, q) < 0;
  }));
  if (crosses) return 0;
  return Math.min(...a.map((p) => polygonDistance(p, b)), ...b.map((p) => polygonDistance(p, a)));
}

export function pointInPolygon([x, y]: Point, poly: readonly Point[]): boolean {
  let inside = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, yi] = poly[i];
    const [xj, yj] = poly[j];
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/** Lattice of points spread over a convex quad (bilinear), for overlap sampling. */
export function sampleQuad(q: readonly Point[], n = 6): Point[] {
  const out: Point[] = [];
  for (let i = 0; i <= n; i++) for (let j = 0; j <= n; j++) {
    const a = mid(q[0], q[1], i / n);
    const b = mid(q[3], q[2], i / n);
    out.push(mid(a, b, j / n));
  }
  return out;
}

/** Fraction of a world box that is inside the frame and not under in-game UI. */
export function visibleFraction(box: readonly [number, number, number, number], view: CampusView): number {
  const toFrame = (x: number, y: number): Point => [(x - view.x) / view.width, (y - view.y) / view.height];
  const [l, t] = toFrame(box[0], box[1]);
  const [rr, b] = toFrame(box[2], box[3]);
  const area = (rr - l) * (b - t);
  if (area <= 0) return 0;
  const overlap = (a: readonly number[], c: readonly number[]) => Math.max(0, Math.min(a[2], c[2]) - Math.max(a[0], c[0])) * Math.max(0, Math.min(a[3], c[3]) - Math.max(a[1], c[1]));
  const own = [l, t, rr, b];
  const inFrame = overlap(own, [0, 0, 1, 1]);
  const hidden = Object.values(CAMPUS_UI_OVERLAYS).reduce((sum, o) => sum + overlap(own, o), 0);
  return Math.max(0, inFrame - hidden) / area;
}

/** Device px per world unit for a frame `cssWidth` CSS px wide (pass zoom 1 for the sky layer). */
export const campusDeviceRatio = (cssWidth: number, dpr: number, zoom: number) => (cssWidth * dpr * zoom) / CAMPUS_FRAME_WIDTH;
