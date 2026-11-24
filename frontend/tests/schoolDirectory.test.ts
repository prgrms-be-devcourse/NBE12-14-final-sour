import { SCHOOL_ID, type SchoolId } from '../src/data/schoolIds.ts';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { REAL_UNIVERSITIES } from '../src/data/universitiesData.ts';
import { getSchoolRankings } from '../src/data/mockRankings.ts';
import { INITIAL_SCHOOL_SCORE, MY_SCHOOL_ID } from '../src/data/gameRules.ts';
import { countByRegion, filterSchools, getSchoolById, getSchoolViews, getTopSchools } from '../src/data/schoolDirectory.ts';
import { SCHOOL_LOGO_ASSETS, schoolLogoStatus } from '../src/data/schoolLogos.ts';
import {
  CHUNGCHEONG_PLACEMENTS,
  JEJU_ISLAND,
  JEJU_ISLAND_BOX,
  MAP_CLUSTERS,
  MAP_AREAS_PX,
  MAP_DESIGN_FRAME_PX,
  MARKER_BOX_PX,
  NATIONAL_PLACEMENTS,
  REGION_TO_FILTER,
  nationalRibbonPoint,
  mapImageToFrame,
  mapLayerFit,
  regionMarkerPx,
  regionViewport,
  type Size,
  type Point,
  type SchoolFilterRegion,
} from '../src/data/schoolMapLayout.ts';

// Plan: docs/261108-1930-m1_school_map_data_plan.md. These tests pin the M1 data invariants:
// one ID per school, map positions for every school, and map values derived from the shared ranking rule.

const ids = REAL_UNIVERSITIES.map((school) => school.id);
const known = new Set(ids);

function overlaps(points: readonly { id: SchoolId; p: Point }[]): string[] {
  const hits: string[] = [];
  for (let i = 0; i < points.length; i++) {
    for (let j = i + 1; j < points.length; j++) {
      const a = points[i].p;
      const b = points[j].p;
      if (Math.abs(a.x - b.x) < MARKER_BOX_PX.width && Math.abs(a.y - b.y) < MARKER_BOX_PX.height) hits.push(`${points[i].id}/${points[j].id}`);
    }
  }
  return hits;
}

test('학교 ID는 유일하고 권역 필터는 지역에서 파생된 값과 같다', () => {
  assert.equal(known.size, ids.length);
  for (const school of REAL_UNIVERSITIES) assert.equal(school.filterRegion, REGION_TO_FILTER[school.region], `school ${school.id}`);
});

test('모든 학교가 전국 지도 위치를 갖고 충청 학교는 충청 전용 화면 위치를 갖는다', () => {
  assert.deepEqual(ids.filter((id) => !NATIONAL_PLACEMENTS[id]), []);
  const chungcheong = REAL_UNIVERSITIES.filter((school) => school.filterRegion === '충청').map((school) => school.id);
  assert.equal(chungcheong.length, 24);
  assert.deepEqual(chungcheong.filter((id) => !CHUNGCHEONG_PLACEMENTS[id]), []);
  for (const id of [...Object.keys(NATIONAL_PLACEMENTS), ...Object.keys(CHUNGCHEONG_PLACEMENTS)].map(Number)) assert.ok(known.has(id), `unknown layout key ${id}`);
  const clusterRegions = new Set(MAP_CLUSTERS.map((cluster) => cluster.region));
  for (const school of REAL_UNIVERSITIES) assert.ok(clusterRegions.has(school.region), `no cluster for ${school.region}`);
});

// MapLayer draws markers at the design frame size and scales the whole layer, so spacing is checked once in design
// pixels. "Inside" uses the part of the frame each measured map area shows (M1-D3).
const FRAME = MAP_DESIGN_FRAME_PX;
const label = (area: Size) => `${area.width}×${area.height}`;
const outside = (points: readonly { id: SchoolId; p: Point }[]) => MAP_AREAS_PX.flatMap((area) => {
  const { cropX, cropY } = mapLayerFit(area);
  return points.filter(({ p }) =>
    p.x < cropX + MARKER_BOX_PX.width / 2 || p.x > FRAME.width - cropX - MARKER_BOX_PX.width / 2 ||
    p.y < cropY + MARKER_BOX_PX.height / 2 || p.y > FRAME.height - cropY - MARKER_BOX_PX.height / 2).map(({ id }) => `${id}@${label(area)}`);
});

test('확대한 권역(충청 제외)에서 학교 마커가 겹치지 않고 모든 지도 크기에서 보이는 영역 안에 있다', () => {
  const regions: SchoolFilterRegion[] = ['수도권', '강원', '영남', '호남', '제주'];
  for (const region of regions) {
    const viewport = regionViewport(region);
    const points = REAL_UNIVERSITIES.filter((school) => school.filterRegion === region).map((school) => ({ id: school.id, p: regionMarkerPx(school.id, viewport)! }));
    assert.ok(points.length > 0, region);
    assert.deepEqual(outside(points), [], `${region} outside`);
    assert.deepEqual(overlaps(points), [], `${region} overlaps`);
  }
});

// J1·J2 (docs/261110-2005-visit_ui_jeju_map_plan.md §3, 사용자 지시 2026-11-10): "제주도 지역으로 들어가보면 학교들이
// 바다에 위치해 있는 어색함" — schools must stand on the island, and the 제주 view must be centred on the island.
const ISLAND_MARGIN_PX = 8; // source pixels kept between a school point and the coast (≈12 screen px when zoomed)

/** Inverse of mapImageToFrame (it is linear in each axis). */
function frameToImage(p: Point): Point {
  const o = mapImageToFrame(0, 0);
  const u = mapImageToFrame(1, 1);
  return { x: (p.x - o.x) / (u.x - o.x), y: (p.y - o.y) / (u.y - o.y) };
}

/** True when the square of ±margin source pixels around the point lies inside 제주도's per-row extent. */
function onJeju(point: Point, margin: number): boolean {
  const { x, y } = frameToImage(point);
  for (let row = Math.floor(y - margin); row <= Math.ceil(y + margin); row++) {
    const span = JEJU_ISLAND.rows[row - JEJU_ISLAND.y0];
    if (!span || x - margin < span[0] || x + margin > span[1]) return false;
  }
  return true;
}

test('제주 학교는 제주도 육지 위에 있다 (J1)', () => {
  const jeju = REAL_UNIVERSITIES.filter((school) => school.filterRegion === '제주');
  assert.ok(jeju.length > 0);
  const offIsland = jeju.filter((school) => !onJeju(NATIONAL_PLACEMENTS[school.id].point, ISLAND_MARGIN_PX)).map((school) => school.id);
  assert.deepEqual(offIsland, [], 'move these schools onto 제주도 (frame % in PLACED_POINTS, schoolMapLayout.ts)');
  // The check itself: the old points (26,92) and (33,93) were in the sea north of the island.
  assert.equal(onJeju({ x: 26, y: 92 }, 0), false);
});

test('제주 확대 화면은 제주도를 가운데에 두고 섬 전체를 모든 지도 크기에서 보인다 (J2)', () => {
  const viewport = regionViewport('제주');
  const box = JEJU_ISLAND_BOX;
  assert.ok(Math.abs(viewport.cx - (box.minX + box.maxX) / 2) < 0.5, `cx ${viewport.cx}`);
  assert.ok(Math.abs(viewport.cy - (box.minY + box.maxY) / 2) < 0.5, `cy ${viewport.cy}`);
  for (const area of MAP_AREAS_PX) {
    const { cropX, cropY } = mapLayerFit(area);
    const corners = [{ x: box.minX, y: box.minY }, { x: box.maxX, y: box.maxY }].map((p) => {
      const q = { x: 50 + viewport.zoom * (p.x - viewport.cx), y: 50 + viewport.zoom * (p.y - viewport.cy) };
      return { x: (q.x / 100) * FRAME.width, y: (q.y / 100) * FRAME.height };
    });
    assert.ok(corners[0].x >= cropX && corners[1].x <= FRAME.width - cropX, `island clipped left/right at ${label(area)}`);
    assert.ok(corners[0].y >= cropY && corners[1].y <= FRAME.height - cropY, `island clipped top/bottom at ${label(area)}`);
  }
});

test('충청 전용 화면의 학교 마커는 서로 겹치지 않고 모든 지도 크기에서 보이는 영역 안에 있다 (M1-D1)', () => {
  const points = Object.entries(CHUNGCHEONG_PLACEMENTS).map(([id, p]) => ({ id: Number(id), p: { x: (p.x / 100) * FRAME.width, y: (p.y / 100) * FRAME.height } }));
  assert.deepEqual(outside(points), []);
  assert.deepEqual(overlaps(points), []);
});

// TOP 3 marker (ribbon + badge + label) and cluster bubble sizes, measured in Chromium on 2026-11-09 in design pixels.
const TOP_MARKER_BOX = { width: 75, height: 68, dy: -12 };
const BUBBLE_PX: Record<string, number> = { 'w-8 h-8': 32, 'w-9 h-9': 36, 'w-11 h-11': 44, 'w-13 h-13': 52 };

test('전국 지도의 TOP 3 리본은 권역 버블의 학교 수를 가리지 않는다 (M1-D4)', () => {
  const hits: string[] = [];
  for (const id of [SCHOOL_ID.kaist, SCHOOL_ID.pusan, SCHOOL_ID.chonnam]) {
    const p = nationalRibbonPoint(id)!;
    const cx = (p.x / 100) * FRAME.width;
    const cy = (p.y / 100) * FRAME.height + TOP_MARKER_BOX.dy;
    for (const cluster of MAP_CLUSTERS) {
      const d = BUBBLE_PX[cluster.size];
      assert.ok(d, cluster.size);
      const bx = (cluster.x / 100) * FRAME.width;
      const by = (cluster.y / 100) * FRAME.height;
      if (Math.abs(cx - bx) < (TOP_MARKER_BOX.width + d) / 2 && Math.abs(cy - by) < (TOP_MARKER_BOX.height + d) / 2) hits.push(`${id}/${cluster.region}`);
    }
  }
  assert.deepEqual(hits, []);
});

test('지도 표시 값은 공통 순위 규칙과 같고 내 학교는 게임 점수를 쓴다', () => {
  const views = getSchoolViews();
  const rankings = getSchoolRankings(INITIAL_SCHOOL_SCORE);
  assert.equal(views.length, REAL_UNIVERSITIES.length);
  assert.deepEqual(views.map(({ id, rank, score }) => ({ id, rank, score })), rankings.map(({ id, rank, score }) => ({ id, rank, score })));
  const mine = getSchoolById(views, MY_SCHOOL_ID)!;
  assert.equal(mine.isMySchool, true);
  assert.equal(mine.score, INITIAL_SCHOOL_SCORE);
  assert.equal(mine.rank, 18);
  assert.equal(views.filter((school) => school.isMySchool).length, 1);
  assert.deepEqual(getTopSchools(views).map((school) => school.id), [SCHOOL_ID.kaist, SCHOOL_ID.pusan, SCHOOL_ID.chonnam]);
  assert.deepEqual(views.filter((school) => school.isTop3).map((school) => school.id), [SCHOOL_ID.kaist, SCHOOL_ID.pusan, SCHOOL_ID.chonnam]);
});

test('내 점수가 바뀌면 TOP 3와 캠퍼스 단계가 함께 바뀐다', () => {
  const top = getSchoolViews(3_000_000);
  assert.deepEqual(getTopSchools(top).map((school) => school.id), [SCHOOL_ID.hanyang, SCHOOL_ID.kaist, SCHOOL_ID.pusan]);
  assert.equal(getSchoolById(top, SCHOOL_ID.chonnam)!.isTop3, false);
  assert.ok(nationalRibbonPoint(SCHOOL_ID.hanyang), 'TOP 3에 들어온 학교도 리본 위치가 있다');
  assert.equal(getSchoolById(getSchoolViews(999_999), MY_SCHOOL_ID)!.campusLevel, 3);
  assert.equal(getSchoolById(getSchoolViews(1_000_000), MY_SCHOOL_ID)!.campusLevel, 4);
  assert.equal(getSchoolById(top, MY_SCHOOL_ID)!.campusLevel, 5);
});

test('권역 학교 수는 데이터에서 계산된다', () => {
  const counts = countByRegion(getSchoolViews());
  let total = 0;
  for (const cluster of MAP_CLUSTERS) {
    const expected = REAL_UNIVERSITIES.filter((school) => school.region === cluster.region).length;
    assert.equal(counts.get(cluster.region), expected, cluster.region);
    total += expected;
  }
  assert.equal(total, REAL_UNIVERSITIES.length);
});

test('검색은 권역 필터 뒤에 이름·약칭·도시로 찾고 순위 순서를 지킨다', () => {
  const views = getSchoolViews();
  assert.equal(filterSchools(views, '전체', '').length, 76);
  assert.equal(filterSchools(views, '충청', '').length, 24);
  assert.deepEqual(filterSchools(views, '전체', '  KAIST ').map((school) => school.id), [SCHOOL_ID.kaist]);
  assert.ok(filterSchools(views, '수도권', '한양').some((school) => school.id === SCHOOL_ID.hanyang));
  assert.deepEqual(filterSchools(views, '제주', '한양'), []);
  assert.deepEqual(filterSchools(views, '전체', '존재하지않는학교'), []);
  const ranks = filterSchools(views, '영남', '').map((school) => school.rank);
  assert.deepEqual(ranks, [...ranks].sort((a, b) => a - b));
});

test('로고 자산이 없는 학교는 모두 임시 배지 상태로 표시된다(결정 D1)', () => {
  const views = getSchoolViews();
  const placeholders = views.filter((school) => school.logoStatus === 'placeholder').length;
  assert.equal(placeholders, views.length - Object.keys(SCHOOL_LOGO_ASSETS).length);
  assert.equal(placeholders, 76);
  assert.equal(schoolLogoStatus(999_999), 'placeholder');
});
