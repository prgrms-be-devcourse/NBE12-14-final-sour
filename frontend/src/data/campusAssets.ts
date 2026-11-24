// Campus asset registry built from the generated manifest (scripts/assets/process.py).
// Plan: docs/261107-2236-campus_asset_composition_plan.md, sections 5-5 and 5-6. Sprites draw from
// display-resolution copies (method B, docs/261108-1014-campus_hd_default_plan.md); the 1x files stay
// the coordinate reference and the fallback.
import { CAMPUS_ASSET_MANIFEST } from '../assets/campus/manifest';
import CAMPUS_FOOTPRINTS from '../assets/campus/footprints.json';

export type CampusAssetId = keyof typeof CAMPUS_ASSET_MANIFEST;

/** Top folder of the asset in src/assets/campus (the manifest `file` path). */
export type CampusAssetCategory = 'buildings' | 'objects' | 'people' | 'sky' | 'terrain';

export interface CampusAsset {
  id: CampusAssetId;
  category: CampusAssetCategory;
  url: string;
  kind: 'sprite' | 'texture';
  width: number;
  height: number;
  /** Bottom-center point placed on the scene coordinate; [0, 0] for textures. */
  anchor: readonly [number, number];
  /** Night window-light mask of the same size (buildings only, plan 4-2). */
  lightsUrl?: string;
  /**
   * Display-resolution copies by scale (exactly width × scale by height × scale), from
   * `process.py --hd`. Pick one with hdScale; sprites without them draw the 1x file.
   */
  hd?: Readonly<Record<number, CampusHdFile>>;
  /**
   * Isometric ground footprint corners in sprite pixels (scripts/assets/footprints.py). The
   * front corner is usually not the anchor: anchors are bounding-box centers.
   */
  footprint?: CampusFootprint;
  /** Ground face(s) of the unflipped sprite that hold the main entrance (buildings only). */
  door?: CampusDoorSide;
}

/** 'inner': the inside corner of an L-shaped building (CampusFootprint.inner), facing its courtyard. */
export type CampusDoorSide = 'left' | 'right' | 'both' | 'inner';

/**
 * Entrance faces read off the sprites by eye (docs/261109-1950-campus_lead_feedback_plan.md, U0).
 * Mockup data: the placement check requires a path at each entrance. Buildings missing here
 * (tent, fountain) need no path.
 */
const DOOR_SIDES: Partial<Record<string, CampusDoorSide>> = {
  'hall-small': 'left', 'hall-extended': 'left', 'main-clocktower': 'left', library: 'left', 'lecture-2': 'left', 'lecture-3': 'left',
  'library-r': 'right', 'lecture-2-r': 'right', 'sports-hall': 'left', 'student-hall': 'right', 'dorm-a': 'left', 'dorm-b': 'left', 'twin-tower': 'right', stadium: 'both', 'grand-hall': 'inner',
};

export interface CampusHdFile {
  url: string;
  lightsUrl?: string;
}

export interface CampusFootprint {
  left: readonly [number, number];
  front: readonly [number, number];
  right: readonly [number, number];
  /**
   * Inside corner of an L-shaped building seen from inside the L (grand-hall, Lv.10). The footprint is
   * then the diamond minus the courtyard between this corner and the (empty) front corner.
   */
  inner?: readonly [number, number];
}

const FOOTPRINTS = CAMPUS_FOOTPRINTS as Partial<Record<string, { left: number[]; front: number[]; right: number[]; inner?: number[] }>>;
const toPoint = (p: number[]) => [p[0], p[1]] as const;

// Vite rewrites this two-segment template into a glob over src/assets/campus/*/*.png
// (which also covers the <id>.lights.png masks).
const assetUrl = (category: string, name: string) => new URL(`../assets/campus/${category}/${name}.png`, import.meta.url).href;
const urlOf = (file: string) => {
  const [category, name] = file.split('/');
  return assetUrl(category, name.replace(/\.png$/, ''));
};
// Likewise a glob over src/assets/campus-hd/*/*/*.png.
const hdAssetUrl = (scale: number, category: string, name: string) => new URL(`../assets/campus-hd/${scale}/${category}/${name}.png`, import.meta.url).href;
const hdUrlOf = (scale: number, file: string) => {
  const [category, name] = file.split('/');
  return hdAssetUrl(scale, category, name.replace(/\.png$/, ''));
};
const hdOf = (entry: { file: string; lights?: string; hdScales?: readonly number[] }): Record<number, CampusHdFile> | undefined => entry.hdScales?.length
  ? Object.fromEntries(entry.hdScales.map((scale) => [scale, { url: hdUrlOf(scale, entry.file), ...(entry.lights ? { lightsUrl: hdUrlOf(scale, entry.lights) } : {}) }]))
  : undefined;

/**
 * The HD scale for a layer's device ratio (device px per 1x px): the smallest scale that covers it,
 * so the browser only shrinks, by less than the step between scales. Unknown ratios (0 before the
 * frame is measured) and ratios above every scale take the largest.
 */
export function hdScale(ratio: number, scales: readonly number[]): number | undefined {
  const sorted = [...scales].sort((a, b) => a - b);
  if (!sorted.length) return undefined;
  if (!Number.isFinite(ratio) || ratio <= 0) return sorted[sorted.length - 1];
  return sorted.find((s) => s >= ratio) ?? sorted[sorted.length - 1];
}

/** The HD copy of an asset for a device ratio, or undefined to draw the 1x file. */
export function hdFileFor(asset: CampusAsset, ratio: number): CampusHdFile | undefined {
  if (!asset.hd) return undefined;
  const scale = hdScale(ratio, Object.keys(asset.hd).map(Number));
  return scale === undefined ? undefined : asset.hd[scale];
}

export const CAMPUS_ASSETS: Record<CampusAssetId, CampusAsset> = Object.fromEntries(
  (Object.entries(CAMPUS_ASSET_MANIFEST) as [CampusAssetId, (typeof CAMPUS_ASSET_MANIFEST)[CampusAssetId]][]).map(([id, entry]) => {
    const hd = hdOf(entry as { file: string; lights?: string; hdScales?: readonly number[] });
    return [id, {
      id,
      category: entry.file.split('/')[0] as CampusAssetCategory,
      url: urlOf(entry.file),
      kind: entry.kind,
      width: entry.width,
      height: entry.height,
      anchor: 'anchor' in entry ? entry.anchor : [0, 0],
      ...('lights' in entry ? { lightsUrl: urlOf(entry.lights) } : {}),
      ...(hd ? { hd } : {}),
      ...(FOOTPRINTS[id] ? { footprint: { left: toPoint(FOOTPRINTS[id].left), front: toPoint(FOOTPRINTS[id].front), right: toPoint(FOOTPRINTS[id].right), ...(FOOTPRINTS[id].inner ? { inner: toPoint(FOOTPRINTS[id].inner) } : {}) } } : {}),
      ...(DOOR_SIDES[id] ? { door: DOOR_SIDES[id] } : {}),
    } satisfies CampusAsset];
  }),
) as Record<CampusAssetId, CampusAsset>;

export function isCampusAssetId(value: string): value is CampusAssetId {
  return Object.hasOwn(CAMPUS_ASSET_MANIFEST, value);
}

/**
 * Buildings are the assets under `buildings/` (16 kinds, plan T6 §9). Only they wobble on a tap
 * (user decision Q8); the fountain has a footprint but sits under `objects/`, so it is not one.
 */
export function isBuilding(asset: CampusAsset): boolean {
  return asset.category === 'buildings';
}
