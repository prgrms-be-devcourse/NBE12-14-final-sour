// Map presentation data, keyed by backend school ID (schoolIds.ts) or data region. Plan: docs/261108-1930-m1_school_map_data_plan.md 4-1, 4-2.
// Every coordinate here is a mockup placement on the pixel map image, not a real location.
// A real service would place schools from the library latitude/longitude in the University table.
import { REAL_UNIVERSITIES, type UniversityItem } from './universitiesData.ts';
import { SCHOOL_ID, type SchoolId } from './schoolIds.ts';
import mapLayers from '../assets/images/map/layers.json';

export type SchoolRegion = UniversityItem['region'];
export type SchoolFilterRegion = UniversityItem['filterRegion'];

export const MAP_REGIONS = ['전체', '수도권', '강원', '충청', '영남', '호남', '제주'] as const;
export type MapRegion = (typeof MAP_REGIONS)[number];

export function isMapRegion(value: string): value is MapRegion {
  return (MAP_REGIONS as readonly string[]).includes(value);
}

/** Data region → filter chip. Replaces string checks such as `includes('충남')`. */
export const REGION_TO_FILTER: Readonly<Record<SchoolRegion, SchoolFilterRegion>> = {
  서울: '수도권',
  인천: '수도권',
  경기: '수도권',
  강원: '강원',
  충북: '충청',
  '충남·대전': '충청',
  전라: '호남',
  경상: '영남',
  제주: '제주',
};

export interface Point {
  x: number;
  y: number;
}

/**
 * Frame the hand-placed percentages were drawn on (400px phone, sheet collapsed, before M6). MapLayer draws the
 * image and markers at this size and scales the whole layer to cover the map area, so every map area shows the
 * same arrangement: a percentage points at the same image spot and marker spacing stays as designed (M1-D2, D3).
 */
export const MAP_DESIGN_FRAME_PX = { width: 392, height: 530 } as const;

export interface Size {
  width: number;
  height: number;
}

/**
 * Map areas in use, measured in Chromium on 2026-11-09 (M1-D3): single phone at 360/390/≥430px viewports and the
 * 2-Phone comparison. Tests check that markers stay inside the part of the design frame each area shows.
 * Single phone height 488 → 492 after the search box became 44px tall (T44-D3).
 * Remeasured 2026-11-10 after the 390×844 PhoneShell (T1b, tests/e2e/map-layout.mjs): single phone 336/366/382×466 at
 * 360/390/≥430px viewports; both 2-Phone maps are now full width, 382×483.
 * Full-screen phones (T2) have other sizes; MAP_AREA_HEIGHT (MapParts.tsx) keeps them between the shortest and tallest
 * aspects here, and a crop depends only on the aspect (mapLayerFit), so these checks still cover them.
 */
export const MAP_AREAS_PX: readonly Size[] = [
  { width: 336, height: 466 }, { width: 366, height: 466 }, { width: 382, height: 466 },
  { width: 382, height: 483 },
];

/** How MapLayer fits the design frame into a map area: scale to cover, centred. Crops are in design pixels. */
export interface MapLayerFit {
  scale: number;
  cropX: number;
  cropY: number;
}

export function mapLayerFit(area: Size): MapLayerFit {
  const scale = Math.max(area.width / MAP_DESIGN_FRAME_PX.width, area.height / MAP_DESIGN_FRAME_PX.height);
  return {
    scale,
    cropX: (MAP_DESIGN_FRAME_PX.width - area.width / scale) / 2,
    cropY: (MAP_DESIGN_FRAME_PX.height - area.height / scale) / 2,
  };
}

/** Marker footprint (badge + short-name label) used by the overlap checks. */
export const MARKER_BOX_PX = { width: 44, height: 34 } as const;

export interface MapCluster {
  id: string;
  region: SchoolRegion;
  x: number;
  y: number;
  size: string;
  fontSize: string;
  bg: string;
  textColor: string;
}

/** National view bubbles, one per data region. School counts are derived, never stored. */
export const MAP_CLUSTERS: readonly MapCluster[] = [
  { id: 'seoul', region: '서울', x: 33, y: 22, size: 'w-11 h-11', fontSize: 'text-[9px]', bg: '#fef08a', textColor: '#261608' },
  { id: 'incheon', region: '인천', x: 21, y: 24, size: 'w-9 h-9', fontSize: 'text-[8px]', bg: '#dbeafe', textColor: '#1e3a8a' },
  { id: 'gyeonggi', region: '경기', x: 44, y: 29, size: 'w-11 h-11', fontSize: 'text-[9px]', bg: '#e0e7ff', textColor: '#312e81' },
  { id: 'gangwon', region: '강원', x: 64, y: 21, size: 'w-9 h-9', fontSize: 'text-[8px]', bg: '#dcfce7', textColor: '#14532d' },
  { id: 'chungbuk', region: '충북', x: 58, y: 38, size: 'w-11 h-11', fontSize: 'text-[9px]', bg: '#fef3c7', textColor: '#78350f' },
  { id: 'chungnam_daejeon', region: '충남·대전', x: 32, y: 41, size: 'w-13 h-13', fontSize: 'text-[9px]', bg: '#fed7aa', textColor: '#7c2d12' },
  { id: 'jeolla', region: '전라', x: 33, y: 60, size: 'w-11 h-11', fontSize: 'text-[9px]', bg: '#d1fae5', textColor: '#064e3b' },
  { id: 'gyeongsang', region: '경상', x: 71, y: 53, size: 'w-11 h-11', fontSize: 'text-[9px]', bg: '#fee2e2', textColor: '#7f1d1d' },
  { id: 'jeju', region: '제주', x: 28, y: 92, size: 'w-8 h-8', fontSize: 'text-[8px]', bg: '#ffedd5', textColor: '#9a3412' },
];

// Hand-placed national points (percent of the map frame), carried over from the old universitiesData x/y.
// hanyang, ajou, gachon, hknu, kunsan and woosuk moved 1–5 points for M1 so zoomed region markers do not overlap.
const PLACED_POINTS: Readonly<Record<SchoolId, Point>> = {
  [SCHOOL_ID.kaist]: { x: 41, y: 44 }, [SCHOOL_ID.pusan]: { x: 77, y: 66 }, [SCHOOL_ID.chonnam]: { x: 31, y: 65 }, [SCHOOL_ID.hanyang]: { x: 41, y: 22 },
  [SCHOOL_ID.ajou]: { x: 36, y: 30 }, [SCHOOL_ID.gachon]: { x: 46, y: 29 }, [SCHOOL_ID.kau]: { x: 31, y: 18 }, [SCHOOL_ID.daejin]: { x: 40, y: 15 },
  [SCHOOL_ID.suwon]: { x: 32, y: 33 }, [SCHOOL_ID.hknu]: { x: 44, y: 40 }, [SCHOOL_ID.kangwon]: { x: 54, y: 20 },
  [SCHOOL_ID.sangji]: { x: 58, y: 29 }, [SCHOOL_ID.cbu]: { x: 48, y: 41 }, [SCHOOL_ID.ut]: { x: 56, y: 35 }, [SCHOOL_ID.semyung]: { x: 64, y: 32 },
  [SCHOOL_ID.jungwon]: { x: 53, y: 38 }, [SCHOOL_ID.u1]: { x: 52, y: 49 }, [SCHOOL_ID.kut]: { x: 39, y: 38 }, [SCHOOL_ID.sch]: { x: 33, y: 39 },
  [SCHOOL_ID.gongju]: { x: 36, y: 45 }, [SCHOOL_ID.konyang]: { x: 37, y: 49 }, [SCHOOL_ID.hanseo]: { x: 26, y: 38 }, [SCHOOL_ID.chungwoon]: { x: 30, y: 42 },
  [SCHOOL_ID.jbnu]: { x: 36, y: 57 }, [SCHOOL_ID.wku]: { x: 33, y: 52 }, [SCHOOL_ID.kunsan]: { x: 27, y: 55 }, [SCHOOL_ID.scnu]: { x: 47, y: 70 },
  [SCHOOL_ID.mokpo]: { x: 23, y: 74 }, [SCHOOL_ID.woosuk]: { x: 38, y: 54 }, [SCHOOL_ID.knu]: { x: 68, y: 53 }, [SCHOOL_ID.postech]: { x: 81, y: 51 },
  [SCHOOL_ID.unist]: { x: 81, y: 61 }, [SCHOOL_ID.yu]: { x: 73, y: 56 }, [SCHOOL_ID.cwnu]: { x: 67, y: 67 }, [SCHOOL_ID.gnu]: { x: 56, y: 66 },
  // 2026-11-10 (J1, docs/261110-2005-visit_ui_jeju_map_plan.md §3-2): 26,92 and 33,93 lay in the sea north of the
  // island. Both schools are in 제주시, so they sit inland of the north coast; tests check they are on the island.
  [SCHOOL_ID.jejunu]: { x: 24.2, y: 97.4 }, [SCHOOL_ID.jeju_intl]: { x: 28.3, y: 98.1 },
  // 2026-11-09 (M1-D3): the second 강릉 slot ran off the right edge of the narrowest map area in the 강원 view.
  [SCHOOL_ID.cku]: { x: 71, y: 25 },
};

// Base points for cities where no school has a hand-placed point (decision D3, 2026-11-08).
const CITY_ANCHORS: Readonly<Record<string, Point>> = {
  인천: { x: 22, y: 25 },
  용인: { x: 39, y: 33 },
  오산: { x: 33, y: 38 },
  음성: { x: 50, y: 36 },
};

/**
 * Screen-pixel offsets for schools sharing a city point (decision D3). Slot 0 sits on the point; the
 * rest step by more than one marker box so badges and labels never overlap in the zoomed region view.
 */
// Order changed 2026-11-09 (M1-D3): 인천 fills right and down before left, and 서울's 10th school goes left,
// so the 수도권 view stays inside the narrowest map area.
export const SLOT_OFFSETS_PX: readonly Point[] = [
  { x: 0, y: 0 },
  { x: 48, y: 0 }, { x: 0, y: 38 }, { x: 0, y: -38 }, { x: 48, y: 38 },
  { x: 48, y: -38 }, { x: -48, y: 0 }, { x: -48, y: 38 }, { x: -48, y: -38 },
  { x: -96, y: 0 }, { x: 96, y: 0 }, { x: 96, y: 38 }, { x: -96, y: 38 },
];

export interface NationalPlacement {
  point: Point;
  slot: number;
}

function buildNationalPlacements(): Readonly<Record<SchoolId, NationalPlacement>> {
  const placements: Record<SchoolId, NationalPlacement> = {};
  const cityPoint = new Map<string, Point>();
  const nextSlot = new Map<string, number>();
  for (const school of REAL_UNIVERSITIES) {
    const point = PLACED_POINTS[school.id];
    if (!point) continue;
    placements[school.id] = { point, slot: 0 };
    if (!cityPoint.has(school.city)) {
      cityPoint.set(school.city, point);
      nextSlot.set(school.city, 1);
    }
  }
  for (const school of REAL_UNIVERSITIES) {
    if (placements[school.id]) continue;
    const point = cityPoint.get(school.city) ?? CITY_ANCHORS[school.city];
    if (!point) continue; // Reported by tests/schoolDirectory.test.ts as a school without a map position.
    const slot = nextSlot.get(school.city) ?? 0;
    nextSlot.set(school.city, slot + 1);
    placements[school.id] = { point, slot };
  }
  return placements;
}

export const NATIONAL_PLACEMENTS = buildNationalPlacements();

/** TOP 3 ribbons in the national view keep clear of the cluster bubbles; other schools use their own point. */
const RIBBON_POINTS: Readonly<Record<SchoolId, Point>> = {
  [SCHOOL_ID.kaist]: { x: 49, y: 52 }, // 44,50 → 49,52 (M1-D4): the ribbon covered the 충남·대전 school count
  [SCHOOL_ID.pusan]: { x: 80, y: 66 },
  [SCHOOL_ID.chonnam]: { x: 27, y: 73 }, // y 70→73 (M1-D4 check): the ribbon box overlapped the 전라 bubble edge
};

export function nationalRibbonPoint(schoolId: SchoolId): Point | undefined {
  return RIBBON_POINTS[schoolId] ?? NATIONAL_PLACEMENTS[schoolId]?.point;
}

export interface RegionViewport {
  cx: number;
  cy: number;
  zoom: number;
}

const MIN_ZOOM = 1.5;
const MAX_ZOOM = 4;

/** Box in percent of the map frame. */
export interface FrameBox {
  minX: number;
  maxX: number;
  minY: number;
  maxY: number;
}

/** Source map pixel → percent of the design frame, through the same object-cover box MapArt draws the layers in. */
export function mapImageToFrame(x: number, y: number): Point {
  const { width: fw, height: fh } = MAP_DESIGN_FRAME_PX;
  const s = Math.max(fw / mapLayers.width, fh / mapLayers.height);
  const left = (fw - mapLayers.width * s) / 2;
  const top = (fh - mapLayers.height * s) / 2;
  return { x: ((x * s + left) / fw) * 100, y: ((y * s + top) / fh) * 100 };
}

/** Outline of 제주도 written by scripts/map/split_layers.py (largest island, source pixels). */
export const JEJU_ISLAND = mapLayers.jeju;

/** 제주도's bounding box in percent of the design frame. */
export const JEJU_ISLAND_BOX: FrameBox = (() => {
  const a = mapImageToFrame(JEJU_ISLAND.x0, JEJU_ISLAND.y0);
  const b = mapImageToFrame(JEJU_ISLAND.x1 + 1, JEJU_ISLAND.y1 + 1);
  return { minX: a.x, maxX: b.x, minY: a.y, maxY: b.y };
})();

/**
 * Regions framed on their land rather than on their school points. 제주 (J2, 2026-11-10): the two schools sit close
 * together on the north side, so their centre left the island in the lower part of the view.
 */
const REGION_FOCUS: Partial<Record<SchoolFilterRegion, FrameBox>> = {
  제주: JEJU_ISLAND_BOX,
};

function fitViewport({ minX, maxX, minY, maxY }: FrameBox): RegionViewport {
  const zoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.min(60 / Math.max(maxX - minX, 1), 60 / Math.max(maxY - minY, 1))));
  return { cx: (minX + maxX) / 2, cy: (minY + maxY) / 2, zoom };
}

/** Zoom the national map onto one filter region (decision D2). Fits the region's points, or its land, into the frame. */
export function regionViewport(region: SchoolFilterRegion): RegionViewport {
  const focus = REGION_FOCUS[region];
  if (focus) return fitViewport(focus);
  const points = REAL_UNIVERSITIES.filter((school) => school.filterRegion === region)
    .map((school) => NATIONAL_PLACEMENTS[school.id]?.point)
    .filter((point): point is Point => !!point);
  if (points.length === 0) return { cx: 50, cy: 50, zoom: 1 };
  const xs = points.map((p) => p.x);
  const ys = points.map((p) => p.y);
  return fitViewport({ minX: Math.min(...xs), maxX: Math.max(...xs), minY: Math.min(...ys), maxY: Math.max(...ys) });
}

/** Point in the zoomed view, in percent of the map frame. */
export function projectToViewport(point: Point, viewport: RegionViewport): Point {
  return { x: 50 + viewport.zoom * (point.x - viewport.cx), y: 50 + viewport.zoom * (point.y - viewport.cy) };
}

/** Marker centre in design-frame pixels for a school in a zoomed region view. */
export function regionMarkerPx(schoolId: SchoolId, viewport: RegionViewport): Point | undefined {
  const placement = NATIONAL_PLACEMENTS[schoolId];
  if (!placement) return undefined;
  const p = projectToViewport(placement.point, viewport);
  const offset = SLOT_OFFSETS_PX[placement.slot] ?? SLOT_OFFSETS_PX[0];
  return { x: (p.x / 100) * MAP_DESIGN_FRAME_PX.width + offset.x, y: (p.y / 100) * MAP_DESIGN_FRAME_PX.height + offset.y };
}

export interface ChungcheongPlacement extends Point {
  subRegion: string;
  leaderLine?: { dx: number; dy: number };
}

/**
 * Points on the dedicated 충청 screen (its own zoomed frame). The first 18 are the original hand placements.
 * The last 6 were added for M1 (2026-11-08): a least-squares fit from national to 충청 frame points of the
 * 7 schools placed on both maps, then nudged off neighbouring markers.
 */
export const CHUNGCHEONG_PLACEMENTS: Readonly<Record<SchoolId, ChungcheongPlacement>> = {
  [SCHOOL_ID.kaist]: { x: 39, y: 68, subRegion: '대전' },
  [SCHOOL_ID.cnu]: { x: 53, y: 63, subRegion: '대전' },
  [SCHOOL_ID.hanbat]: { x: 29, y: 76, subRegion: '대전' },
  [SCHOOL_ID.hannam]: { x: 62, y: 74, subRegion: '대전' },
  [SCHOOL_ID.cbu]: { x: 58, y: 43, subRegion: '청주' },
  [SCHOOL_ID.cheongju]: { x: 69, y: 35, subRegion: '청주' },
  [SCHOOL_ID.seowon]: { x: 70, y: 44, subRegion: '청주' },
  [SCHOOL_ID.knue]: { x: 50, y: 50, subRegion: '청주', leaderLine: { dx: -8, dy: 6 } },
  [SCHOOL_ID.cje]: { x: 77, y: 52, subRegion: '청주' },
  [SCHOOL_ID.ut]: { x: 72, y: 22, subRegion: '충주' },
  [SCHOOL_ID.semyung]: { x: 83, y: 14, subRegion: '제천' },
  [SCHOOL_ID.sch]: { x: 14, y: 28, subRegion: '아산' }, // x 17→14 (M1-D1): cleared 호서대
  [SCHOOL_ID.hoseo]: { x: 27, y: 25, subRegion: '아산' },
  [SCHOOL_ID.sunmoon]: { x: 23, y: 36, subRegion: '아산', leaderLine: { dx: 6, dy: 6 } },
  [SCHOOL_ID.kut]: { x: 44, y: 35, subRegion: '천안' },
  [SCHOOL_ID.baekseok]: { x: 46, y: 24, subRegion: '천안' },
  [SCHOOL_ID.nsu]: { x: 34, y: 17, subRegion: '천안' }, // y 19→17 (M1-D1): cleared 호서대
  [SCHOOL_ID.gongju]: { x: 25, y: 52, subRegion: '공주' },
  [SCHOOL_ID.jungwon]: { x: 59, y: 28, subRegion: '괴산' },
  [SCHOOL_ID.kdu]: { x: 58, y: 16, subRegion: '음성' },
  [SCHOOL_ID.u1]: { x: 62, y: 86, subRegion: '영동' },
  [SCHOOL_ID.konyang]: { x: 22, y: 86, subRegion: '논산' },
  [SCHOOL_ID.hanseo]: { x: 11, y: 18, subRegion: '서산' }, // x 8→11 (M1-D3): inside the narrowest map area
  [SCHOOL_ID.chungwoon]: { x: 13, y: 44, subRegion: '홍성' },
};
