// T6 (docs/261110-1005-mockup_remaining_tasks_plan.md §9): only buildings wobble on a tap (Q8); the click area stays still.
import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { REDUCED_MOTION_QUERY, stubMatchMedia } from './helpers/matchMedia';
import { CAMPUS_ASSET_MANIFEST } from '../src/assets/campus/manifest';

const hooks = registerHooks({ load(url, context, nextLoad) {
  if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) return { format: 'module', source: `export default ${JSON.stringify(url)};`, shortCircuit: true };
  return nextLoad(url, context);
} });
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator, HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, requestAnimationFrame: dom.window.requestAnimationFrame.bind(dom.window), cancelAnimationFrame: dom.window.cancelAnimationFrame.bind(dom.window), IS_REACT_ACT_ENVIRONMENT: true })) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });

const { render, screen, fireEvent, cleanup } = await import('@testing-library/react');
const { CAMPUS_ASSETS, isBuilding } = await import('../src/data/campusAssets');
const { getCampusLayout, sortPlacements } = await import('../src/data/campusLayouts');
const { CampusScene, CampusSceneLights } = await import('../src/components/CampusScene');
const { CampusView } = await import('../src/components/CampusView');
const { MockGameProvider } = await import('../src/hooks/useMockGame');
const { CampusAmbienceProvider } = await import('../src/hooks/useCampusTimePhase');
const { wobbleBuildings, WOBBLE_SELECTOR, WOBBLE_STAGGER_MS } = await import('../src/utils/campusWobble');

afterEach(() => cleanup());
after(() => { hooks.deregister(); dom.window.close(); });

/** Buildings per level, Lv.1-10 (plan §9, counted by running the layouts). */
const BUILDINGS_PER_LEVEL = [1, 1, 1, 1, 2, 2, 3, 2, 3, 1];
const BUILDING_IDS = ['dorm-a', 'dorm-b', 'grand-hall', 'hall-extended', 'hall-small', 'lecture-2', 'lecture-2-r', 'lecture-3', 'library', 'library-r', 'main-clocktower', 'sports-hall', 'stadium', 'student-hall', 'tent', 'twin-tower'];

/** JSDOM has no Web Animations API: record each call and whether a later tap cancelled it. */
function stubAnimate() {
  const calls: { el: Element; delay: number; cancelled: boolean }[] = [];
  const proto = dom.window.Element.prototype as unknown as { animate?: unknown };
  proto.animate = function (this: Element, _frames: Keyframe[], options: KeyframeAnimationOptions) {
    const call = { el: this, delay: Number(options.delay ?? 0), cancelled: false };
    calls.push(call);
    return { cancel: () => { call.cancelled = true; } };
  };
  return { calls, restore: () => { delete proto.animate; } };
}

test('isBuilding은 매니페스트 buildings/ 16종과 정확히 같고, 입구·창문 불빛이 있는 에셋은 모두 건물이다', () => {
  const buildings = Object.values(CAMPUS_ASSETS).filter(isBuilding).map((a) => a.id).sort();
  assert.deepEqual(buildings, BUILDING_IDS, '건물 목록이 바뀌면 T6 흔들림 대상도 확인하고 이 목록을 갱신한다');
  for (const [id, entry] of Object.entries(CAMPUS_ASSET_MANIFEST)) {
    assert.equal(isBuilding(CAMPUS_ASSETS[id as keyof typeof CAMPUS_ASSETS]), entry.file.startsWith('buildings/'), id);
  }
  // A building-like asset placed in another folder would miss the wobble: entrances and window lights belong to buildings.
  for (const asset of Object.values(CAMPUS_ASSETS)) {
    if (asset.door || asset.lightsUrl) assert.ok(isBuilding(asset), `${asset.id}: 입구나 창문 불빛이 있으면 buildings/에 둔다`);
  }
  assert.equal(isBuilding(CAMPUS_ASSETS.fountain), false, '분수는 footprint가 있지만 objects/라 건물이 아니다');
});

test('Lv.1~10 장면은 건물마다 흔들림 래퍼 하나를 두고, 나무·소품·사람·분수에는 두지 않는다', () => {
  for (let level = 1; level <= 10; level++) {
    render(React.createElement(CampusScene, { level }));
    const wrappers = Array.from(document.querySelectorAll('[data-wobble]'));
    assert.equal(wrappers.length, BUILDINGS_PER_LEVEL[level - 1], `Lv.${level}`);
    for (const wrapper of wrappers) {
      const images = Array.from(wrapper.querySelectorAll('[data-asset]')).map((e) => e.getAttribute('data-asset'));
      assert.deepEqual(images, [wrapper.getAttribute('data-wobble')], `Lv.${level}: 래퍼에는 그 건물 스프라이트만 있다`);
      assert.ok(isBuilding(CAMPUS_ASSETS[images[0] as keyof typeof CAMPUS_ASSETS]), `Lv.${level} ${images[0]}`);
    }
    const sprites = Array.from(document.querySelectorAll('[data-asset]')).filter((e) => !e.closest('[data-sky]'));
    const outside = sprites.filter((e) => !e.closest('[data-wobble]')).map((e) => e.getAttribute('data-asset')!);
    assert.deepEqual(outside.filter((id) => isBuilding(CAMPUS_ASSETS[id as keyof typeof CAMPUS_ASSETS])), [], `Lv.${level}: 래퍼 밖 건물 없음`);
    cleanup();
  }
});

test('밤 창문 불빛은 건물과 같은 순번·지연으로 함께 흔들린다(Lv.1~10)', () => {
  const { calls, restore } = stubAnimate();
  try {
    for (let level = 1; level <= 10; level++) {
      calls.length = 0;
      const { container } = render(React.createElement('div', null,
        React.createElement(CampusScene, { level }),
        React.createElement(CampusSceneLights, { level, opacity: 1, twinkle: false })));
      const layout = getCampusLayout(level);
      assert.ok(layout, `Lv.${level} 레이아웃`);
      const lit = sortPlacements(layout.items).filter((item) => CAMPUS_ASSETS[item.asset].lightsUrl).length;
      const lights = Array.from(container.querySelectorAll('[data-wobble-lights]'));
      assert.equal(lights.length, lit, `Lv.${level}: 불빛 래퍼 수 = 창문 불빛이 있는 건물 수`);

      assert.equal(wobbleBuildings(container, false), BUILDINGS_PER_LEVEL[level - 1] + lit, `Lv.${level}: 건물과 불빛에만 애니메이션`);
      assert.ok(calls.every((c) => c.el.matches(WOBBLE_SELECTOR)), `Lv.${level}`);
      const delayOf = (selector: string, index: string) => calls.find((c) => c.el.matches(selector) && c.el.getAttribute('data-item-index') === index)?.delay;
      for (const light of lights) {
        const index = light.getAttribute('data-item-index')!;
        const building = container.querySelector(`[data-wobble][data-item-index="${index}"]`);
        assert.equal(building?.getAttribute('data-wobble'), light.getAttribute('data-wobble-lights'), `Lv.${level}: 불빛 순번 ${index}의 건물이 같다`);
        assert.equal(delayOf('[data-wobble-lights]', index), delayOf('[data-wobble]', index), `Lv.${level}: 같은 지연`);
      }
      const delays = [...new Set(calls.map((c) => c.delay))].sort((a, b) => a - b);
      assert.deepEqual(delays, Array.from({ length: BUILDINGS_PER_LEVEL[level - 1] }, (_, k) => k * WOBBLE_STAGGER_MS), `Lv.${level}: 건물 순서대로 ${WOBBLE_STAGGER_MS}ms씩`);
      cleanup();
    }
  } finally { restore(); }
});

function renderCampusView() {
  return render(React.createElement(MockGameProvider, null, React.createElement(CampusAmbienceProvider, null,
    React.createElement(CampusView, { onTap: () => {}, clickPower: 1, basePower: 1, activeStudents: 1 }))));
}

test('캠퍼스를 누르면 건물 래퍼만 흔들리고 클릭 영역은 클래스·transform이 그대로이며, 연타는 이전 애니메이션을 취소한다', () => {
  stubMatchMedia(dom.window as unknown as Window, []);
  const { calls, restore } = stubAnimate();
  try {
    renderCampusView();
    const area = screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
    const before = { className: area.className, transform: area.style.transform };
    assert.doesNotMatch(before.className, /translate-[xy]-\[2px\]|transition-transform/, '눌림 이동 효과 없음');

    fireEvent.click(area);
    assert.deepEqual({ className: area.className, transform: area.style.transform }, before, '누른 직후에도 클릭 영역은 움직이지 않는다');
    const targets = area.querySelectorAll(WOBBLE_SELECTOR).length;
    assert.ok(area.querySelectorAll('[data-wobble]').length > 0);
    assert.equal(calls.length, targets);
    assert.ok(calls.every((c) => c.el.matches(WOBBLE_SELECTOR) && area.contains(c.el)));

    fireEvent.click(area);
    assert.equal(calls.length, targets * 2);
    assert.ok(calls.slice(0, targets).every((c) => c.cancelled), '연타하면 이전 애니메이션을 취소해 쌓이지 않는다');
    assert.ok(calls.slice(targets).every((c) => !c.cancelled));
  } finally { restore(); }
});

test('reduced motion이면 흔들지 않는다', () => {
  stubMatchMedia(dom.window as unknown as Window, [REDUCED_MOTION_QUERY]);
  const { calls, restore } = stubAnimate();
  try {
    renderCampusView();
    fireEvent.click(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }));
    assert.equal(calls.length, 0);
  } finally { restore(); }
});
