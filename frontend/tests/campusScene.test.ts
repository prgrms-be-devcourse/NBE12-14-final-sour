import type { SchoolId } from '../src/data/schoolIds';
import { after, afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { assertAbsent } from './helpers/domAssert';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';

// Same asset stub as tests/gameUi.test.ts: the fallback CampusSprite imports the level screenshots.
const assetHooks = registerHooks({
  load(url, context, nextLoad) {
    if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) {
      const match = new URL(url).pathname.match(/\/src\/assets\/.+$/);
      return { format: 'module', source: `export default ${JSON.stringify(match ? match[0] : '/stub.svg')};`, shortCircuit: true };
    }
    return nextLoad(url, context);
  },
});
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({
  window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });

const { act, cleanup, render, screen, waitFor } = await import('@testing-library/react');
const { CAMPUS_ASSETS, hdFileFor, hdScale } = await import('../src/data/campusAssets');
const { CAMPUS_LAYOUTS, FEATURE_MIN_VISIBLE, LAMP_GAP, lampGlows, lightOccluders, placementTransform, sortPlacements, terrainGroups, validateCampusLayouts } = await import('../src/data/campusLayouts');
const { CAMPUS_TALL_ASPECT, CAMPUS_UI_OVERLAYS, campusView, doorPoints, faceMid, footprintCores, footprintPolygon, polygonDistance, polygonGap, footprintOf, iso, isoRect, isoStrip, mid, pointInPolygon, spriteBox, visibleFraction } = await import('../src/data/campusGeometry');
const { CAMPUS_STAGES } = await import('../src/data/gameRules');
const { CampusScene, CampusSceneLights } = await import('../src/components/CampusScene');
const { MIN_NAME_SIZE, fitFontSize, signDisplayName, signTextLayout, splitSignName } = await import('../src/components/CampusSignText');
type CampusLayout = import('../src/data/campusLayouts').CampusLayout;

after(() => { assetHooks.deregister(); dom.window.close(); });
afterEach(cleanup);

/**
 * Known violations of the U0 path rules (docs/261109-1950-campus_lead_feedback_plan.md), fixed level by
 * level in U4-U10. Each step deletes its level's lines; the list must end empty. Any new problem fails.
 */
const LEAD_FEEDBACK_OPEN: readonly string[] = [];

test('campus layouts reference known assets of the right kind and cover every stage feature', () => {
  assert.deepEqual(validateCampusLayouts(), LEAD_FEEDBACK_OPEN);
  for (let level = 1; level <= 10; level++) assert.ok(CAMPUS_LAYOUTS[level], `Lv.${level} layout exists (plan A4: every level is a composed scene)`);
});

// Regression sensors for docs/261109-1950-campus_lead_feedback_plan.md U0 (team lead feedback F1-F3).
test('the sun and moon sit above every cloud (F1)', () => {
  const lv1 = CAMPUS_LAYOUTS[1]!;
  const high = { ...lv1, clouds: [{ asset: 'cloud-1' as const, x: 200, y: 26 }] };
  assert.ok(validateCampusLayouts({ 1: high }).some((p) => p.includes('higher than the sun and moon')));
  // Lowering clouds too far hides them behind the far forest (seen in the first U0 screenshots).
  const sunk = { ...lv1, clouds: [{ asset: 'cloud-3' as const, x: 300, y: 56 }] };
  assert.ok(validateCampusLayouts({ 1: sunk }).some((p) => p.includes('behind the far hills')));
  assert.deepEqual(validateCampusLayouts({ 1: lv1 }), []);
});

test('validation rejects split paths, an entrance off the paths, props on a path and a pond on a path (F2, F3)', () => {
  const lv2 = CAMPUS_LAYOUTS[2]!;
  const hall = lv2.items.find((i) => i.asset === 'hall-small')!;
  const door = faceMid(hall, 'left');
  const walk = { texture: 'tex-paving' as const, points: isoStrip(door, iso(door, 0, 60), 8), edgeColor: '#000' };
  const base: CampusLayout = { ...lv2, terrain: [walk], items: [hall] };
  assert.deepEqual(validateCampusLayouts({ 2: base }).filter((p) => !p.includes('feature')), []);
  const far = { ...walk, points: isoStrip(iso(door, 80, 0), iso(door, 80, 60), 8) };
  assert.ok(validateCampusLayouts({ 2: { ...base, terrain: [walk, far] } }).some((p) => p.includes('2 separate groups')));
  assert.ok(validateCampusLayouts({ 2: { ...base, terrain: [far] } }).some((p) => p.includes('hall-small entrance') && p.includes('off the paths')));
  const onPath = iso(door, 0, 30);
  assert.ok(validateCampusLayouts({ 2: { ...base, items: [hall, { asset: 'rock', x: onPath[0], y: onPath[1] }] } }).some((p) => p.includes('rock') && p.includes('stands on a path')));
  assert.ok(!validateCampusLayouts({ 2: { ...base, items: [hall, { asset: 'student-walk-1', x: onPath[0], y: onPath[1] }] } }).some((p) => p.includes('stands on a path')), 'students may walk on paths');
  // G2 fill props (fences, planters, flower borders, low hedges) must stay off paths too (user request 2026-11-09).
  for (const asset of ['fence-iron', 'fence-picket', 'hedge-low', 'planter-strip', 'planter-square', 'flower-border'] as const) {
    assert.ok(validateCampusLayouts({ 2: { ...base, items: [hall, { asset, x: onPath[0], y: onPath[1] }] } }).some((p) => p.includes(`${asset} at`) && p.includes('stands on a path')), asset);
  }
  // A long prop's base is the sprite's diagonal, a quarter of its width above the anchor: with the
  // anchor on the grass beside the walk, the fence can still be drawn over the walk (UF, Lv.4).
  const fenceAt = (u: number) => { const p = iso(door, u, 30); return validateCampusLayouts({ 2: { ...base, items: [hall, { asset: 'fence-picket', x: p[0], y: p[1] }] } }).some((m) => m.includes('stands on a path')); };
  assert.ok(fenceAt(14), 'fence base over the walk');
  assert.ok(!fenceAt(20), 'fence clear of the walk');
  // A walk must stop short of a sports court or track (U8: the east walk ran into the Lv.8 court).
  const courtOnPath = { texture: 'tex-court' as const, points: isoRect(onPath, 12, 6), edgeColor: '#000' };
  assert.ok(validateCampusLayouts({ 2: { ...base, terrain: [walk, courtOnPath] } }).some((p) => p.includes('path runs onto the court')));
  const courtAside = { ...courtOnPath, points: isoRect(iso(door, 60, 30), 12, 6) };
  assert.ok(!validateCampusLayouts({ 2: { ...base, terrain: [walk, courtAside] } }).some((p) => p.includes('runs onto')));
  const pondOnPath = { texture: 'tex-water' as const, points: isoRect(onPath, 10), edgeColor: '#000' };
  assert.ok(validateCampusLayouts({ 2: { ...base, terrain: [walk, pondOnPath] } }).some((p) => p.includes('pond overlaps')));
});

test('validation rejects two lamps drawn on almost the same spot (F5, Lv.6)', () => {
  const lv2 = CAMPUS_LAYOUTS[2]!;
  const lamp = (x: number, y: number) => ({ asset: 'lamp' as const, x, y });
  const twin = validateCampusLayouts({ 2: { ...lv2, items: [...lv2.items, lamp(200, 250), lamp(204, 250)] } });
  assert.ok(twin.some((p) => p.includes('lamps at 200,250 and 204,250 overlap')), JSON.stringify(twin));
  const apart = validateCampusLayouts({ 2: { ...lv2, items: [...lv2.items, lamp(200, 250), lamp(200 + LAMP_GAP, 250)] } });
  assert.ok(!apart.some((p) => p.includes('lamps')));
});

test('a scaled building grows about its anchor: box, footprint and transform follow (user request 2026-11-09)', () => {
  const lecture = CAMPUS_ASSETS['lecture-2-r'];
  const [ax, ay] = lecture.anchor;
  const item = { asset: 'lecture-2-r' as const, x: 100, y: 200, scale: 2 };
  assert.deepEqual(spriteBox(item), [100 - ax * 2, 200 - ay * 2, 100 + (lecture.width - ax) * 2, 200 + (lecture.height - ay) * 2]);
  const front = lecture.footprint!.front;
  assert.deepEqual(footprintOf(item)!.front, [100 + (front[0] - ax) * 2, 200 + (front[1] - ay) * 2]);
  assert.match(placementTransform(item), /scale\(2 2\)/);
  assert.match(placementTransform({ ...item, flip: true }), /scale\(-2 2\)/);
  assert.equal(placementTransform({ ...item, scale: undefined }), `translate(${100 - ax} ${200 - ay})`);
  // Lv.6: the lecture hall reads about as large as the Lv.5 library (wider, lower: 94% of its area).
  const box = (i: Parameters<typeof spriteBox>[0]) => { const [l, t, r, b] = spriteBox(i); return (r - l) * (b - t); };
  const lv6 = CAMPUS_LAYOUTS[6]!.items.find((i) => i.asset === 'lecture-2-r')!;
  const lv5 = CAMPUS_LAYOUTS[5]!.items.find((i) => i.asset === 'library-r')!;
  assert.ok(box(lv6) >= box(lv5) * 0.9, `${box(lv6)} vs ${box(lv5)}`);
  // Only buildings may be scaled.
  const lv2 = CAMPUS_LAYOUTS[2]!;
  assert.ok(validateCampusLayouts({ 2: { ...lv2, items: [...lv2.items, { asset: 'bench', x: 200, y: 250, scale: 1.5 }] } }).some((p) => p.includes('bench has scale 1.5')));
});

test('Lv.7: the lecture hall shows at its Lv.6 size; see-through avenue trees draw with their opacity (F6)', () => {
  const screen = (level: number) => {
    const layout = CAMPUS_LAYOUTS[level]!;
    const [l, , r] = spriteBox(layout.items.find((i) => i.asset === 'lecture-2-r')!);
    return (r - l) * layout.camera.zoom;
  };
  assert.ok(Math.abs(screen(7) - screen(6)) < 1, `${screen(7)} vs ${screen(6)}`);
  const seeThrough = CAMPUS_LAYOUTS[7]!.items.filter((i) => i.opacity !== undefined);
  assert.ok(seeThrough.length >= 3 && seeThrough.every((i) => i.asset.startsWith('tree-')));
  render(React.createElement(CampusScene, { level: 7 }));
  const drawn = [...document.querySelectorAll('[data-asset^="tree-cherry"]')].map((el) => el.getAttribute('opacity'));
  assert.equal(drawn.filter((o) => o === String(seeThrough[0]!.opacity)).length, seeThrough.length);
  const lv2 = CAMPUS_LAYOUTS[2]!;
  for (const opacity of [0, 1.5]) {
    assert.ok(validateCampusLayouts({ 2: { ...lv2, items: [...lv2.items, { asset: 'tree-oak-1', x: 200, y: 250, opacity }] } }).some((p) => p.includes(`has opacity ${opacity}`)));
  }
});

test('Lv.8: the main hall stays (Q6) and its entrance opens onto the fountain square (F7)', () => {
  const layout = CAMPUS_LAYOUTS[8]!;
  const main = layout.items.find((i) => i.asset === 'main-clocktower');
  assert.ok(main, 'Lv.8 keeps the clock-tower main hall');
  const fountain = layout.items.find((i) => i.asset === 'fountain')!;
  const [door] = doorPoints(main);
  // The entrance faces the square: the fountain lies down-right of the door, along +U.
  assert.ok(fountain.x > door![0] && fountain.y > door![1], `door ${door} vs fountain ${fountain.x},${fountain.y}`);
});

test('T2/T5: Lv.8 keeps its large court visible above the stats sign, without crossing the plaza or walks', () => {
  const layout = CAMPUS_LAYOUTS[8]!;
  const court = layout.terrain.find((t) => t.texture === 'tex-court')!;
  const xs = court.points.map(([x]) => x);
  const ys = court.points.map(([, y]) => y);
  const box = [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)] as const;
  // Preserve the approved large court (38×20 isometric half-extents), including its right-edge crop.
  assert.equal(box[2] - box[0], 116);
  assert.equal(box[3] - box[1], 58);
  const view = campusView(layout.camera);
  assert.ok(box[2] > view.x + view.width, 'the court must still extend beyond the right edge');
  assert.equal(layout.items.filter((i) => i.asset === 'goal-post').length, 1, 'keep the single inward-facing goal');

  // Short T2 frames need a stricter check than the old 367/358 default. Cover 0.8 through the
  // height cap, including intermediate ratios; this is a geometry guard, not browser measurement.
  const aspects = [view.height / view.width, CAMPUS_TALL_ASPECT,
    ...Array.from({ length: 30 }, (_, i) => 0.8 + i / 100)];
  for (const aspect of aspects) {
    const fraction = visibleFraction(box, campusView(layout.camera, aspect));
    assert.ok(fraction >= FEATURE_MIN_VISIBLE,
      `Lv.8 court only ${(fraction * 100).toFixed(1)}% visible at aspect ${aspect.toFixed(3)}; move the court clear of the stats sign without shrinking it`);
  }
  for (const terrain of layout.terrain.filter((t) => ['tex-paving', 'tex-dirt', 'tex-track'].includes(t.texture))) {
    assert.ok(polygonGap(court.points, terrain.points) >= 4, 'the lifted court must clear the plaza, walks and track');
  }
  assert.deepEqual(validateCampusLayouts({ 8: layout }), [], 'rerouted paths must still join and reach both entrances');
});

test('Lv.9: the main hall and both dormitories stand on one street, with flowerbeds and a crowd (F8)', () => {
  const layout = CAMPUS_LAYOUTS[9]!;
  for (const asset of ['main-clocktower', 'dorm-a', 'dorm-b'] as const) assert.ok(layout.items.some((i) => i.asset === asset), `Lv.9 has ${asset}`);
  const beds = layout.items.filter((i) => /^(flowerbed-|flower-border|planter-)/.test(i.asset));
  assert.ok(beds.length >= 4, `Lv.9 flowerbeds: ${beds.length}`);
  assert.ok(layout.items.filter((i) => i.asset.startsWith('student-')).length >= 20, 'Lv.9 is crowded (growth sheet)');
  // User request (2026-11-09): dorm-life props fill the lawns.
  for (const asset of ['bike-rack', 'bicycle', 'trash-bin', 'student-sit-1'] as const) assert.ok(layout.items.some((i) => i.asset === asset), `Lv.9 has ${asset}`);
});

test('Lv.10: flags stand off the paving and banner lamps ring the square (F9)', () => {
  const layout = CAMPUS_LAYOUTS[10]!;
  const paving = layout.terrain.filter((t) => t.texture === 'tex-paving');
  for (const flag of layout.items.filter((i) => i.asset === 'flag')) {
    assert.ok(paving.every((t) => polygonDistance([flag.x, flag.y], t.points) > 0), `Lv.10 flag at ${flag.x},${flag.y} stands on the paving`);
  }
  assert.ok(layout.items.filter((i) => i.asset === 'lamp-banner').length >= 6, 'Lv.10 banner lamps');
  // Four flower borders trim the open sides; the two corner flowerbeds were taken out (user, 2026-11-27).
  assert.ok(layout.items.filter((i) => /^(flowerbed-|flower-border)/.test(i.asset)).length >= 4, 'Lv.10 flower borders frame the square');  // User request (2026-11-10): from Lv.10 the campus is one L-shaped grand hall, not separate buildings.
  const buildings = layout.items.filter((i) => footprintOf(i) && i.asset !== 'fountain').map((i) => i.asset);
  assert.deepEqual(buildings, ['grand-hall']);
  // User request (2026-11-10): a fountain, trees, and walks out to the lower left and lower right.
  assert.ok(layout.items.some((i) => i.asset === 'fountain'), 'Lv.10 has a fountain');
  assert.ok(layout.items.filter((i) => i.asset.startsWith('tree-')).length >= 5, 'Lv.10 trees');
  const ends = layout.terrain.filter((t) => t.texture === 'tex-paving' && !t.base).flatMap((t) => t.points);
  assert.ok(ends.some(([x, y]) => x < 90 && y > 260), 'a walk leads to the lower left');
  assert.ok(ends.some(([x, y]) => x > 270 && y > 260), 'a walk leads to the lower right');
});

test('Lv.7: a sports hall fills the lower right and garden props line the paths (F10, F11)', () => {
  const layout = CAMPUS_LAYOUTS[7]!;
  const hall = layout.items.find((i) => i.asset === 'sports-hall');
  assert.ok(hall, 'Lv.7 has no sports hall');
  const [l, t, r, b] = spriteBox(hall);
  const { x: cx, y: cy, width, height } = campusView(layout.camera);
  const shown = (Math.min(r, cx + width) - Math.max(l, cx)) * (Math.min(b, cy + height) - Math.max(t, cy));
  assert.ok(shown / ((r - l) * (b - t)) >= 0.6, `sports hall only ${(shown / ((r - l) * (b - t))).toFixed(2)} on screen`);
  const garden = new Set(layout.items.map((i) => i.asset).filter((a) => /^(fence-|hedge-low|planter-|flower-border)/.test(a)));
  assert.ok(garden.size >= 3, `Lv.7 garden props: ${[...garden].join(', ')}`);
});

test('a flipped building has its entrance on the other face', () => {
  const plain = doorPoints({ asset: 'library', x: 100, y: 200 });
  const flipped = doorPoints({ asset: 'library', x: 100, y: 200, flip: true });
  assert.equal(plain.length, 1);
  assert.ok(plain[0][0] < 100 && flipped[0][0] > 100, `${plain} vs ${flipped}`);
  assert.equal(doorPoints({ asset: 'stadium', x: 100, y: 200 }).length, 2);
  assert.deepEqual(doorPoints({ asset: 'fountain', x: 100, y: 200 }), []);
});

test('an L-shaped building keeps its courtyard open and its door at the inside corner (user request 2026-11-10)', () => {
  const hall = { asset: 'grand-hall' as const, x: 200, y: 250 };
  const f = footprintOf(hall)!;
  assert.ok(f.inner, 'grand-hall footprint has an inside corner');
  assert.deepEqual(doorPoints(hall), [f.inner]);
  assert.equal(footprintPolygon(f).length, 6, 'the outline is the diamond minus the courtyard');
  const courtyard = mid(f.inner!, f.front);
  const cores = footprintCores(f);
  assert.equal(cores.length, 2, 'one core per wing');
  assert.ok(!cores.some((c) => pointInPolygon(courtyard, c)), 'the courtyard is outside both wing cores');
  assert.ok(cores.some((c) => pointInPolygon(mid(f.inner!, f.left), c)) && cores.some((c) => pointInPolygon(mid(f.inner!, f.right), c)), 'each wing has a core');
  const plaza: CampusLayout = { camera: CAMPUS_LAYOUTS[10]!.camera, clouds: [], ground: 'tex-grass-1', items: [hall], features: {},
    terrain: [{ texture: 'tex-paving', points: isoRect(courtyard, 30, 30), edgeColor: '#000' }] };
  assert.ok(!validateCampusLayouts({ 10: plaza }).some((m) => m.includes('runs under')), 'a courtyard plaza is not under the building');
});

test('layout validation reports broken layouts with actionable messages', () => {
  const broken: CampusLayout = {
    camera: { cx: 180, horizon: 100, zoom: 2 },
    clouds: [{ asset: 'cloud-1', x: 100, y: 200 }],
    ground: 'tent',
    terrain: [{ texture: 'tex-dirt', points: [[0.5, 120], [10, 120]], edgeColor: '#000' }],
    items: [{ asset: 'tex-grass-1', x: 180, y: 150 }, { asset: 'tent', x: 900, y: 150 }],
    features: { '빈 풀밭 부지': 'tex-grass-1' },
  };
  const problems = validateCampusLayouts({ 1: broken });
  for (const fragment of ['ground tent is not a texture', 'at least 3 points', 'not an integer', 'tex-grass-1 is not a sprite', 'outside the camera view', 'outside the sky band', '"나무 푯말" has no asset']) {
    assert.ok(problems.some((p) => p.includes(fragment)), `expected a problem containing "${fragment}" in ${JSON.stringify(problems)}`);
  }
});

// Regression sensors for the placement defects in docs/261108-0128-campus_scene_layout_fix_plan.md 5-1.
test('validation rejects a path under a building and a prop standing inside one (P3)', () => {
  const lv2 = CAMPUS_LAYOUTS[2]!;
  const hall = lv2.items.find((i) => i.asset === 'hall-small')!;
  const through: CampusLayout = { ...lv2, terrain: [...lv2.terrain, { texture: 'tex-paving', points: isoStrip([hall.x - 60, hall.y - 50], [hall.x + 60, hall.y + 10], 8), edgeColor: '#000' }] };
  assert.ok(validateCampusLayouts({ 2: through }).some((p) => p.includes('path runs under hall-small')));
  const fp = footprintOf(hall)!;
  const [cx, cy] = mid(fp.left, fp.right);
  const inside: CampusLayout = { ...lv2, items: [...lv2.items, { asset: 'bench', x: Math.round(cx), y: Math.round(cy) }] };
  assert.ok(validateCampusLayouts({ 2: inside }).some((p) => p.includes('bench') && p.includes('stands inside hall-small')));
});

test('validation rejects stage features hidden by the in-game labels, guestbook or tap hint (P5)', () => {
  // Lv.1 before the fix: the tent sat right under the tap hint ("터치!").
  const lv1 = CAMPUS_LAYOUTS[1]!;
  const view = campusView(lv1.camera);
  const [l, , r, b] = CAMPUS_UI_OVERLAYS['demo-hand'];
  const hidden = { asset: 'tent' as const, x: Math.round(view.x + ((l + r) / 2) * view.width), y: Math.round(view.y + b * view.height) };
  const problems = validateCampusLayouts({ 1: { ...lv1, items: lv1.items.map((i) => (i.asset === 'tent' ? hidden : i)) } });
  assert.ok(problems.some((p) => p.includes('"임시 본부 텐트"') && p.includes('visible')), JSON.stringify(problems));
  // Lv.4 before the fix: the pond (a terrain feature) lay under the guestbook board.
  const lv4 = CAMPUS_LAYOUTS[4]!;
  const v4 = campusView(lv4.camera);
  const [gl, gt, gr, gb] = CAMPUS_UI_OVERLAYS.guestbook;
  const pondUnderBoard = lv4.terrain.map((tr) => (tr.texture === 'tex-water'
    ? { ...tr, points: [[gl, gt], [gr, gt], [gr, gb], [gl, gb]].map(([fx, fy]) => [Math.round(v4.x + fx * v4.width), Math.round(v4.y + fy * v4.height)] as const) } : tr));
  assert.ok(validateCampusLayouts({ 4: { ...lv4, terrain: pondUnderBoard } }).some((p) => p.includes('"자연 연못"')));
});

test('building footprints follow the drawn front corner, not the bounding-box anchor', () => {
  // hall-extended's front corner is 27px right of its anchor; paths placed at the anchor missed it.
  const f = footprintOf({ asset: 'hall-extended', x: 100, y: 200 })!;
  assert.deepEqual(f.front, [127, 200]);
  const flipped = footprintOf({ asset: 'hall-extended', x: 100, y: 200, flip: true })!;
  assert.deepEqual(flipped.front, [73, 200]);
  assert.ok(flipped.left[0] < flipped.front[0] && flipped.front[0] < flipped.right[0], 'flip keeps left/right on screen sides');
});

test('placements draw back to front by anchor y, then x, then input order', () => {
  const sorted = sortPlacements([{ id: 'a', x: 5, y: 20 }, { id: 'b', x: 9, y: 10 }, { id: 'c', x: 1, y: 10 }, { id: 'd', x: 1, y: 10 }]);
  assert.deepEqual(sorted.map((p) => p.id), ['c', 'd', 'b', 'a']);
  // depth overrides the anchor y for drawing only.
  assert.deepEqual(sortPlacements([{ id: 'hall', x: 5, y: 20 }, { id: 'hedge', x: 1, y: 10, depth: 21 }]).map((p) => p.id), ['hall', 'hedge']);
});

test('the Lv.4 hedge by the main hall draws in front of the hall (user request 2026-11-09)', () => {
  const order = sortPlacements(CAMPUS_LAYOUTS[4]!.items).map((i) => i.asset);
  // Right of the entrance and lower-right wall; the left-wing hedge gave way to the school signboard (2026-11-27).
  assert.equal(order.filter((a) => a === 'hedge').length, 2);
  assert.ok(order.indexOf('hedge') > order.indexOf('main-clocktower'), JSON.stringify(order));
  assert.ok(order.indexOf('signboard') > order.indexOf('main-clocktower'), JSON.stringify(order));
  // At night the window lights sit above the scene, so the hedges and the sign must mask them (lights bled over it).
  const hall = lightOccluders(CAMPUS_LAYOUTS[4]!.items).find((l) => l.item.asset === 'main-clocktower')!;
  assert.equal(hall.occluders.filter((o) => o.asset === 'hedge').length, 2);
  assert.equal(hall.occluders.filter((o) => o.asset === 'signboard').length, 1, 'window lights do not shine through the sign');
  render(React.createElement(CampusSceneLights, { level: 4, opacity: 1, twinkle: false }));
  const lights = document.querySelector('[data-lights="main-clocktower"]')!;
  const maskId = lights.closest('[mask]')!.getAttribute('mask')!.match(/url\(#(.+)\)/)![1];
  assert.ok(document.getElementById(maskId)!.querySelector('[data-occluder="hedge"]'));
});

test('every composed level has night lights from window masks or glow points', () => {
  for (const [level, layout] of Object.entries(CAMPUS_LAYOUTS)) {
    const masks = layout!.items.filter((i) => CAMPUS_ASSETS[i.asset].lightsUrl).length;
    assert.ok(masks + (layout!.glows?.length ?? 0) > 0, `Lv.${level} would be dark at night; add a building with "lights": true in scripts/assets/manifest.json or a glow point`);
  }
});

test('CampusSceneLights overlays each lit building mask at the building position', () => {
  render(React.createElement(CampusSceneLights, { level: 2, opacity: 1, twinkle: false }));
  const layer = document.querySelector('[data-ambience-layer="window"]')!;
  const hall = CAMPUS_LAYOUTS[2]!.items.find((i) => i.asset === 'hall-small')!;
  const mask = layer.querySelector('[data-lights="hall-small"]')!;
  assert.equal(mask.getAttribute('transform'), placementTransform(hall));
  assertAbsent(layer.querySelector('.campus-window-twinkle'));
});

test('CampusScene composes Lv.1 from assets with the stage accessible name', () => {
  render(React.createElement(CampusScene, { level: 1 }));
  const scene = screen.getByRole('img', { name: `캠퍼스 Lv.1 ${CAMPUS_STAGES[0].name}` });
  assert.equal(scene.getAttribute('data-testid'), 'campus-scene');
  const drawn = [...scene.querySelectorAll(':scope > g > [data-asset], :scope > g > [data-wobble] > [data-asset]')].map((g) => g.getAttribute('data-asset'));
  assert.ok(drawn.includes('signboard') && drawn.includes('tent'), JSON.stringify(drawn));
  // The camera frames the level: before layout is measured the viewBox uses the in-game frame aspect.
  const view = campusView(CAMPUS_LAYOUTS[1]!.camera);
  assert.equal(scene.getAttribute('viewBox'), `${view.x} ${view.y} ${view.width} ${view.height}`);
});

test('joined path pieces draw every outline before any fill, so joins show no seam (lead feedback F2)', () => {
  render(React.createElement(CampusScene, { level: 4 }));
  const group = document.querySelector('[data-terrain-group="tex-paving"]')!;
  const kinds = [...group.children].map((el) => (el.hasAttribute('data-terrain-edge') ? 'edge' : 'fill'));
  const pieces = CAMPUS_LAYOUTS[4]!.terrain.filter((t) => t.texture === 'tex-paving').length;
  assert.ok(pieces > 1, 'Lv.4 has several paving pieces');
  assert.deepEqual(kinds, [...Array(pieces).fill('edge'), ...Array(pieces).fill('fill')]);
  // Groups keep first-appearance order: the lawn inside the Lv.8 track draws over the track.
  assert.deepEqual(terrainGroups(CAMPUS_LAYOUTS[8]!.terrain).map((g) => g.texture).slice(0, 2), ['tex-track', 'tex-grass-2']);
});

test('night lights use the same camera as the scene', () => {
  render(React.createElement(CampusScene, { level: 7 }));
  render(React.createElement(CampusSceneLights, { level: 7, opacity: 1, twinkle: false }));
  const scene = document.querySelector('[data-testid="campus-scene"]')!;
  const lights = document.querySelector('[data-ambience-layer="window"] svg')!;
  assert.equal(lights.getAttribute('viewBox'), scene.getAttribute('viewBox'));
});

test('CampusScene falls back to the level screenshot for levels without a layout', () => {
  render(React.createElement(CampusScene, { level: 11 }));
  const shot = screen.getByRole('img', { name: `캠퍼스 Lv.10 ${CAMPUS_STAGES[9].name}` });
  // docs/261110-0747-campus_level_shots_plan.md 5-2: the in-game screenshot, not the 2026-11-06 growth sheet.
  assert.match(shot.getAttribute('src') ?? '', /\/campus-shots\/lv10\.webp$/);
  assertAbsent(document.querySelector('[data-testid="campus-scene"]'));
});

test('effect features must be drawn by a layout effect', () => {
  const layout = CAMPUS_LAYOUTS[10]!;
  assert.deepEqual(validateCampusLayouts({ 10: { ...layout, effects: [] } }).filter((p) => p.includes('effect:fireworks')).length, 1);
  assert.ok(validateCampusLayouts({ 4: { ...CAMPUS_LAYOUTS[4]!, items: [...CAMPUS_LAYOUTS[4]!.items, { asset: 'tree-pine', x: 50, y: 40 }] } })
    .some((p) => p.includes('stands in the sky')));
});

test('sky follows the ambience phase: sun by day, moon and stars at night', (t) => {
  const kst = (hour: number) => Date.UTC(2026, 10, 7, hour - 9, 0, 0);
  t.mock.method(Date, 'now', () => kst(12));
  render(React.createElement(CampusScene, { level: 4 }));
  let sky = document.querySelector('[data-sky]')!;
  assert.equal(sky.getAttribute('data-sky'), 'day');
  assert.ok(sky.querySelector('[data-asset="sun"]'));
  assertAbsent(sky.querySelector('[data-star]'));
  cleanup();
  t.mock.method(Date, 'now', () => kst(22));
  render(React.createElement(CampusScene, { level: 4 }));
  sky = document.querySelector('[data-sky]')!;
  assert.equal(sky.getAttribute('data-sky'), 'night');
  assert.ok(sky.querySelector('[data-asset="moon"]'));
  assert.ok(sky.querySelectorAll('[data-star]').length > 0);
});

test('Lv.10 draws fireworks in the sky and the festival glow; every level shows the sky', () => {
  render(React.createElement(CampusScene, { level: 10 }));
  assert.ok(document.querySelector('[data-sky] [data-effect="fireworks"]'));
  assert.ok(document.querySelector('[data-effect="festival-glow"]'));
  cleanup();
  // User decision D3 (2026-11-08): the sky band shows at every level, not only from Lv.4.
  for (let level = 1; level <= 10; level++) {
    render(React.createElement(CampusScene, { level }));
    assert.ok(document.querySelector('[data-sky] [data-asset="horizon-forest"]'), `Lv.${level} has no sky`);
    cleanup();
  }
});

test('night lights include street lamp glows', () => {
  render(React.createElement(CampusSceneLights, { level: 6, opacity: 1, twinkle: false }));
  const lamps = CAMPUS_LAYOUTS[6]!.items.filter((i) => i.asset === 'lamp').length;
  assert.equal(document.querySelectorAll('[data-glow="lamp"]').length, lamps);
});

// Lv.1-3 props: docs/261110-0031-campus_lv1-3_fill_plan.md (user approval 2026-11-10).
test('wooden lantern posts glow at the hanging lantern, mirrored when flipped', () => {
  const post = { asset: 'lantern-post', x: 100, y: 200 } as const;
  const [plain] = lampGlows([post]);
  const [flipped] = lampGlows([{ ...post, flip: true }]);
  const top = post.y - CAMPUS_ASSETS['lantern-post'].height;
  // The lantern hangs below the arm, not at the pole top like an iron street lamp.
  assert.ok(plain.y - top > 6, `lantern glow ${plain.y - top}px below the top; it should sit at the lantern, not the pole top`);
  assert.ok(plain.x > post.x && flipped.x < post.x, 'the lantern hangs to the arm side and follows a flip');
  assert.equal(plain.x - post.x, post.x - flipped.x);
  render(React.createElement(CampusSceneLights, { level: 3, opacity: 1, twinkle: false }));
  const posts = CAMPUS_LAYOUTS[3]!.items.filter((i) => i.asset === 'lantern-post').length;
  assert.equal(document.querySelectorAll('[data-glow="lantern-post"]').length, posts);
});

test('Lv.1-3 stay humble: wood and dirt only, no iron lamps, paving or fountain (plan 3, D4)', () => {
  for (const level of [1, 2, 3]) {
    const layout = CAMPUS_LAYOUTS[level]!;
    for (const asset of ['lamp', 'lamp-banner', 'fountain', 'bench']) assert.ok(!layout.items.some((i) => i.asset === asset), `Lv.${level} uses ${asset}; keep it for Lv.4 and later`);
    assert.ok(layout.terrain.every((t) => t.texture === 'tex-dirt'), `Lv.${level} uses non-dirt terrain`);
  }
});

test('Lv.1: a founders camp by the tent, survey stakes around an empty lot', () => {
  const layout = CAMPUS_LAYOUTS[1]!;
  for (const asset of ['campfire', 'crates', 'lumber', 'log-bench', 'dog'] as const) assert.ok(layout.items.some((i) => i.asset === asset), `Lv.1 has ${asset}`);
  const fire = layout.items.find((i) => i.asset === 'campfire')!;
  assert.ok(layout.glows?.some((g) => Math.abs(g.x - fire.x) <= 2 && g.y < fire.y && g.y > fire.y - CAMPUS_ASSETS.campfire.height), 'the campfire glows at night');
  const stakes = layout.items.filter((i) => i.asset === 'survey-stake');
  assert.equal(stakes.length, 4);
  // Stakes go round the lot in order; nothing else stands inside it, so the lot still reads as empty grass.
  const lot = stakes.map((s) => [s.x, s.y] as [number, number]);
  const inside = layout.items.filter((i) => i.asset !== 'survey-stake' && pointInPolygon([i.x, i.y], lot));
  assert.deepEqual(inside.map((i) => `${i.asset}@${i.x},${i.y}`), []);
});

test('Lv.2: a dirt walk runs from the yard out of the frame, with the school sign, lantern and bell', () => {
  const layout = CAMPUS_LAYOUTS[2]!;
  const view = campusView(layout.camera);
  const walks = layout.terrain.filter((t) => t.texture === 'tex-dirt' && !t.base);
  assert.ok(walks.some((t) => t.points.some(([x, y]) => x < view.x || x > view.x + view.width || y > view.y + view.height)), 'a dirt walk leaves the frame');
  for (const asset of ['signboard', 'lantern-post', 'school-bell', 'wheelbarrow', 'lumber', 'watering-can'] as const) assert.ok(layout.items.some((i) => i.asset === asset), `Lv.2 has ${asset}`);
  assert.ok(layout.items.filter((i) => i.asset.startsWith('student-')).length >= 3, 'Lv.2 has students');
});

test('Lv.3: lanterns, log benches and a notice board line the dirt paths', () => {
  const layout = CAMPUS_LAYOUTS[3]!;
  assert.ok(layout.items.filter((i) => i.asset === 'lantern-post').length >= 3, 'Lv.3 lantern posts');
  assert.ok(layout.items.filter((i) => i.asset === 'log-bench').length >= 2, 'Lv.3 log benches');
  for (const asset of ['signboard', 'birdhouse', 'bicycle', 'picnic-mat'] as const) assert.ok(layout.items.some((i) => i.asset === asset), `Lv.3 has ${asset}`);
  // One school sign per level: Lv.3 keeps the Lv.2 signboard, standing in front of the hall and drawn over it.
  const signs = layout.items.filter((i) => i.asset === 'signboard' || i.asset === 'notice-board');
  assert.deepEqual(signs.map((i) => i.asset), ['signboard'], 'Lv.3 has exactly one sign, the Lv.2 signboard');
  assert.ok((signs[0].depth ?? signs[0].y) > layout.items[0].y, 'the signboard is drawn in front of the hall');
  assert.equal(signs[0].flip, true, 'signs face the way the school does: the board runs parallel to the door face');
  assert.ok(layout.items.filter((i) => i.asset.startsWith('student-')).length >= 6, 'Lv.3 has more students than Lv.2');
  // Every lantern stands within reach of a path, so it reads as path lighting.
  const paths = layout.terrain.filter((t) => t.texture === 'tex-dirt');
  for (const post of layout.items.filter((i) => i.asset === 'lantern-post')) {
    assert.ok(paths.some((t) => polygonDistance([post.x, post.y], t.points) <= 8), `lantern at ${post.x},${post.y} is far from the paths`);
  }
});

test('Lv.4·5·6도 Lv.3처럼 학교 표지판 하나가 정해진 방향으로 서고 본관보다 앞에 그려진다', () => {
  // Lv.5's sign stands on the lawn toward the library, turned 90 degrees from Lv.4's (user request 2026-11-27).
  for (const [level, flip] of [[4, true], [5, undefined], [6, undefined]] as const) {
    const layout = CAMPUS_LAYOUTS[level]!;
    const hall = layout.items.find((i) => i.asset === 'main-clocktower')!;
    const signs = layout.items.filter((i) => i.asset === 'signboard' || i.asset === 'notice-board');
    assert.deepEqual(signs.map((i) => i.asset), ['signboard'], `Lv.${level} has exactly one sign, the signboard`);
    assert.equal(signs[0].flip, flip, `Lv.${level}: the board's direction`);
    assert.ok((signs[0].depth ?? signs[0].y) > hall.y, `Lv.${level}: the signboard is drawn in front of the main hall`);
  }
});

test('Lv.7·8 학교 표지판은 입구가 오른쪽 면인 학교 건물 앞에서 같은 방향을 보고 그 건물보다 앞에 그려진다', () => {
  for (const [level, building] of [[7, 'lecture-2-r'], [8, 'main-clocktower']] as const) {
    const layout = CAMPUS_LAYOUTS[level]!;
    const hall = layout.items.find((i) => i.asset === building)!;
    const signs = layout.items.filter((i) => i.asset === 'signboard' || i.asset === 'notice-board');
    assert.deepEqual(signs.map((i) => i.asset), ['signboard'], `Lv.${level} has exactly one sign, the signboard`);
    // The door is on the right face, so the board runs along V, unflipped.
    assert.equal(signs[0].flip, undefined, `Lv.${level}: the board runs parallel to the door face`);
    assert.ok((signs[0].depth ?? signs[0].y) > hall.y, `Lv.${level}: the signboard is drawn in front of ${building}`);
  }
});

test('visiting screens show the visited school scene at its score-derived level', async () => {
  const { MockGameProvider } = await import('../src/hooks/useMockGame');
  const { DEMO_VISIT_SCHOOL_ID, getSchoolById, getSchoolViews } = await import('../src/data/schoolDirectory');
  const { getCampusStage } = await import('../src/utils/gameModel');
  // The visited school comes from the shared rows (M2); its level is derived from its score.
  const visited = getSchoolById(getSchoolViews(), DEMO_VISIT_SCHOOL_ID)!;
  const screens: React.FC<{ schoolId: SchoolId }>[] = await Promise.all([
    import('../src/components/VisitingScreen1Phone').then((m) => m.VisitingScreen1Phone),
    import('../src/components/VisitingScreen2Phone').then((m) => m.VisitingScreen2Phone),
  ]);
  assert.equal(visited.campusLevel, getCampusStage(visited.score).level);
  const stage = CAMPUS_STAGES[visited.campusLevel - 1];
  for (const Screen of screens) {
    render(React.createElement(MockGameProvider, null, React.createElement(Screen, { schoolId: visited.id })));
    const scene = screen.getByRole('img', { name: `${visited.name} 캠퍼스 Lv.${stage.level} ${stage.name}` });
    assert.equal(scene.getAttribute('data-testid'), 'campus-scene');
    assert.ok(screen.getAllByText(new RegExp(`Lv\\.${stage.level}`)).length > 0);
    assertAbsent(screen.queryByText(/Lv\.6/), 'fixed Lv.6 label left on a visiting screen');
    cleanup();
  }
});

test('flat frames show the middle of the in-game view; tall frames extend the ground below', () => {
  // Visiting-school banners are ~360x169; framing them from the top showed only sky and roofs.
  const cam = CAMPUS_LAYOUTS[5]!.camera;
  const base = campusView(cam);
  const flat = campusView(cam, 169 / 360);
  assert.equal(flat.skyTop, base.skyTop);
  assert.ok(Math.abs((flat.y + flat.height / 2) - (base.y + base.height / 2)) < 1e-9, 'flat view is centered on the in-game view');
  const tall = campusView(cam, 392 / 358);
  assert.equal(tall.y, base.y, 'tall view keeps the horizon in place');
  assert.ok(tall.height > base.height);
});

// Method B is the default display (docs/261108-1014-campus_hd_default_plan.md 4-2, 4-5): each sprite
// and light mask draws an HD copy smoothed into its 1x box, picked per layer by device ratio, and
// falls back to the 1x file nearest-neighbor when it has no copy or the copy fails to load.
test('the HD scale is the smallest that covers the device ratio; unknown or too large takes the largest', () => {
  const scales = [6, 3];
  assert.equal(hdScale(2.1, scales), 3, 'sky layer at DPR 2');
  assert.equal(hdScale(3, scales), 3, 'exactly covered');
  assert.equal(hdScale(3.01, scales), 6);
  assert.equal(hdScale(6.4, scales), 6, 'Lv.1 at DPR 3 slightly upscales the largest');
  assert.equal(hdScale(0, scales), 6, 'before the frame is measured');
  assert.equal(hdScale(Number.NaN, scales), 6);
  assert.equal(hdScale(4, []), undefined);
});

test('every drawable sprite has HD copies in every scale; textures have none', () => {
  for (const asset of Object.values(CAMPUS_ASSETS)) {
    if (asset.kind === 'texture') { assert.equal(asset.hd, undefined, asset.id); continue; }
    assert.deepEqual(Object.keys(asset.hd ?? {}).map(Number), [3, 6], asset.id);
    for (const [scale, file] of Object.entries(asset.hd!)) {
      assert.match(file.url, new RegExp(`/campus-hd/${scale}/.+/${asset.id}\\.png$`));
      assert.equal(file.lightsUrl !== undefined, asset.lightsUrl !== undefined, `${asset.id} lights`);
    }
  }
  const library = CAMPUS_ASSETS.library;
  assert.equal(hdFileFor(library, 2.9), library.hd![3]);
  assert.equal(hdFileFor({ ...library, hd: undefined }, 2.9), undefined);
});

function hdLevel(level: number) {
  const items = sortPlacements(CAMPUS_LAYOUTS[level]!.items);
  return { items, lit: items.find((i) => CAMPUS_ASSETS[i.asset].lightsUrl)! };
}

test('by default sprites and light masks draw the largest HD copy in the 1x box before the frame is measured', () => {
  const level = 2;
  const { items, lit } = hdLevel(level);
  render(React.createElement(CampusScene, { level }));
  const scene = screen.getByTestId('campus-scene');
  const drawn = [...scene.querySelectorAll(':scope > g:not([data-sky]) > [data-asset], :scope > g > [data-wobble] > [data-asset]')];
  assert.equal(drawn.length, items.length);
  for (const [i, item] of items.entries()) {
    const asset = CAMPUS_ASSETS[item.asset];
    const el = drawn[i];
    assert.equal(el.getAttribute('data-asset'), item.asset);
    assert.equal(el.getAttribute('data-source'), 'hd', item.asset);
    assert.equal(el.getAttribute('href'), asset.hd![6].url);
    assert.equal(el.getAttribute('width'), String(asset.width), 'HD files draw into the 1x box, not at their own size');
    assert.equal(el.getAttribute('height'), String(asset.height));
    assert.equal(el.getAttribute('transform'), placementTransform(item));
    assert.equal((el as SVGElement).style.imageRendering, 'auto');
  }
  // Terrain textures tile at 1x under patterns.
  for (const pattern of scene.querySelectorAll('pattern image')) assert.doesNotMatch(pattern.getAttribute('href')!, /campus-hd/);

  render(React.createElement(CampusSceneLights, { level, opacity: 1, twinkle: false }));
  const mask = document.querySelector(`[data-ambience-layer="window"] [data-lights="${lit.asset}"]`)!;
  assert.equal(mask.getAttribute('data-source'), 'hd');
  assert.equal(mask.getAttribute('href'), CAMPUS_ASSETS[lit.asset].hd![6].lightsUrl);
  assert.equal(mask.getAttribute('transform'), placementTransform(lit));
});

test('each layer picks its HD scale from its own device ratio', (t) => {
  // A 358 px frame at DPR 3: Lv.2 world (zoom 2) needs 5.97 device px per 1x px → 6x; the unzoomed
  // sky needs 2.98 → 3x. Lv.8 (zoom 1.35) needs 4.03 → 6x for the world too.
  class FixedResizeObserver { constructor(private cb: () => void) {} observe() { this.cb(); } disconnect() {} }
  Object.defineProperty(globalThis, 'ResizeObserver', { configurable: true, writable: true, value: FixedResizeObserver });
  t.mock.method(dom.window.Element.prototype, 'getBoundingClientRect', () => ({ width: 358, height: 420, x: 0, y: 0, top: 0, left: 0, right: 358, bottom: 420, toJSON() {} }));
  Object.defineProperty(dom.window, 'devicePixelRatio', { configurable: true, value: 3 });
  t.after(() => { delete (globalThis as { ResizeObserver?: unknown }).ResizeObserver; Object.defineProperty(dom.window, 'devicePixelRatio', { configurable: true, value: 1 }); });
  const { lit } = hdLevel(2);
  render(React.createElement(CampusScene, { level: 2 }));
  render(React.createElement(CampusSceneLights, { level: 2, opacity: 1, twinkle: false }));
  const scene = screen.getByTestId('campus-scene');
  assert.equal(scene.querySelector(`:scope > g > [data-wobble] > [data-asset="${lit.asset}"]`)!.getAttribute('href'), CAMPUS_ASSETS[lit.asset].hd![6].url);
  assert.equal(scene.querySelector('[data-sky] [data-asset="horizon-forest"]')!.getAttribute('href'), CAMPUS_ASSETS['horizon-forest'].hd![3].url);
  assert.equal(document.querySelector(`[data-lights="${lit.asset}"]`)!.getAttribute('href'), CAMPUS_ASSETS[lit.asset].hd![6].lightsUrl);
  cleanup();
  // DPR 1: the world at Lv.2 needs 1.99 → 3x.
  Object.defineProperty(dom.window, 'devicePixelRatio', { configurable: true, value: 1 });
  render(React.createElement(CampusScene, { level: 2 }));
  assert.equal(screen.getByTestId('campus-scene').querySelector(`:scope > g > [data-wobble] > [data-asset="${lit.asset}"]`)!.getAttribute('href'), CAMPUS_ASSETS[lit.asset].hd![3].url);
});

test('a sprite without HD copies draws its 1x file nearest-neighbor', (t) => {
  const { lit } = hdLevel(2);
  const asset = CAMPUS_ASSETS[lit.asset];
  const hd = asset.hd;
  (asset as { hd?: unknown }).hd = undefined;
  t.after(() => { (asset as { hd?: unknown }).hd = hd; });
  render(React.createElement(CampusScene, { level: 2 }));
  render(React.createElement(CampusSceneLights, { level: 2, opacity: 1, twinkle: false }));
  const el = screen.getByTestId('campus-scene').querySelector(`:scope > g > [data-wobble] > [data-asset="${lit.asset}"]`)!;
  assert.equal(el.getAttribute('data-source'), null);
  assert.equal(el.getAttribute('href'), asset.url);
  assert.equal((el as SVGElement).style.imageRendering, 'pixelated');
  assert.equal(document.querySelector(`[data-lights="${lit.asset}"]`)!.getAttribute('href'), asset.lightsUrl);
});

test('an HD file that fails to load falls back to the 1x file with one warning', async (t) => {
  const warn = t.mock.method(console, 'warn', () => {});
  const { lit } = hdLevel(2);
  const asset = CAMPUS_ASSETS[lit.asset];
  render(React.createElement(CampusScene, { level: 2 }));
  render(React.createElement(CampusSceneLights, { level: 2, opacity: 1, twinkle: false }));
  const scene = screen.getByTestId('campus-scene');
  const sprite = () => scene.querySelector(`:scope > g > [data-wobble] > [data-asset="${lit.asset}"]`)!;
  const mask = () => document.querySelector(`[data-lights="${lit.asset}"]`)!;
  await act(async () => { sprite().dispatchEvent(new dom.window.Event('error')); });
  assert.equal(sprite().getAttribute('href'), asset.url);
  assert.equal(sprite().getAttribute('data-source'), null);
  assert.equal((sprite() as SVGElement).style.imageRendering, 'pixelated');
  assert.equal(mask().getAttribute('data-source'), 'hd', 'each image falls back on its own');
  await act(async () => { mask().dispatchEvent(new dom.window.Event('error')); });
  assert.equal(mask().getAttribute('href'), asset.lightsUrl);
  assert.equal(warn.mock.callCount(), 2);
  assert.match(String(warn.mock.calls[0].arguments[0]), /campus-hd\/6\/.+ showing the 1x file/);
});

// The school signboard shows the school's first-ranked department and its score (user request 2026-11-27).
test('표지판에 학교 1위 학과명과 점수를 쓰고, 정보가 없으면 판을 비워 둔다', () => {
  const { container } = render(React.createElement(CampusScene, { level: 3, sign: { name: '국악과', score: 1234567 } }));
  const texts = container.querySelectorAll('[data-sign-text]');
  assert.equal(texts.length, 1, 'one sign, one text');
  assert.equal(container.querySelector('[data-sign-name]')?.textContent, '국악과');
  assert.equal(container.querySelector('[data-sign-score]')?.textContent, '1.2M점');
  assert.ok(texts[0].parentElement?.querySelector('[data-asset="signboard"]'), 'drawn with the sign so it keeps its draw order');
  cleanup();
  const blank = render(React.createElement(CampusScene, { level: 3 }));
  assert.equal(blank.container.querySelectorAll('[data-sign-text]').length, 0);
});

test('표지판 글자는 판 기울기를 따르고, 뒤집힌 표지판에서도 거울글자가 되지 않으며, 긴 학과명일수록 작아진다', () => {
  const info = { name: '국악과', score: 10 };
  const plain = signTextLayout({ asset: 'signboard', x: 100, y: 200 }, info);
  const flipped = signTextLayout({ asset: 'signboard', x: 100, y: 200, flip: true }, info);
  assert.ok(plain.skew < 0 && flipped.skew === -plain.skew, 'the face rises to the right unflipped and falls flipped');
  assert.equal(plain.x - 100, 100 - flipped.x, 'the text sits on the mirrored board face');
  assert.equal(plain.y, flipped.y);
  assert.ok(fitFontSize('컴퓨터소프트웨어학부', 3.4) < fitFontSize('국악과', 3.4), 'a longer name gets a smaller font');
  assert.equal(fitFontSize('국악과', 3.4), 3.4, 'a short name keeps the largest size');
});

test('표지판 학과명이 한 줄로 너무 작아지면 두 줄로 나누고, 짧은 이름은 한 줄로 둔다', () => {
  assert.deepEqual(splitSignName('국악과'), ['국악과']);
  const long = '전기전자컴퓨터공학부소프트웨어전공';
  assert.ok(fitFontSize(long, 3.4) < MIN_NAME_SIZE, 'one line would be too small');
  const lines = splitSignName(long);
  assert.equal(lines.length, 2);
  assert.equal(lines.join(''), long);
  assert.ok(Math.abs([...lines[0]].length - [...lines[1]].length) <= 1, 'halves of about equal width');
  assert.ok(splitSignName('Global Business Administration Department Office').every((line) => !line.startsWith(' ') && !line.endsWith(' ')), 'breaks at a space without dangling spaces');
  const layout = signTextLayout({ asset: 'signboard', x: 0, y: 0 }, { name: long, score: 5 });
  assert.equal(layout.name.lines.length, 2);
  assert.ok(layout.name.size > fitFontSize(long, 3.4), 'two lines are larger than one');
  assert.ok(layout.name.lines[0].dy < layout.name.lines[1].dy && layout.name.lines[1].dy < layout.score!.dy, 'name lines above the score');
  const { container } = render(React.createElement(CampusScene, { level: 3, sign: { name: long, score: 5 } }));
  assert.deepEqual([...container.querySelectorAll('[data-sign-name-line]')].map((n) => n.textContent), lines);
});

test('괄호가 있는 긴 학과명은 괄호 앞만 표지판에 쓰고, 전체 이름은 접근성 제목에 남긴다', () => {
  const long = '음악학과(성악전공, 피아노전공, 작곡전공, 관현악전공, 이론전공)';
  assert.equal(signDisplayName(long), '음악학과');
  assert.equal(signDisplayName('Culture & Technology융합대학[통합모집]'), 'Culture & Technology융합대학');
  assert.equal(signDisplayName('경영학과(야간)'), '경영학과(야간)', 'a short name keeps its brackets');
  assert.equal(signDisplayName('(가칭)신설학부'), '(가칭)신설학부', 'nothing in front of the bracket: keep the whole name');
  const { container } = render(React.createElement(CampusScene, { level: 3, sign: { name: long, score: 12345 } }));
  assert.deepEqual([...container.querySelectorAll('[data-sign-name-line]')].map((n) => n.textContent), ['음악학과']);
  assert.match(container.querySelector('[data-sign-text] title')?.textContent ?? '', /성악전공/);
});

test('1위 학과가 0점이면 표지판에 학과명·점수 대신 "순위 없음"만 쓴다', () => {
  const { container } = render(React.createElement(CampusScene, { level: 3, sign: { name: '환경안전공학과', score: 0 } }));
  assert.deepEqual([...container.querySelectorAll('[data-sign-name-line]')].map((n) => n.textContent), ['순위 없음']);
  assert.equal(container.querySelectorAll('[data-sign-score]').length, 0, 'no score line');
  assert.equal(container.querySelector('[data-sign-text] title')?.textContent, '학과 순위 없음');
});
