import { SCHOOL_ID, type SchoolId } from '../src/data/schoolIds';
import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';

// Map DOM integration (M1). Layout, image cropping and real tap targets still need a browser check.
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

const { render, screen, fireEvent, cleanup, within } = await import('@testing-library/react');
const { MockGameProvider } = await import('../src/hooks/useMockGame');
const { MapScreenPhone } = await import('../src/components/MapScreenPhone');
const { MAP_REGIONS } = await import('../src/data/schoolMapLayout');
const { filterSchools, getSchoolViews } = await import('../src/data/schoolDirectory');

afterEach(() => cleanup());
after(() => { assetHooks.deregister(); dom.window.close(); });

function mount(initialRegion: (typeof MAP_REGIONS)[number] = '전체') {
  const selected: SchoolId[] = [];
  render(React.createElement(MockGameProvider, null,
    React.createElement(MapScreenPhone, { initialRegion, onSelectSchool: (id: SchoolId) => selected.push(id) })));
  return selected;
}
const chip = (name: string) => within(screen.getByRole('group', { name: '권역 선택' })).getByRole('button', { name });
const mapArea = () => screen.getByTestId('map-area');
const resultCount = () => screen.getByTestId('school-result-count').textContent;
const listIds = () => Array.from(screen.getByRole('list', { name: '학교 목록' }).querySelectorAll('li[data-school-id]')).map((li) => Number(li.getAttribute('data-school-id')));
const markerIds = () => Array.from(mapArea().querySelectorAll('button[data-school-id]')).map((b) => Number(b.getAttribute('data-school-id')));

test('7개 권역 칩이 모두 동작하고 지도와 목록이 같은 권역을 보여 준다', () => {
  mount();
  const views = getSchoolViews();
  for (const region of MAP_REGIONS) {
    fireEvent.click(chip(region));
    assert.equal(chip(region).getAttribute('aria-pressed'), 'true', region);
    assert.equal(mapArea().getAttribute('data-region'), region);
    const expected = filterSchools(views, region, '').map((school) => school.id);
    assert.equal(resultCount(), `${expected.length}개교`, region);
    assert.deepEqual(listIds(), expected, region);
    if (region === '전체') {
      assert.deepEqual(markerIds(), [SCHOOL_ID.kaist, SCHOOL_ID.pusan, SCHOOL_ID.chonnam]);
    } else {
      assert.deepEqual([...markerIds()].sort(), [...expected].sort(), `${region} markers`);
    }
  }
});

test('전국 지도의 권역 버블은 계산된 학교 수를 보이고 누른 권역으로 확대한다', () => {
  mount();
  const gyeongsang = screen.getByRole('button', { name: '경상 대학 11개, 영남 확대' }); // DGIST added with the V2 seed (SCRUM-149)
  assert.match(gyeongsang.textContent ?? '', /경상 11/);
  fireEvent.click(gyeongsang);
  assert.equal(mapArea().getAttribute('data-region'), '영남');
  assert.equal(chip('영남').getAttribute('aria-pressed'), 'true');
  fireEvent.click(chip('전체'));
  fireEvent.click(screen.getByRole('button', { name: /^충북 대학 \d+개, 충청 확대$/ }));
  assert.equal(mapArea().getAttribute('data-region'), '충청');
  assert.equal(markerIds().length, 24);
});

test('내 학교 태그는 공통 순위의 순위를 보인다', () => {
  mount();
  assert.equal(screen.getByTestId('my-school-tag').textContent, '내 학교 한양대 · 18위');
  assert.ok(screen.getByRole('button', { name: /^서울 대학 11개/ })); // 국민대 added with the V2 seed (SCRUM-149)
});

test('검색은 결과 수를 갱신하고 결과가 없으면 안내와 지우기 버튼을 보인다', () => {
  mount();
  const input = screen.getByRole('textbox', { name: '학교 검색' });
  fireEvent.change(input, { target: { value: 'kaist' } });
  assert.equal(resultCount(), '1개교');
  assert.deepEqual(listIds(), [SCHOOL_ID.kaist]);
  fireEvent.change(input, { target: { value: '없는학교' } });
  assert.equal(resultCount(), '0개교');
  const status = screen.getByRole('status');
  assert.match(status.textContent ?? '', /‘없는학교’에 맞는 학교가 없습니다/);
  fireEvent.click(within(status).getByRole('button', { name: '검색어 지우기' }));
  assert.equal(resultCount(), '76개교');
  assert.equal((input as HTMLInputElement).value, '');
});

test('마커·목록 방문·TOP 3 리본이 같은 학교 ID를 전달한다', () => {
  const selected = mount();
  fireEvent.click(within(mapArea()).getByRole('button', { name: 'KAIST 전국 1위' }));
  fireEvent.click(chip('수도권'));
  fireEvent.click(within(mapArea()).getByRole('button', { name: /^한양대학교 전국 18위$/ }));
  fireEvent.click(screen.getByRole('button', { name: '한양대학교 방문' }));
  fireEvent.click(chip('충청'));
  fireEvent.click(within(mapArea()).getByRole('button', { name: /^충남대학교 전국 \d+위$/ }));
  assert.deepEqual(selected, [SCHOOL_ID.kaist, SCHOOL_ID.hanyang, SCHOOL_ID.hanyang, SCHOOL_ID.cnu]);
  const marker = within(mapArea()).getByRole('button', { name: /^충남대학교 전국/ });
  assert.equal(marker.getAttribute('aria-pressed'), 'true');
});

test('지도 영역 높이는 마커 검사가 덮는 비율 범위로 묶이고, 헤더와 지도만 스크롤되며 목록 시트는 밖에 남는다 (T2)', async () => {
  const { MAP_AREAS_PX } = await import('../src/data/schoolMapLayout');
  mount();
  for (const region of ['전체', '충청'] as const) {
    fireEvent.click(chip(region));
    const area = mapArea();
    const classes = area.className.split(/\s+/);
    assert.ok(classes.includes('flex-1'), region);
    // Marker visibility depends only on the area's aspect (mapLayerFit), so the bounds must equal the checked extremes.
    const ratio = (prefix: string) => {
      const match = classes.find((c) => c.startsWith(prefix))?.match(/^[\w-]+\[calc\(100cqw\*(\d+)\/(\d+)\)\]$/);
      assert.ok(match, `${region}: ${prefix} missing`);
      return Number(match[1]) / Number(match[2]);
    };
    const aspects = MAP_AREAS_PX.map(({ width, height }) => height / width);
    assert.equal(ratio('min-h-['), Math.min(...aspects), `${region}: shortest checked aspect`);
    assert.equal(ratio('max-h-['), Math.max(...aspects), `${region}: tallest checked aspect`);
    const body = screen.getByTestId('map-scroll-body');
    assert.ok(body.contains(area) && body.contains(screen.getByRole('group', { name: '권역 선택' })), `${region}: header and map scroll together`);
    assert.ok(body.classList.contains('@container') && body.classList.contains('overflow-y-auto'), region);
    assert.ok(!body.contains(screen.getByTestId('school-list-sheet')), `${region}: list sheet and nav stay outside`);
  }
});
