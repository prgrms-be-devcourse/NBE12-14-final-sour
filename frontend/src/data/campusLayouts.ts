// Per-level campus scene layouts in world pixels, framed by a per-level camera.
// Plans: docs/261107-2236-campus_asset_composition_plan.md (5-6) and
// docs/261108-0128-campus_scene_layout_fix_plan.md (6, 7). Mockup-only placement values, not a
// service policy. Levels without a layout fall back to the legacy growth sheet (CampusSprite).
import { CAMPUS_ASSETS, isCampusAssetId, type CampusAssetId } from './campusAssets';
import {
  CAMPUS_FRAME_WIDTH, CAMPUS_SKY_HEIGHT, CAMPUS_TALL_ASPECT, campusView, doorPoints, faceMid, footprintCores, footprintOf, iso, isoRect, isoStrip, mid, pointInPolygon, polygonDistance,
  polygonGap, pond, pt, sampleQuad, skyHeightOf, spriteBox, visibleFraction, type CampusCamera, type CampusView, type Point,
} from './campusGeometry';
import { CAMPUS_STAGES } from './gameRules';

export interface CampusPlacement {
  asset: CampusAssetId;
  /** Point where the asset's anchor (bottom-center) lands: world px, or frame units for clouds. */
  x: number;
  y: number;
  flip?: boolean;
  /** Ground shadow under the sprite; defaults to true for sprites up to 60px wide (not buildings). */
  shadow?: boolean;
  /**
   * World y used for draw order instead of the anchor y. Anchors are bounding-box bottoms, so a prop
   * hugging a building's front face sorts behind it; set this past the building's y to draw it in front.
   */
  depth?: number;
  /**
   * Size multiplier about the anchor. Buildings only (lead feedback Q4): a small building can match
   * its neighbours without changing the camera. Footprints, entrances and HD picks follow it.
   */
  scale?: number;
  /** Sprite opacity (0-1), for trees whose canopy would hide the path behind them (lead feedback F6). */
  opacity?: number;
}

export interface CampusTerrain {
  texture: CampusAssetId;
  points: readonly Point[];
  edgeColor: string;
  /** Ground a building may stand on (yard, plinth). Other terrain is a path and must stay clear. */
  base?: boolean;
}

/** Effects drawn in code, not generated (plan 4-5). Fireworks use sky frame units. */
export type CampusEffect =
  | { kind: 'fireworks'; bursts: readonly { x: number; y: number; r: number; color: string }[] }
  | { kind: 'festival-glow'; x: number; y: number; rx: number; ry: number };
export type CampusEffectKind = CampusEffect['kind'];

/** A stage feature is shown by an asset, or by a code-drawn effect (`effect:<kind>`). */
export type CampusFeatureSource = CampusAssetId | `effect:${CampusEffectKind}`;

export interface CampusLayout {
  camera: CampusCamera;
  /** Clouds in the sky band, in frame units (unzoomed, like the sun and the far forest). */
  clouds: readonly CampusPlacement[];
  ground: CampusAssetId;
  terrain: readonly CampusTerrain[];
  items: readonly CampusPlacement[];
  /** Extra night light points (world px) for lit things without window glass, such as a tent. */
  glows?: readonly { x: number; y: number; r: number }[];
  effects?: readonly CampusEffect[];
  /** Sun and moon in frame units; CAMPUS_SUN_POSITION unless the scene moves them (title scene). */
  sun?: { x: number; y: number };
  /** Each CAMPUS_STAGES feature of this level mapped to the asset or effect that shows it. */
  features: Readonly<Record<string, CampusFeatureSource>>;
}

/** Mockup sky gradients (top, horizon) per ambience phase. Not a service policy. */
export const CAMPUS_SKY_COLORS = {
  dawn: ['#e9a07f', '#ffe0b5'],
  day: ['#5db6ee', '#cdeeff'],
  dusk: ['#c86f5a', '#ffc387'],
  night: ['#0a1430', '#2a3a70'],
} as const;
/** Sun and moon in frame units: between the two top labels, above every cloud (lead feedback 2026-11-09). */
export const CAMPUS_SUN_POSITION = { x: 212, y: 24 } as const;

const DIRT_EDGE = '#8a5a2b';
const PAVING_EDGE = '#7d7468';
const WATER_EDGE = '#2f5f8f';
const COURT_EDGE = '#f2f2ea';
const TRACK_EDGE = '#f2f2ea';
/** Every level shares the world horizon and camera center; zoom decides how much shows. */
const HORIZON = 100;
const camera = (zoom: number): CampusCamera => ({ cx: 180, horizon: HORIZON, zoom });

type Opt = Partial<Pick<CampusPlacement, 'flip' | 'shadow' | 'depth' | 'scale' | 'opacity'>>;
const at = (asset: CampusAssetId, [x, y]: Point, opt: Opt = {}): CampusPlacement => ({ asset, x: Math.round(x), y: Math.round(y), ...opt });
const P = (x: number, y: number): Point => [x, y];
const dirt = (points: Point[], base = false): CampusTerrain => ({ texture: 'tex-dirt', points, edgeColor: DIRT_EDGE, ...(base ? { base } : {}) });
const paving = (points: Point[], base = false): CampusTerrain => ({ texture: 'tex-paving', points, edgeColor: PAVING_EDGE, ...(base ? { base } : {}) });

/** n points from a to b inclusive. */
const along = (a: Point, b: Point, n: number): Point[] => Array.from({ length: n }, (_, i) => pt(a[0] + ((b[0] - a[0]) * i) / Math.max(1, n - 1), a[1] + ((b[1] - a[1]) * i) / Math.max(1, n - 1)));

/** Deterministic pseudo-random number in [0, 1) (integer hash), so scenes never reshuffle. */
function hash01(n: number): number {
  let h = Math.imul(n ^ 0x9e3779b9, 0x85ebca6b);
  h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35);
  return ((h ^ (h >>> 16)) >>> 0) / 2 ** 32;
}

const WALKERS = ['student-walk-1', 'student-walk-2', 'student-walk-3', 'student-walk-4', 'student-sit-1', 'student-sit-2'] as const;
/** Deterministic students scattered over an isometric rectangle (center c, half-lengths a, b). */
function crowd(c: Point, a: number, b: number, count: number, seed = 1): CampusPlacement[] {
  return Array.from({ length: count }, (_, i) => {
    const u = (hash01(seed * 7919 + i * 2) - 0.5) * 2 * a * 0.85;
    const v = (hash01(seed * 7919 + i * 2 + 1) - 0.5) * 2 * b * 0.85;
    return at(WALKERS[(i + seed) % (i % 4 === 3 ? 6 : 4)], iso(c, u, v), { flip: i % 2 === 1, shadow: false });
  });
}

/** Small ground clutter seen all over the growth sheet: grass flowers, rocks, bushes. */
function scatter(points: readonly Point[], seed = 0): CampusPlacement[] {
  const kinds = ['flowers-1', 'flowers-2', 'bush-2', 'flowers-1', 'rock', 'bush-1'] as const;
  return points.map((p, i) => at(kinds[(i + seed) % kinds.length], p, { flip: (i + seed) % 2 === 1 }));
}

// Lv.1-3 props: docs/261110-0031-campus_lv1-3_fill_plan.md (4). Humble wood and dirt only; iron
// lamps, paving and fountains stay for Lv.4 and later (plan 3).
// ---- Lv.1: an empty lot with a sign and a tent -------------------------------------------------
const lv1Tent = at('tent', P(198, 192));
const lv1Fire = at('campfire', P(176, 208));
/**
 * Survey stakes mark the lot for the Lv.2 schoolhouse (plan D3). The lot sits left of the hall's exact
 * Lv.2 site, which the tent covers, so the stakes stay clear of the camp.
 */
const lv1Lot = isoRect(P(156, 146), 18);
// ---- Lv.2: a small brick schoolhouse on a dirt yard -----------------------------------------------
const lv2Hall = at('hall-small', P(178, 168));
const lv2Yard = P(180, 166);
/** The dirt walk leaves the yard in front of the door (u -16 from the yard center) to the lower left. */
const lv2Walk = (v: number, u = 0): Point => iso(lv2Yard, -16 + u, v);
// ---- Lv.3: the extended hall with dirt paths and young woods --------------------------------------
const lv3Hall = at('hall-extended', P(172, 168));
const lv3Front = iso(faceMid(lv3Hall, 'left'), 0, 16);
const lv3At = (u: number, v: number): Point => iso(lv3Front, u, v);
// ---- Lv.4: clock-tower main hall, paved entrance, pond ------------------------------------------
const lv4Main = at('main-clocktower', P(180, 206));
const lv4Door = iso(faceMid(lv4Main, 'left'), 0, 12);
/** Pond on open grass between the two paths and the guestbook (F3). */
const lv4Pond = P(150, 256);
// ---- Lv.5: library and cherry garden ------------------------------------------------------------
const lv5Main = at('main-clocktower', P(222, 202));
// Rotated library (entrance on its right face) beside the hall, so it no longer covers the hall's
// entrance; both entrances meet at one plaza (F4, Q2).
const lv5Library = at('library-r', P(95, 198));
const lv5Door = iso(faceMid(lv5Main, 'left'), 0, 12);
const lv5Plaza = iso(lv5Door, 0, 34);
// ---- Lv.6: second lecture hall and lamp-lit plaza ------------------------------------------------
// Rotated lecture hall (entrance on its right face) and the hall's entrance both lead straight onto
// one plaza where their paths cross (F5).
// The lecture hall is scaled to read as large as the Lv.5 library (120x139); the hall moves left so
// it is not cut by the frame (user request 2026-11-09, A+C).
const lv6Main = at('main-clocktower', P(274, 196));
const lv6Plaza = iso(faceMid(lv6Main, 'left'), 0, 50);
const lv6Lecture = at('lecture-2-r', P(129, 188), { scale: 1.2 });
// ---- Lv.7: stadium and cherry avenue ------------------------------------------------------------
// Cherry avenue from the stadium's left entrance down to the lower left (F6). The rotated lecture
// hall faces the avenue across a short stub; it is scaled to the size it shows at Lv.6 (1.2 x 1.6 / 1.35,
// user decision 2026-11-09). No main hall at Lv.7 (user decision Q6-7, 2026-11-09): a sports hall fills
// the lower right instead (F10).
const lv7Stadium = at('stadium', P(264, 168));
const lv7Gate = doorPoints(lv7Stadium)[0];
const lv7Avenue: [Point, Point] = [lv7Gate, iso(lv7Gate, 0, 190)];
const lv7Lecture = at('lecture-2-r', P(98, 204), { scale: 1.42 });
const lv7Plaza = iso(lv7Gate, 0, 40);
// Sports hall below the stadium, right of the avenue (F10). Its door faces lower left, so a short stub
// runs down to a south walk that branches off the avenue.
const lv7Hall = at('sports-hall', P(262, 286));
const lv7HallDoor = faceMid(lv7Hall, 'left');
const lv7HallStub = iso(lv7HallDoor, 0, 20);
const lv7SouthV = lv7HallStub[1] - lv7Gate[1] - (lv7HallStub[0] - lv7Gate[0]) / 2;
const lv7South = iso(lv7Gate, 0, lv7SouthV);
// ---- Lv.8: fountain square and sports fields -----------------------------------------------------
// Growth-sheet Lv.8 layout (F7, Q5 a): a brick hall on the left whose door looks onto a fountain
// square, a track field upper right and a court lower right. The hall is the clock-tower main hall
// (Q6, kept from Lv.4), mirrored so its entrance faces the square.
const lv8Main = at('main-clocktower', P(114, 228), { flip: true });
const lv8Door = faceMid(lv8Main, 'right');
const lv8Plaza = iso(lv8Door, 58, 0);
const lv8Track = P(286, 136);
// Student hall (user request 2026-11-09) behind the square. Turn its walk toward the square sooner
// so the large court can sit above the T5 stats sign without the north walk crossing it.
const lv8Hall = at('student-hall', P(196, 166));
const lv8HallDoor = faceMid(lv8Hall, 'right');
const lv8Junction = iso(lv8HallDoor, 30);
const lv8NorthJoin = iso(lv8Junction, 0, 64);
// Keep the approved 38×20 court and right-edge crop; lift it 52 world px to clear the stats sign
// even in short T2 frames (aspect 0.8 through CAMPUS_TALL_ASPECT).
const lv8Court = P(277, 214.5);
// ---- Lv.9: dormitories in the woods ---------------------------------------------------------------
// Growth-sheet Lv.9 layout (F8, Q5 a): the main hall in the middle, a dormitory on each side, a square
// ringed by flowerbeds in front of the hall. All three entrances face lower left, so one long street
// runs along U below them and each entrance drops onto it along V. Dormitory A peeks in at the lower
// right as on the growth sheet.
const lv9Main = at('main-clocktower', P(180, 202));
const lv9DormB = at('dorm-b', P(83, 171.5));
const lv9DormA = at('dorm-a', P(304, 289), { scale: 1.75 });
/** The street is the U line where y - x / 2 equals this. */
const LV9_STREET = 140;
const lv9ToStreet = (p: Point): Point => iso(p, 0, LV9_STREET - (p[1] - p[0] / 2));
const lv9MainDoor = faceMid(lv9Main, 'left');
const lv9DormADoor = faceMid(lv9DormA, 'left');
const lv9Plaza = lv9ToStreet(lv9MainDoor);
// ---- Lv.10: one L-shaped grand hall around the festival square ------------------------------------
// User request (2026-11-10): from Lv.10 the campus is one continuous L-shaped (ㄱ) building, like the
// growth sheet's Lv.10 panel. Its courtyard opens toward the viewer; the main door is the inside corner.
const lv10Hall = at('grand-hall', P(181, 222));
const lv10Fp = footprintOf(lv10Hall)!;
const lv10Door = lv10Fp.inner!;
const lv10Court = mid(lv10Door, lv10Fp.front);
// The festival square fills the courtyard and runs past the open corner toward the viewer.
const lv10Plaza = iso(lv10Court, 8, 8);
// Courtyard props stand in front of the wings' inner walls, so the hall sorts at its inside corner.
const lv10HallPlaced = { ...lv10Hall, depth: lv10Door[1] };
const lv10Fountain = at('fountain', iso(lv10Plaza, 4, 4));
const lv10FountainCores = footprintCores(footprintOf(lv10Fountain)!);

export const CAMPUS_LAYOUTS: Readonly<Partial<Record<number, CampusLayout>>> = {
  1: {
    camera: camera(2),
    clouds: [at('cloud-3', P(180, 40)), at('cloud-2', P(296, 40))],
    ground: 'tex-grass-1',
    terrain: [],
    items: [
      at('signboard', P(158, 188)),
      lv1Tent,
      // Founders' camp in front of the tent: a fire, seats, two students and the dog.
      lv1Fire,
      at('log-bench', P(164, 218)), at('stump', P(192, 214)),
      at('student-sit-1', P(166, 211), { shadow: false }), at('student-sit-2', P(190, 208), { flip: true, shadow: false }),
      at('dog', P(204, 218), { flip: true }),
      // Supplies stacked beside the tent.
      at('crates', P(228, 184)), at('lumber', P(238, 200), { flip: true }),
      ...lv1Lot.map((p, i) => at('survey-stake', p, { flip: i % 2 === 1 })),
      at('sapling', P(110, 186)), at('sapling', P(250, 138), { flip: true }),
      ...scatter([P(104, 160), P(232, 222), P(214, 124), P(130, 216), P(256, 166), P(104, 118), P(262, 230)]),
    ],
    glows: [{ x: lv1Tent.x - 6, y: lv1Tent.y - 8, r: 4 }, { x: lv1Fire.x, y: lv1Fire.y - 6, r: 5 }],
    features: { '빈 풀밭 부지': 'tex-grass-1', '나무 푯말': 'signboard', '임시 본부 텐트': 'tent' },
  },
  2: {
    camera: camera(2),
    clouds: [at('cloud-1', P(178, 40)), at('cloud-3', P(316, 42))],
    ground: 'tex-grass-1',
    terrain: [dirt(isoRect(lv2Yard, 38), true), dirt(isoStrip(lv2Walk(34), lv2Walk(110), 8))],
    items: [
      lv2Hall,
      at('sapling', P(136, 170)), at('sapling', P(156, 190)), at('sapling', P(206, 190), { flip: true }), at('sapling', P(228, 170)),
      at('watering-can', P(163, 194)),
      // The Lv.1 signboard now names the school at the yard entrance, under a lantern (plan D2).
      at('signboard', lv2Walk(40, -16)), at('lantern-post', lv2Walk(36, 14)),
      at('school-bell', P(128, 158)),
      // Building leftovers in the yard corner hint at the Lv.3 extension.
      at('wheelbarrow', P(236, 182), { flip: true }), at('shovel', P(246, 182), { shadow: false }), at('lumber', P(222, 196)),
      at('student-walk-1', lv2Walk(70), { shadow: false }), at('student-walk-3', lv2Walk(46, 4), { flip: true, shadow: false }),
      at('student-walk-2', P(200, 178), { shadow: false }), at('student-walk-4', P(176, 188), { flip: true, shadow: false }),
      at('tree-oak-1', P(254, 132)), at('tree-oak-2', P(104, 122), { flip: true }),
      ...scatter([P(112, 206), P(250, 214), P(260, 186), P(122, 140), P(222, 120), P(140, 120)], 1),
    ],
    features: { '단층 벽돌 건물': 'hall-small', '흙길': 'tex-dirt', '어린 묘목': 'sapling' },
  },
  3: {
    camera: camera(1.8),
    clouds: [at('cloud-4', P(180, 40)), at('cloud-2', P(310, 40))],
    ground: 'tex-grass-1',
    terrain: [
      dirt(isoStrip(faceMid(lv3Hall, 'left'), lv3Front, 8)),
      dirt(isoStrip(iso(lv3Front, -110), iso(lv3Front, 12), 10)),
      dirt(isoStrip(lv3Front, iso(lv3Front, 0, 100), 10)),
      dirt(isoStrip(iso(lv3Front, 40, 0), iso(iso(lv3Front, 40, 0), 0, 80), 10)),
      dirt(isoStrip(lv3Front, iso(lv3Front, 50), 10)),
    ],
    items: [
      lv3Hall,
      at('tree-oak-1', P(56, 146)), at('tree-oak-2', P(262, 118)), at('tree-oak-1', P(276, 150), { flip: true }), at('tree-oak-2', P(120, 108)),
      at('tree-oak-1', P(234, 234)), at('tree-oak-2', P(102, 222), { flip: true }), at('tree-oak-1', P(272, 206)), at('tree-oak-1', P(162, 250)),
      at('hedge', iso(faceMid(lv3Hall, 'left'), -18, 0), { flip: true }), at('hedge', iso(faceMid(lv3Hall, 'left'), 18, 0), { flip: true }),
      at('sapling', P(150, 216)), at('sapling', P(256, 182), { flip: true }),
      // Wooden lanterns, log benches and a notice board line the dirt paths (plan D4: no iron lamps yet).
      at('notice-board', lv3At(-14, 14)),
      at('lantern-post', lv3At(14, 30)), at('lantern-post', lv3At(24, 14)), at('lantern-post', lv3At(54, 46)),
      at('log-bench', lv3At(-30, 16)), at('log-bench', lv3At(28, 58), { flip: true }),
      at('student-sit-1', lv3At(-30, 18), { shadow: false }),
      // Woods life: a birdhouse, a stump, a picnic and a bicycle.
      at('birdhouse', P(200, 232)), at('stump', P(252, 222)), at('picnic-mat', lv3At(72, 30)), at('student-sit-2', lv3At(74, 31), { shadow: false }),
      at('bicycle', lv3At(64, 14)),
      at('flowers-1', iso(faceMid(lv3Hall, 'left'), -30, 4)),
      ...[lv3At(0, 40), lv3At(2, 82), lv3At(-60, 2), lv3At(-44, 0), lv3At(40, 30), lv3At(38, 62), lv3At(30, 0)]
        .map((p, i) => at(WALKERS[i % 4], p, { flip: i % 2 === 1, shadow: false })),
      ...scatter([P(70, 170), P(196, 222), P(150, 226), P(250, 160), P(212, 112)], 2),
    ],
    features: { '확장된 교사': 'hall-extended', '다져진 흙길': 'tex-dirt', '자라나는 나무': 'tree-oak-1' },
  },
  4: {
    camera: camera(1.6),
    clouds: [at('cloud-1', P(178, 40)), at('cloud-3', P(318, 42))],
    ground: 'tex-grass-1',
    terrain: [
      { texture: 'tex-water', points: pond(lv4Pond[0], lv4Pond[1], 30, 12), edgeColor: WATER_EDGE },
      // One cross of paths that leaves the frame on both sides, so it reads as one network (F2).
      paving(isoStrip(lv4Door, iso(lv4Door, 0, 120), 9)),
      paving(isoStrip(iso(lv4Door, -80, 26), iso(lv4Door, 160, 26), 9)),
      paving(isoRect(lv4Door, 14, 12)),
    ],
    items: [
      lv4Main,
      at('tree-oak-1', P(86, 150)), at('tree-pine', P(272, 128)), at('tree-oak-2', P(282, 176)), at('tree-oak-1', P(100, 118), { flip: true }),
      at('tree-oak-2', P(276, 236)),
      // Pond life stays on the grass around the pond, clear of the paving (F3).
      at('reeds', iso(lv4Pond, -26, 2), { shadow: false }), at('reeds', iso(lv4Pond, 22, -10), { shadow: false, flip: true }), at('lily-pad', iso(lv4Pond, -2, 2), { shadow: false }),
      at('duck', iso(lv4Pond, 10, -2), { shadow: false }), at('rock', P(lv4Pond[0] + 36, lv4Pond[1] + 6)), at('rock', P(lv4Pond[0] - 34, lv4Pond[1] + 10)),
      // In front of the main hall's left wing, not under it (user request 2026-11-09).
      at('hedge', iso(faceMid(lv4Main, 'left'), -30, 8), { flip: true, depth: lv4Main.y + 1 }),
      at('hedge', iso(faceMid(lv4Main, 'left'), 36, 8), { flip: true, depth: lv4Main.y + 1 }),
      at('hedge', iso(faceMid(lv4Main, 'right'), 8, 0), { depth: lv4Main.y + 1 }), at('bush-1', P(250, 212)), at('bush-2', P(232, 230)),
      ...scatter([P(80, 196), P(110, 248), P(286, 220), P(74, 150), P(214, 266)], 3),
      // Garden fill (F11, UF): a white picket fence closes the pond lawn off the cross path, and a
      // flower border lines the entrance path. Long props run along V unflipped and along U flipped.
      ...[44, 70].map((u) => at('fence-picket', iso(lv4Door, u, 52), { flip: true })),
      ...[56, 80].map((v) => at('flower-border', iso(lv4Door, 27, v))),
    ],
    features: { '본관 시계탑': 'main-clocktower', '돌 보도블록': 'tex-paving', '자연 연못': 'tex-water' },
  },
  5: {
    camera: camera(1.6),
    clouds: [at('cloud-2', P(176, 38)), at('cloud-4', P(300, 42))],
    ground: 'tex-grass-1',
    terrain: [
      paving(isoStrip(faceMid(lv5Main, 'left'), lv5Plaza, 9)),
      paving(isoStrip(faceMid(lv5Library, 'right'), lv5Plaza, 9)),
      paving(isoStrip(lv5Plaza, iso(lv5Plaza, 120), 9)),
      paving(isoRect(lv5Plaza, 22, 18)),
    ],
    items: [
      lv5Main, lv5Library,
      at('tree-cherry-2', P(30, 236)), at('tree-cherry-1', P(170, 120)), at('tree-oak-2', P(296, 150)), at('tree-cherry-1', P(290, 214)),
      at('tree-cherry-2', P(250, 262), { flip: true }), at('tree-cherry-1', P(96, 262), { flip: true }), at('tree-cherry-2', P(20, 150)),
      at('bench', iso(lv5Plaza, 0, 30)), at('bench', iso(lv5Plaza, 30, -26), { flip: true }),
      at('flowerbed-2', iso(lv5Plaza, 34, 30)), at('flowers-2', iso(lv5Plaza, -10, 32)), at('flowers-2', P(254, 222), { flip: true }),
      at('lamp', iso(lv5Plaza, -26, -22)), at('lamp', iso(lv5Plaza, 26, 22)),
      ...crowd(lv5Plaza, 20, 16, 5, 2),
      ...scatter([P(230, 270), P(160, 272), P(286, 186)], 4),
      // Garden fill (F11, UF): planters along the hall front, a picket fence along the garden walk.
      ...[-26, 26].map((u) => at('planter-strip', iso(faceMid(lv5Main, 'left'), u, 6), { flip: true, depth: lv5Main.y + 1 })),
      ...[60, 86].map((u) => at('fence-picket', iso(lv5Plaza, u, 27), { flip: true })),
    ],
    features: { '중앙도서관': 'library-r', '벚꽃 정원': 'tree-cherry-1', '벤치': 'bench' },
  },
  6: {
    camera: camera(1.6),
    clouds: [at('cloud-1', P(180, 40)), at('cloud-3', P(300, 42))],
    ground: 'tex-grass-1',
    terrain: [
      paving(isoStrip(faceMid(lv6Lecture, 'right'), lv6Plaza, 9)),
      paving(isoStrip(faceMid(lv6Main, 'left'), lv6Plaza, 9)),
      paving(isoStrip(lv6Plaza, iso(lv6Plaza, 0, 120), 9)),
      paving(isoStrip(lv6Plaza, iso(lv6Plaza, 120), 9)),
      paving(isoRect(lv6Plaza, 26, 22)),
    ],
    items: [
      lv6Lecture, lv6Main,
      ...[P(80, 118), P(122, 112), P(164, 118), P(206, 112), P(240, 120)].map((p, i) => at((['tree-maple-orange', 'tree-cherry-1', 'tree-oak-2', 'tree-pine', 'tree-cherry-2'] as const)[i], p, { flip: i % 2 === 1 })),
      // One lamp per spot: two at the plaza corners, then a row down the south path (F5).
      at('lamp', iso(lv6Plaza, 30, -26)), at('lamp', iso(lv6Plaza, -30, 26)),
      ...along(iso(lv6Plaza, 0, 50), iso(lv6Plaza, 0, 110), 3).map((p) => at('lamp', iso(p, 14))),
      at('flowerbed-1', iso(lv6Plaza, 44, 44)), at('flowerbed-2', iso(lv6Plaza, -42, 42)), at('flowerbed-1', iso(lv6Plaza, 34, -40)),
      at('bench', iso(lv6Plaza, 40, 18)), at('bush-2', P(214, 264)), at('tree-cherry-1', P(70, 250)), at('tree-maple-red', P(276, 276)),
      ...crowd(lv6Plaza, 24, 20, 6, 3), ...crowd(iso(lv6Plaza, 0, 60), 6, 30, 3, 4),
      ...scatter([P(110, 280), P(310, 236), P(60, 200)], 5),
      // Garden fill (F11, UF): low hedges edge the plaza walks, planters line the hall front.
      ...[66, 94].map((u) => at('hedge-low', iso(lv6Plaza, u, 27), { flip: true })),
      ...[64, 92].map((v) => at('hedge-low', iso(lv6Plaza, -15, v))),
      ...[-26, 26].map((u) => at('planter-strip', iso(faceMid(lv6Main, 'left'), u, 6), { flip: true, depth: lv6Main.y + 1 })),
    ],
    features: { '제2강의동': 'lecture-2-r', '빈티지 가로등': 'lamp', '화단': 'flowerbed-1' },
  },
  7: {
    camera: camera(1.35),
    clouds: [at('cloud-2', P(176, 38)), at('cloud-3', P(320, 42))],
    ground: 'tex-grass-1',
    terrain: [
      paving(isoStrip(lv7Avenue[0], lv7Avenue[1], 11)),
      paving(isoStrip(faceMid(lv7Lecture, 'right'), iso(faceMid(lv7Lecture, 'right'), 24), 9)),
      paving(isoStrip(lv7Plaza, iso(lv7Plaza, 170), 9)),
      paving(isoRect(lv7Plaza, 30, 22)),
      paving(isoStrip(lv7HallDoor, lv7HallStub, 8)),
      paving(isoStrip(lv7South, lv7HallStub, 9)),
    ],
    items: [
      lv7Stadium, lv7Lecture, lv7Hall,
      // Front row (lower right of the avenue): canopies hang over the path, so they are see-through.
      ...[80, 160, 190].map((v, i) => at(i % 2 ? 'tree-cherry-1' : 'tree-cherry-2', iso(lv7Gate, 30, v), { flip: i % 2 === 1, opacity: 0.6 })),
      ...along(iso(lv7Gate, 0, 150), lv7Avenue[1], 2).map((p, i) => at(i % 2 ? 'tree-cherry-2' : 'tree-cherry-1', iso(p, -26), { flip: i % 2 === 0 })),
      at('tree-maple-orange', P(150, 96 + 20)), at('tree-oak-2', P(64, 128)),
      at('flag', iso(lv7Plaza, 20, -10)), at('bench', iso(lv7Plaza, 44, -20)),
      ...crowd(lv7Plaza, 26, 18, 6, 4), ...crowd(iso(lv7Gate, 0, 110), 4, 40, 4, 5),
      at('tree-oak-1', P(296, 272)), at('tree-maple-red', P(150, 292), { flip: true }), at('bench', iso(lv7Gate, 26, 100)),
      ...along(iso(lv7Plaza, 50), iso(lv7Plaza, 130), 3).map((p) => at('lamp', iso(p, 0, -14))),
      ...scatter([P(60, 270), P(206, 296)], 6),
      // Garden fill (F11). Long props run along V unflipped and along U flipped.
      ...[-22, 22].map((u) => at('planter-strip', iso(lv7HallDoor, u, 6), { flip: true, depth: lv7Hall.y + 1 })),
      ...[70, 110].map((u) => at('fence-iron', iso(lv7Plaza, u, -13), { flip: true })),
      ...[112, 134].map((v) => at('hedge-low', iso(lv7Gate, -22, v))),
      ...[40, 66].map((u) => at('flower-border', iso(lv7South, u, -14), { flip: true })),
    ],
    features: { '종합 경기장': 'stadium', '가로수길': 'tree-cherry-2', '캠퍼스 광장': 'tex-paving' },
  },
  8: {
    camera: camera(1.35),
    clouds: [at('cloud-4', P(180, 40)), at('cloud-2', P(306, 40))],
    ground: 'tex-grass-1',
    terrain: [
      { texture: 'tex-track', points: isoRect(lv8Track, 44, 24), edgeColor: TRACK_EDGE },
      { texture: 'tex-grass-2', points: isoRect(lv8Track, 32, 12), edgeColor: TRACK_EDGE },
      { texture: 'tex-court', points: isoRect(lv8Court, 38, 20), edgeColor: COURT_EDGE },
      paving(isoStrip(lv8Door, iso(lv8Plaza, -30), 9)),
      paving(isoStrip(lv8NorthJoin, lv8Junction, 9)),
      paving(isoStrip(lv8HallDoor, lv8Junction, 9)),
      paving(isoStrip(iso(lv8Plaza, 0, 30), iso(lv8Plaza, 0, 130), 10)),
      paving(isoRect(lv8Plaza, 34, 30), true),
    ],
    items: [
      lv8Main, lv8Hall,
      at('fountain', lv8Plaza),
      ...[[-30, -26], [30, -26], [-30, 26], [30, 26]].map(([u, v]) => at('lamp-banner', iso(lv8Plaza, u, v))),
      // Banner lamps and benches down both sides of the south walk (growth sheet).
      ...along(iso(lv8Plaza, 0, 56), iso(lv8Plaza, 0, 112), 3).flatMap((p) => [at('lamp-banner', iso(p, -16)), at('lamp-banner', iso(p, 16))]),
      at('bench', iso(lv8Plaza, -20, 84), { flip: true }), at('bench', iso(lv8Plaza, 20, 70)),
      // One goal on the near end line (the sprite opens toward +U only; user request 2026-11-09); the
      // bleacher's rows run along the court's long side, facing it.
      at('goal-post', iso(lv8Court, -35), { shadow: false }),
      at('bleacher', iso(lv8Court, -34, -30), { flip: true }),
      // Garden fill (F11): planters at the square's corners and along the hall's entrance wall, low
      // hedges along the walk to the track.
      ...[[-44, -40], [44, 40]].map(([u, v]) => at('planter-square', iso(lv8Plaza, u, v))),
      at('hedge-low', iso(lv8Plaza, -6, -32)),
      ...[-24, 24].map((v) => at('planter-strip', iso(lv8Door, 6, v), { depth: lv8Main.y + 1 })),
      ...[P(60, 112), P(196, 110), P(232, 104), P(306, 104)].map((p, i) => at((['tree-oak-2', 'tree-cherry-1', 'tree-pine', 'tree-oak-1'] as const)[i], p, { flip: i % 2 === 0 })),
      at('tree-cherry-2', P(56, 250)), at('tree-pine', P(306, 196)), at('bench', iso(lv8Plaza, 58, 30), { flip: true }),
      ...crowd(lv8Plaza, 30, 26, 8, 5), ...crowd(iso(lv8Plaza, 0, 80), 6, 40, 3, 6),
      ...scatter([P(150, 292), P(240, 296), P(90, 160)], 7),
    ],
    features: { '분수광장': 'fountain', '스포츠 코트': 'tex-court', '학교 배너': 'lamp-banner' },
  },
  9: {
    camera: camera(1.35),
    clouds: [at('cloud-1', P(178, 40)), at('cloud-2', P(310, 40))],
    ground: 'tex-grass-1',
    terrain: [
      paving(isoStrip(P(20, 150), P(340, 310), 10)),
      ...[lv9MainDoor, faceMid(lv9DormB, 'left'), lv9DormADoor].map((d) => paving(isoStrip(d, lv9ToStreet(d), 9))),
      paving(isoRect(lv9Plaza, 26, 20)),
    ],
    items: [
      lv9Main, lv9DormB, lv9DormA,
      // Flowerbeds ring the square; planters line the hall front (growth sheet, F11).
      at('flowerbed-1', iso(lv9Plaza, -40, 30)), at('flowerbed-2', iso(lv9Plaza, 40, 30)),
      ...[-24, 24].map((u) => at('flower-border', iso(lv9Plaza, u, 34), { flip: true })),
      ...[-26, 26].map((u) => at('planter-strip', iso(lv9MainDoor, u, 6), { flip: true, depth: lv9Main.y + 1 })),
      // Lamps and benches along the street.
      ...along(iso(lv9Plaza, 44), iso(lv9Plaza, 110), 3).map((p) => at('lamp', iso(p, 0, -16))),
      at('bench', iso(lv9Plaza, 70, 18)), at('bench', iso(lv9Plaza, 104, 20), { flip: true }),
      // The lawn below the street: picnics, pets and students.
      at('picnic-mat', iso(lv9Plaza, 30, 60), { shadow: false }), at('picnic-mat', iso(lv9Plaza, 96, 50), { shadow: false }),
      at('dog', iso(lv9Plaza, 10, 70), { shadow: false }), at('cat', iso(lv9Plaza, 110, 42), { shadow: false }),
      // Dorm life (user request U9-fill): the dorm walls sit on the street, so bike racks stand on the lawn
      // across from each dorm door; bins by the benches, students sitting out.
      at('bike-rack', iso(lv9DormADoor, -14, 44)), at('bicycle', iso(lv9DormADoor, 2, 46), { flip: true }),
      at('bicycle', iso(lv9DormADoor, -30, 46)), at('bicycle', iso(faceMid(lv9DormB, 'left'), 52, 40), { flip: true }),
      ...[87, 120].map((u) => at('trash-bin', iso(lv9Plaza, u, 19))),
      at('student-sit-1', iso(lv9Plaza, 70, 17), { shadow: false }), at('student-sit-2', iso(lv9Plaza, 28, 58), { shadow: false }),
      at('student-sit-1', iso(lv9Plaza, 34, 62), { shadow: false }), at('student-sit-2', iso(lv9Plaza, 98, 48), { shadow: false }),
      // Planters on the lawn between the hall and the big dorm; flowers on the lower lawn.
      ...[P(214, 196), P(232, 206)].map((p) => at('planter-square', p)),
      ...scatter([iso(lv9Plaza, 70, 76), iso(lv9Plaza, 92, 80)], 1),
      // Woods: a dense row behind the buildings and trees along the bottom.
      ...[P(52, 112), P(150, 104), P(250, 106), P(286, 118), P(316, 128)].map((p, i) => at((['tree-pine', 'tree-cherry-1', 'tree-pine', 'tree-oak-1', 'tree-maple-orange'] as const)[i], p, { flip: i % 2 === 1 })),
      ...[P(270, 176), P(304, 190)].map((p, i) => at(i ? 'tree-cherry-2' : 'tree-oak-2', p)),
      at('tree-cherry-2', P(60, 218)), at('tree-oak-1', P(184, 306), { flip: true }),
      ...crowd(lv9Plaza, 22, 16, 10, 6), ...crowd(iso(lv9Plaza, 70), 40, 6, 7, 7), ...crowd(iso(lv9Plaza, 50, 56), 30, 12, 7, 8),
      ...scatter([P(70, 236), P(236, 214), P(110, 280)], 8),
    ],
    features: { '학생 기숙사': 'dorm-b', '녹음 숲': 'tree-pine', '학생 인파': 'student-walk-1' },
  },
  10: {
    camera: camera(1.35),
    clouds: [at('cloud-3', P(320, 42))],
    ground: 'tex-grass-1',
    terrain: [
      // From the main door (the square's back corner) out past the open front corner. Base: the
      // fountain stands on it, as on the Lv.8 square.
      paving(isoRect(lv10Plaza, 48, 48), true),
      // Walks from the open sides out to the lower left and lower right (user request 2026-11-10).
      paving(isoStrip(iso(lv10Plaza, -10, 44), iso(lv10Plaza, -10, 124), 10)),
      paving(isoStrip(iso(lv10Plaza, 44, -10), iso(lv10Plaza, 124, -10), 10)),
    ],
    items: [
      lv10HallPlaced,
      // Banner lamps along the wings and the open sides; flags on the lawn at the front corner; flower
      // borders and flowerbeds trim the open sides like the gold-trimmed square of the growth sheet (F9, F11).
      ...[[-42, -20], [-42, 12], [-20, -42], [12, -42], [42, 4], [4, 42]].map(([u, v]) => at('lamp-banner', iso(lv10Plaza, u, v))),
      ...[-36, 22].map((u) => at('flower-border', iso(lv10Plaza, u, 64), { flip: true })),
      ...[-36, 22].map((v) => at('flower-border', iso(lv10Plaza, 64, v))),
      // A fountain in the middle of the square; trees behind the wings, along the walks and on the front lawn.
      lv10Fountain,
      ...[P(58, 158), P(302, 156)].map((p, i) => at(i ? 'tree-oak-2' : 'tree-oak-1', p, { flip: i === 1 })),
      ...[iso(lv10Plaza, -44, 78), iso(lv10Plaza, 26, 94)].map((p, i) => at(i ? 'tree-maple-orange' : 'tree-cherry-2', p)),
      ...[iso(lv10Plaza, 78, -44), iso(lv10Plaza, 94, 26)].map((p, i) => at(i ? 'tree-maple-red' : 'tree-cherry-1', p, { flip: true })),
      at('tree-oak-1', iso(lv10Plaza, 84, 84)),
      at('flag', iso(lv10Plaza, 56, 38)), at('flag', iso(lv10Plaza, 38, 56)),
      at('flowerbed-1', iso(lv10Plaza, 66, -38)), at('flowerbed-2', iso(lv10Plaza, -38, 66)),
      // The crowd fills the square around the fountain.
      ...crowd(lv10Plaza, 42, 42, 48, 9).filter((s) => !lv10FountainCores.some((c) => pointInPolygon([s.x, s.y], c))),
    ],
    effects: [
      { kind: 'festival-glow', x: lv10Plaza[0], y: lv10Plaza[1], rx: 92, ry: 46 },
      // Big enough to read against the day sky (user request 2026-11-08); frame units, sky band 0-96.
      { kind: 'fireworks', bursts: [{ x: 162, y: 44, r: 28, color: '#ff4f8b' }, { x: 264, y: 50, r: 26, color: '#3fa9ff' }, { x: 92, y: 66, r: 22, color: '#ffd23f' }, { x: 330, y: 70, r: 20, color: '#5fe36a' }, { x: 212, y: 80, r: 16, color: '#ff8a3d' }] },
    ],
    features: { '트윈 시계탑': 'grand-hall', '축제 광장': 'tex-paving', '불꽃놀이': 'effect:fireworks' },
  },
};

// ---- Onboarding title scene (docs/261110-0837-onboarding_title_screen_plan.md 4) -------------------
// A square frame with a tall sky for the text logo (sky 140 of 360 frame units), the clock-tower hall
// in the middle and a path running from its door toward the viewer, where new students walk in.
// Not a level: validated with validateSceneLayout and drawn with CampusLayoutScene.
const titleMain = at('main-clocktower', P(206, 226));
const titlePlaza = iso(faceMid(titleMain, 'left'), 0, 12);
/**
 * Where the text logo covers the title sky, as [left, top, right, bottom] in frame units: measured with
 * Playwright at 360, 430 and 1280 px viewports after fonts load (95-265 x 22-110), rounded outward.
 * The sun and clouds stay out of it.
 */
export const TITLE_LOGO_BOX = [90, 0, 270, 112] as const;
export const TITLE_LAYOUT: CampusLayout = {
  camera: { cx: 180, horizon: HORIZON, zoom: 1.15, skyHeight: 140 },
  sun: { x: 322, y: 56 },
  clouds: [at('cloud-1', P(42, 74)), at('cloud-4', P(296, 82)), at('cloud-3', P(332, 64))],
  ground: 'tex-grass-1',
  terrain: [
    paving(isoStrip(titlePlaza, iso(titlePlaza, 0, 160), 9)),
    paving(isoStrip(iso(titlePlaza, -120, 0), iso(titlePlaza, 140, 0), 8)),
    paving(isoRect(titlePlaza, 16, 12)),
  ],
  items: [
    titleMain,
    at('tree-cherry-1', P(78, 186)), at('tree-cherry-2', P(104, 136), { flip: true }), at('tree-cherry-1', P(312, 168), { flip: true }),
    at('tree-cherry-2', P(330, 214)), at('tree-oak-1', P(40, 210)), at('tree-pine', P(292, 124)),
    at('lamp', iso(titlePlaza, -24, 30)), at('lamp', iso(titlePlaza, 24, 30)),
    at('flowerbed-1', iso(titlePlaza, -34, 16)), at('flowerbed-2', iso(titlePlaza, 34, 50)),
    // Flowers line the entrance walk; long props run along V unflipped.
    ...[44, 76].map((v) => at('flower-border', iso(titlePlaza, -22, v))),
    // A picnic on the lawn between the walks fills the front of the scene.
    at('picnic-mat', iso(titlePlaza, 60, 44), { depth: titlePlaza[1] + 40 }), at('student-sit-1', iso(titlePlaza, 56, 46), { shadow: false }), at('student-sit-2', iso(titlePlaza, 64, 48), { flip: true, shadow: false }),
    ...scatter([P(262, 268), P(290, 240), P(236, 288), P(52, 238)], 1),
    at('bench', iso(titlePlaza, 30, 84)), at('hedge-low', P(28, 244)), at('hedge-low', P(330, 250), { flip: true }),
    at('student-walk-1', iso(titlePlaza, 0, 56)), at('student-walk-2', iso(titlePlaza, 2, 70), { flip: true }),
    at('student-walk-3', iso(titlePlaza, -3, 92)), at('student-walk-4', iso(titlePlaza, 3, 104), { flip: true }),
    at('dog', iso(titlePlaza, 4, 84), { shadow: false }),
    at('student-walk-2', iso(titlePlaza, 74, 2)), at('student-walk-1', iso(titlePlaza, -64, -2), { flip: true }),
  ],
  features: {},
};

/**
 * Terrain pieces grouped by texture, in first-appearance order (so a lawn inside a track still draws
 * over it). The scene draws a group's outlines before its fills, which hides seams where pieces join.
 */
export function terrainGroups(terrain: readonly CampusTerrain[]): { texture: CampusAssetId; pieces: CampusTerrain[] }[] {
  const groups = new Map<CampusAssetId, CampusTerrain[]>();
  for (const t of terrain) groups.set(t.texture, [...(groups.get(t.texture) ?? []), t]);
  return [...groups].map(([texture, pieces]) => ({ texture, pieces }));
}

export function getCampusLayout(level: number): CampusLayout | undefined {
  return CAMPUS_LAYOUTS[level];
}

/** SVG transform that places an asset's anchor on (x, y), mirrored around x when flipped. */
export function placementTransform(item: Pick<CampusPlacement, 'asset' | 'x' | 'y' | 'flip' | 'scale'>): string {
  const [ax, ay] = CAMPUS_ASSETS[item.asset].anchor;
  if (item.scale !== undefined && item.scale !== 1) {
    return `translate(${item.x} ${item.y}) scale(${item.flip ? -item.scale : item.scale} ${item.scale}) translate(${-ax} ${-ay})`;
  }
  const place = `translate(${item.x - ax} ${item.y - ay})`;
  return item.flip ? `translate(${2 * item.x} 0) scale(-1 1) ${place}` : place;
}

/** Draw order: farther (smaller depth, else anchor y) first; ties broken by x, then input order. */
export function sortPlacements<T extends Pick<CampusPlacement, 'x' | 'y' | 'depth'>>(items: readonly T[]): T[] {
  const key = (item: T) => item.depth ?? item.y;
  return items.map((item, index) => ({ item, index }))
    .sort((a, b) => key(a.item) - key(b.item) || a.item.x - b.item.x || a.index - b.index)
    .map(({ item }) => item);
}

/**
 * Window-lit sprites in draw order, each with the later-drawn sprites whose boxes overlap it. The night
 * light layer sits above the whole scene, so it masks these out to keep lights off a hedge or tree in front.
 */
export function lightOccluders(items: readonly CampusPlacement[]): { item: CampusPlacement; occluders: CampusPlacement[] }[] {
  const sorted = sortPlacements(items);
  const overlaps = (a: readonly number[], b: readonly number[]) => a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
  return sorted.flatMap((item, i) => {
    if (!CAMPUS_ASSETS[item.asset].lightsUrl) return [];
    const box = spriteBox(item);
    return [{ item, occluders: sorted.slice(i + 1).filter((o) => overlaps(box, spriteBox(o))) }];
  });
}

const polygonBox = (points: readonly Point[]): [number, number, number, number] => {
  const xs = points.map((p) => p[0]);
  const ys = points.map((p) => p[1]);
  return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)];
};

/** Walkable ground: paths, plazas and yards. Water, courts and tracks are not part of the network. */
const PATH_TEXTURES = new Set<CampusAssetId>(['tex-paving', 'tex-dirt']);
/** Lowest cloud bottom (frame units) that still clears the far forest, whose sprite top is y 39 (measured). */
export const CLOUD_LOWEST = 42;
/** World px two path pieces may be apart and still read as one joined path (rounding slack). */
export const PATH_JOIN_GAP = 1;
/** World px an entrance (faceMid of its door face) may be from the nearest path. */
export const DOOR_REACH = 4;
/** World px of grass required between a pond and any path. */
export const WATER_PATH_GAP = 4;
/** World px two lamps' anchors must be apart; closer pairs read as one doubled lamp (F5, Lv.6). */
export const LAMP_GAP = 16;
/** Props that must not stand on a walkway (base yards excepted): plants, rocks, benches and beds. */
const OFF_PATH_PROP = /^(tree-|bush-|hedge|rock|flowers-|flowerbed-|flower-border|planter-|fence-|reeds|lily-pad|sapling|bench|picnic-mat|puddle|trash-bin|bike-rack|campfire|crates|lumber|wheelbarrow|survey-stake|watering-can|lantern-post|notice-board|log-bench|school-bell|birdhouse|stump)/;
/** Long G2 props drawn as a straight section along one ground axis; their base is a diagonal, not the anchor row. */
const DIAGONAL_PROP = /^(fence-|hedge-low|planter-strip|flower-border)/;

/**
 * Lamp sprites that glow at night, with the light's offset from the sprite's top-center as
 * [dx, dy] in sprite px (unflipped). Street lamps light at the top; the wooden lantern post's
 * lantern hangs from an arm to the right (measured on lantern-post.png: yellow glass at 5-6, 9-10).
 */
export const LAMP_LIGHTS: Readonly<Partial<Record<CampusAssetId, readonly [number, number]>>> = {
  lamp: [0, 3], 'lamp-banner': [0, 3], 'lantern-post': [1.5, 9.5],
};

/** Night glow points (world px) of the lamps among the items, mirrored with flipped sprites. */
export function lampGlows(items: readonly CampusPlacement[]): { asset: CampusAssetId; x: number; y: number }[] {
  return items.flatMap((item) => {
    const light = LAMP_LIGHTS[item.asset];
    if (!light) return [];
    const [dx, dy] = light;
    return [{ asset: item.asset, x: item.x + (item.flip ? -dx : dx), y: item.y - CAMPUS_ASSETS[item.asset].height + dy }];
  });
}

/** Share of a stage-feature sprite that must stay visible (in frame, not under in-game UI). */
export const FEATURE_MIN_VISIBLE = 0.6;
const FEATURE_EXEMPT = new Set<string>(['student-walk-1']);

/**
 * Checks shared by every composed scene (levels and the title scene), pushed onto problems with the
 * given label. Returns the assets in use and the default view, or undefined when the layout is too broken
 * to check further.
 */
function checkSceneLayout(label: string, layout: CampusLayout, problems: string[]): { used: Set<string>; view: CampusView } | undefined {
  const used = new Set<string>([layout.ground, ...layout.terrain.map((t) => t.texture), ...layout.items.map((i) => i.asset),
    ...layout.clouds.map((c) => c.asset), ...(layout.effects?.map((e) => `effect:${e.kind}`) ?? [])]);
  for (const id of used) if (!id.startsWith('effect:') && !isCampusAssetId(id)) problems.push(`${label}: unknown asset ${id}`);
  if (![...used].every((id) =>id.startsWith('effect:') || isCampusAssetId(id))) return undefined;
  if (!(layout.camera.zoom > 0)) { problems.push(`${label}: camera zoom must be positive`); return undefined; }
  const view = campusView(layout.camera);
  const tall = campusView(layout.camera, CAMPUS_TALL_ASPECT);
  const margin = 120 / layout.camera.zoom;
  if (CAMPUS_ASSETS[layout.ground].kind !== 'texture') problems.push(`${label}: ground ${layout.ground} is not a texture`);
  for (const t of layout.terrain) {
    if (CAMPUS_ASSETS[t.texture].kind !== 'texture') problems.push(`${label}: terrain ${t.texture} is not a texture`);
    if (t.points.length < 3) problems.push(`${label}: terrain needs at least 3 points`);
    for (const [x, y] of t.points) {
      if (!Number.isInteger(x) || !Number.isInteger(y)) problems.push(`${label}: terrain point ${x},${y} is not an integer (crisp edges need integer coordinates)`);
      if (x < view.x - margin || x > view.x + view.width + margin || y < layout.camera.horizon - margin || y > tall.y + tall.height + margin) problems.push(`${label}: terrain point ${x},${y} is far outside the camera view`);
    }
  }
  const buildings = layout.items.flatMap((item) => { const f = footprintOf(item); return f ? [{ item, cores: footprintCores(f) }] : []; });
  for (const item of layout.items) {
    if (CAMPUS_ASSETS[item.asset].kind !== 'sprite') { problems.push(`${label}: ${item.asset} is not a sprite`); continue; }
    if (item.opacity !== undefined && !(item.opacity > 0 && item.opacity <= 1)) problems.push(`${label}: ${item.asset} has opacity ${item.opacity}; use a value above 0 and at most 1`);
    if (item.scale !== undefined && (!(item.scale > 0) || !CAMPUS_ASSETS[item.asset].footprint)) problems.push(`${label}: ${item.asset} has scale ${item.scale}; scale only buildings (assets with a footprint), by a positive factor`);
    if (item.y <= layout.camera.horizon) problems.push(`${label}: ${item.asset} anchor ${item.x},${item.y} stands in the sky (horizon ${layout.camera.horizon}); move it below the horizon`);
    else if (item.x < view.x - margin || item.x > view.x + view.width + margin || item.y > tall.y + tall.height + margin) problems.push(`${label}: ${item.asset} anchor ${item.x},${item.y} is outside the camera view`);
    if (!footprintOf(item)) {
      const inside = buildings.find((b) => b.cores.some((core) => pointInPolygon([item.x, item.y], core)));
      if (inside) problems.push(`${label}: ${item.asset} at ${item.x},${item.y} stands inside ${inside.item.asset}; move it off the building footprint`);
    }
  }
  for (let i = 0; i < buildings.length; i++) for (let j = i + 1; j < buildings.length; j++) {
    const [a, b] = [buildings[i], buildings[j]];
    if (a.cores.some((ca) => sampleQuad(ca).some((p) => b.cores.some((cb) => pointInPolygon(p, cb))))) problems.push(`${label}: ${a.item.asset} and ${b.item.asset} footprints overlap; move them apart`);
  }
  for (const t of layout.terrain) {
    if (t.base) continue;
    for (const b of buildings) {
      if (b.cores.some((core) => sampleQuad(core).some((p) => pointInPolygon(p, t.points)))) problems.push(`${label}: ${t.texture} path runs under ${b.item.asset}; start it at the building face (faceMid) or mark the terrain as base`);
    }
  }
  // Lead feedback (docs/261109-1950-campus_lead_feedback_plan.md, U0): one joined path network,
  // a path at every entrance, no clutter or water on the paths.
  const paths = layout.terrain.filter((t) => PATH_TEXTURES.has(t.texture));
  const group = paths.map((_, i) => i);
  const root = (i: number): number => (group[i] === i ? i : (group[i] = root(group[i])));
  for (let i = 0; i < paths.length; i++) for (let j = i + 1; j < paths.length; j++) {
    if (polygonGap(paths[i].points, paths[j].points) <= PATH_JOIN_GAP) group[root(i)] = root(j);
  }
  const groups = new Set(paths.map((_, i) => root(i))).size;
  if (groups > 1) problems.push(`${label}: paths form ${groups} separate groups; join every path and plaza into one network`);
  if (paths.length) for (const b of buildings) {
    const doors = doorPoints(b.item);
    if (doors.length && !doors.some((d) => paths.some((t) => polygonDistance(d, t.points) <= DOOR_REACH))) {
      problems.push(`${label}: ${b.item.asset} entrance at ${doors.map((d) => d.join(',')).join(' or ')} is off the paths; start a path at faceMid of its door face`);
    }
  }
  const walkways = paths.filter((t) => !t.base);
  for (const item of layout.items) {
    if (!OFF_PATH_PROP.test(item.asset)) continue;
    const [l, , rgt] = spriteBox(item);
    const quarter = (rgt - l) / 4;
    const base: Point[] = [[item.x, item.y], [item.x - quarter, item.y], [item.x + quarter, item.y]];
    if (DIAGONAL_PROP.test(item.asset)) {
      // The base runs corner to corner of the sprite box at 2:1, so its middle sits a quarter of the
      // width above the anchor; unflipped it rises to the right, flipped it falls to the right.
      const my = item.y - quarter;
      const rise = item.flip ? quarter / 2 : -quarter / 2;
      base.push([item.x, my], [item.x - quarter, my - rise], [item.x + quarter, my + rise]);
    }
    if (walkways.some((t) => base.some((p) => pointInPolygon(p, t.points)))) problems.push(`${label}: ${item.asset} at ${item.x},${item.y} stands on a path; move it off the path`);
  }
  // Sports surfaces are their own ground: a walk must stop short of a court or track (U8).
  for (const field of layout.terrain.filter((t) => t.texture === 'tex-court' || t.texture === 'tex-track')) {
    if (walkways.some((t) => polygonGap(field.points, t.points) < WATER_PATH_GAP)) problems.push(`${label}: path runs onto the ${field.texture.slice(4)}; keep ${WATER_PATH_GAP}px of grass between them`);
  }
  for (const water of layout.terrain.filter((t) => t.texture === 'tex-water')) {
    if (paths.some((t) => polygonGap(water.points, t.points) < WATER_PATH_GAP)) problems.push(`${label}: pond overlaps or touches a path; keep ${WATER_PATH_GAP}px of grass between them`);
  }
  const lamps = layout.items.filter((i) => i.asset === 'lamp' || i.asset === 'lantern-post');
  lamps.forEach((l, i) => lamps.slice(i + 1).forEach((m) => {
    if (Math.hypot(l.x - m.x, l.y - m.y) < LAMP_GAP) problems.push(`${label}: lamps at ${l.x},${l.y} and ${m.x},${m.y} overlap; keep them ${LAMP_GAP}px apart or remove one`);
  }));
  // The far hills sit at the bottom of the sky band, so a taller sky lowers the cloud limit with them.
  const sky = skyHeightOf(layout.camera);
  const lowest = CLOUD_LOWEST + sky - CAMPUS_SKY_HEIGHT;
  const sun = layout.sun ?? CAMPUS_SUN_POSITION;
  for (const cloud of layout.clouds) {
    if (cloud.y > sky || cloud.x < 0 || cloud.x > CAMPUS_FRAME_WIDTH) problems.push(`${label}: cloud ${cloud.asset} at ${cloud.x},${cloud.y} is outside the sky band (0-${CAMPUS_FRAME_WIDTH} x 0-${sky} frame units)`);
    const [, top, , bottom] = spriteBox(cloud);
    if (cloud.y > lowest) problems.push(`${label}: cloud ${cloud.asset} at ${cloud.x},${cloud.y} sinks behind the far hills; keep its bottom at y ${lowest} or above`);
    if ((top + bottom) / 2 < sun.y) problems.push(`${label}: cloud ${cloud.asset} at ${cloud.x},${cloud.y} sits higher than the sun and moon (y ${sun.y}); move it down so its middle is below them`);
  }
  return { used, view };
}

/** Problems of a composed scene outside the level table (the onboarding title scene); empty when valid. */
export function validateSceneLayout(label: string, layout: CampusLayout): string[] {
  const problems: string[] = [];
  checkSceneLayout(label, layout, problems);
  return problems;
}

/** Returns human-readable problems; an empty list means the layouts are valid. */
export function validateCampusLayouts(layouts: Readonly<Partial<Record<number, CampusLayout>>> = CAMPUS_LAYOUTS): string[] {
  const problems: string[] = [];
  for (const [key, layout] of Object.entries(layouts)) {
    const level = Number(key);
    if (!Number.isInteger(level) || level < 1 || level > 10 || !layout) { problems.push(`unknown level key ${key}`); continue; }
    const checked = checkSceneLayout(`Lv.${level}`, layout, problems);
    if (!checked) continue;
    const { used, view } = checked;
    const stage = CAMPUS_STAGES[level - 1];
    for (const feature of stage.features) {
      const source = layout.features[feature];
      if (!source) { problems.push(`Lv.${level}: feature "${feature}" has no asset`); continue; }
      if (!used.has(source)) { problems.push(`Lv.${level}: feature "${feature}" maps to ${source}, which the layout does not use`); continue; }
      if (source.startsWith('effect:') || FEATURE_EXEMPT.has(source) || source === layout.ground) continue;
      const boxes = CAMPUS_ASSETS[source as CampusAssetId].kind === 'sprite'
        ? layout.items.filter((i) => i.asset === source).map(spriteBox)
        : layout.terrain.filter((t) => t.texture === source).map((t) => polygonBox(t.points));
      const best = Math.max(0, ...boxes.map((box) => visibleFraction(box, view)));
      if (best < FEATURE_MIN_VISIBLE) problems.push(`Lv.${level}: feature "${feature}" (${source}) is only ${Math.round(best * 100)}% visible; keep it in frame and clear of the in-game labels, guestbook and tap hint`);
    }
  }
  return problems;
}
