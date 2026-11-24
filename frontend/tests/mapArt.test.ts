// T7 (docs/261110-1005-mockup_remaining_tasks_plan.md §10-1, Q9 option C): the map is drawn from layers cut out of
// the original JPG. Pixel agreement with the original is checked by `npm run map:check` (Python, needs the decoder);
// the browser check of positions and reduced motion is tests/e2e/map-art.mjs.
import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import { readFileSync, statSync } from 'node:fs';
import React from 'react';
import { JSDOM } from 'jsdom';

const assetHooks = registerHooks({
  load(url, context, nextLoad) {
    if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) {
      const match = new URL(url).pathname.match(/\/src\/assets\/.+$/);
      return { format: 'module', source: `export default ${JSON.stringify(match ? match[0] : '/asset')};`, shortCircuit: true };
    }
    return nextLoad(url, context);
  },
});
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({
  window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node,
  requestAnimationFrame: dom.window.requestAnimationFrame.bind(dom.window),
  cancelAnimationFrame: dom.window.cancelAnimationFrame.bind(dom.window),
  IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
dom.window.HTMLElement.prototype.scrollIntoView = function () {};

const { render, screen, cleanup } = await import('@testing-library/react');
const { MockGameProvider } = await import('../src/hooks/useMockGame');
const { MapScreenPhone } = await import('../src/components/MapScreenPhone');
const { MAP_REGIONS, MAP_DESIGN_FRAME_PX } = await import('../src/data/schoolMapLayout');
const { MAP_CLOUDS, MAP_IMAGE_BOX, CLOUD_DRIFT_SECONDS } = await import('../src/components/MapArt');
const layers = JSON.parse(readFileSync(new URL('../src/assets/images/map/layers.json', import.meta.url), 'utf8'));

afterEach(() => cleanup());
after(() => { assetHooks.deregister(); dom.window.close(); });

const MAP_DIR = new URL('../src/assets/images/map/', import.meta.url);
const ORIGINAL = new URL('../src/assets/images/korea_pixel_map_1793879803724.jpg', import.meta.url);
const LAYER_FILES = ['land.png', 'islands.png', 'cloud-bank-north.png', ...layers.clouds.map((c: { id: string }) => `${c.id}.png`)];

test('레이어 파일은 육지·섬·북쪽 구름띠·자유 구름 3개이고, 합계가 원본 JPG 용량 이하다', () => {
  assert.deepEqual(layers.clouds.map((c: { id: string }) => c.id), ['clouds-right', 'clouds-left', 'clouds-bottom-right']);
  const total = LAYER_FILES.reduce((sum, name) => sum + statSync(new URL(name, MAP_DIR)).size, 0);
  assert.ok(total <= statSync(ORIGINAL).size, `${total} > ${statSync(ORIGINAL).size}`);
  assert.deepEqual([layers.width, layers.height], [848, 1264], '원본과 같은 캔버스라 % 좌표가 그대로 맞는다');
});

test('이미지 상자는 옛 object-cover 상자와 같다: 설계 프레임을 덮고, 원본 비율이며, 가운데 정렬이다', () => {
  const { width: fw, height: fh } = MAP_DESIGN_FRAME_PX;
  const box = MAP_IMAGE_BOX;
  const close = (a: number, b: number, label: string) => assert.ok(Math.abs(a - b) < 1e-9, `${label}: ${a} vs ${b}`);
  close((box.width / 100) * fw / ((box.height / 100) * fh), layers.width / layers.height, '비율');
  assert.ok(box.left <= 0 && box.top <= 0 && box.left + box.width >= 100 && box.top + box.height >= 100, JSON.stringify(box));
  close(box.left + box.width / 2, 50, '가로 중앙');
  close(box.top + box.height / 2, 50, '세로 중앙');
  assert.ok(Math.abs(box.left) < 1e-9 || Math.abs(box.top) < 1e-9, '한 방향은 꼭 맞고 다른 방향만 잘린다');
});

test('구름은 60~120초에 지도를 서→동으로 한 번 건너고, 시작 위치는 원본 자리다', () => {
  assert.equal(MAP_CLOUDS.length, 3);
  for (const cloud of MAP_CLOUDS) {
    assert.ok(cloud.seconds >= 60 && cloud.seconds <= 120, `${cloud.id}: ${cloud.seconds}s`);
    assert.equal(cloud.seconds, CLOUD_DRIFT_SECONDS[cloud.id]);
    assert.equal(cloud.from, -cloud.width, `${cloud.id}: 완전히 서쪽 밖에서 들어온다`);
    assert.equal(cloud.to, 100, `${cloud.id}: 완전히 동쪽 밖으로 나간다`);
    assert.ok(cloud.rest > cloud.from && cloud.rest < cloud.to, `${cloud.id}: 원본 자리는 경로 위에 있다`);
    // At t = 0 the animation is at from + (-delay / seconds) * (to - from): the source spot.
    const atStart = cloud.from + (-cloud.delaySeconds / cloud.seconds) * (cloud.to - cloud.from);
    assert.ok(Math.abs(atStart - cloud.rest) < 1e-9, `${cloud.id}: ${atStart} vs ${cloud.rest}`);
    const source = layers.clouds.find((c: { id: string }) => c.id === cloud.id);
    assert.ok(Math.abs(cloud.rest - (source.x / layers.width) * 100) < 1e-9);
    assert.ok(Math.abs(cloud.top - (source.y / layers.height) * 100) < 1e-9);
  }
});

test('7개 권역 모두 지도 그림이 레이어로 그려지고, 원본 JPG를 쓰지 않으며, 첫 <img>가 육지다', () => {
  for (const region of MAP_REGIONS) {
    render(React.createElement(MockGameProvider, null, React.createElement(MapScreenPhone, { initialRegion: region, onSelectSchool: () => {} })));
    const area = screen.getByTestId('map-area');
    const art = area.querySelectorAll('[data-map-art]');
    assert.equal(art.length, 1, region);
    assert.equal(art[0].getAttribute('role'), 'img', region);
    assert.ok(art[0].getAttribute('aria-label'), region);
    const images = Array.from(area.querySelectorAll('img'));
    // tests/e2e/map-layout.mjs measures marker positions against the first image of the area.
    assert.equal(images[0]?.getAttribute('data-map-layer-art'), 'land', region);
    assert.deepEqual(images.filter((img) => /korea_pixel_map/.test(img.getAttribute('src') ?? '')).map((img) => img.getAttribute('src')), [], region);
    assert.deepEqual(images.map((img) => img.getAttribute('alt')), images.map(() => ''), `${region}: 장식 레이어는 이름이 없고 그림 이름은 컨테이너 하나에 있다`);
    assert.equal(area.querySelectorAll('[data-map-cloud]').length, 3, region);
    assert.equal(area.querySelectorAll('[data-map-sea-glint]').length, 2, region);
    cleanup();
  }
});

/** Every rule body for `selector` in index.css, split by whether it sits inside a reduced-motion block. */
function cssRules() {
  const css = readFileSync(new URL('../src/index.css', import.meta.url), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const reduced: string[] = [];
  const re = /@media\s*\(prefers-reduced-motion:\s*reduce\)\s*\{([\s\S]*?\})\s*\}/g;
  for (const m of css.matchAll(re)) reduced.push(m[1]);
  return { css, reduced: reduced.join('\n') };
}

test('reduced motion이면 바다 반짝임과 구름 흐름이 멈춘다(index.css)', () => {
  const { css, reduced } = cssRules();
  const animated = [...new Set([...css.matchAll(/\.(map-[\w-]+)\s*\{[^}]*animation(?:-name)?\s*:/g)].map((m) => m[1]))];
  assert.deepEqual(animated.sort(), ['map-cloud-drift', 'map-sea-glint'], '움직이는 지도 클래스가 늘면 reduced motion 목록도 확인한다');
  for (const name of animated) {
    const rule = [...reduced.matchAll(/([^{}]+)\{([^}]*)\}/g)].find((m) => m[1].split(',').map((s) => s.trim()).includes(`.${name}`));
    assert.ok(rule, `.${name}이 reduced motion 블록에 없다`);
    assert.match(rule[2], /animation\s*:\s*none/, `.${name}`);
  }
  // At rest the cloud rows keep their inline transform: the source spots, not the drift's start off the map.
  render(React.createElement(MockGameProvider, null, React.createElement(MapScreenPhone, { initialRegion: '전체', onSelectSchool: () => {} })));
  for (const cloud of MAP_CLOUDS) {
    const row = screen.getByTestId('map-area').querySelector<HTMLElement>(`[data-map-cloud="${cloud.id}"]`);
    const percent = Number(row?.style.transform.match(/^translateX\((-?[\d.]+)%\)$/)?.[1]);
    assert.ok(Math.abs(percent - cloud.rest) < 1e-3, `${cloud.id}: ${row?.style.transform} vs ${cloud.rest}%`);
  }
});
