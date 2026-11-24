/**
 * Inputs of each growth-stage screenshot, hashed so tests/campusShots.test.ts can tell when a shot
 * is stale: the level's layout data, its stage definition, the day sky constants, the capture size,
 * and the bytes of every asset file the day scene draws (1x and HD). Drawing code is left out on
 * purpose (plan docs/261110-0747-campus_level_shots_plan.md 5-3, D4).
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { CAMPUS_SHOT_SIZE } from '../../src/data/campusGeometry';
import { CAMPUS_LAYOUTS, CAMPUS_SKY_COLORS, CAMPUS_SUN_POSITION } from '../../src/data/campusLayouts';
import { CAMPUS_STAGES } from '../../src/data/gameRules';

export const ROOT = path.resolve(import.meta.dirname, '../..');
export const SHOTS_DIR = path.join(ROOT, 'src/assets/campus-shots');
export const SHOTS_JSON = path.join(SHOTS_DIR, 'shots.json');
export const SHOT_SIZE = CAMPUS_SHOT_SIZE;
/** Assets every day scene draws outside the layout (CampusScene sky). */
const SCENE_ASSETS = ['sun', 'horizon-forest'];

interface ManifestEntry { file: string; hdScales?: number[] }
const MANIFEST: Record<string, ManifestEntry> = JSON.parse(readFileSync(path.join(ROOT, 'src/assets/campus/manifest.json'), 'utf8'));

export const shotFile = (level: number) => `lv${String(level).padStart(2, '0')}.webp`;

/** Asset ids the day scene of a level draws. */
export function shotAssets(level: number): string[] {
  const layout = CAMPUS_LAYOUTS[level];
  if (!layout) throw new Error(`Lv.${level} has no layout`);
  return [...new Set([layout.ground, ...layout.terrain.map((t) => t.texture), ...layout.items.map((i) => i.asset), ...layout.clouds.map((c) => c.asset), ...SCENE_ASSETS])].sort();
}

export function shotInputHash(level: number): string {
  const hash = createHash('sha256');
  hash.update(JSON.stringify({ layout: CAMPUS_LAYOUTS[level], stage: CAMPUS_STAGES[level - 1], sky: CAMPUS_SKY_COLORS.day, sun: CAMPUS_SUN_POSITION, size: SHOT_SIZE }));
  for (const id of shotAssets(level)) {
    const entry = MANIFEST[id];
    if (!entry) throw new Error(`asset ${id} is not in src/assets/campus/manifest.json`);
    const files = [path.join(ROOT, 'src/assets/campus', entry.file), ...(entry.hdScales ?? []).map((s) => path.join(ROOT, 'src/assets/campus-hd', String(s), entry.file))];
    for (const file of files) hash.update(`${id}:${path.relative(ROOT, file)}:`).update(readFileSync(file));
  }
  return hash.digest('hex');
}
