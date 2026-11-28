import { SCHOOL_ID, type SchoolId } from '../src/data/schoolIds';
import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { assertAbsent } from './helpers/domAssert';

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
const { MapSceneViewport } = await import('../src/components/MapSceneViewport');
const { Screen1NationalMap } = await import('../src/components/Screen1NationalMap');
const { Screen2RegionMap } = await import('../src/components/Screen2RegionMap');
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

const sceneCallbacks = { onSelectSchool: () => {}, onClusterSelect: () => {}, serviceLayout: false };
const renderScene = (region: (typeof MAP_REGIONS)[number], scene: React.ReactNode) =>
  render(React.createElement(MockGameProvider, null, React.createElement(MapSceneViewport, { region, animated: false, children: scene })));

test('공동 상위 학교가 많아도 권역마다 PC·모바일 점수판은 세 개다', () => {
  const schools = getSchoolViews().map(school => ({ ...school, rank: 1, score: 0, isTop3: true }));
  for (const region of ['수도권', '충청'] as const) {
    const regionSchools = filterSchools(schools, region, '');
    for (const embedded of [true, false]) {
      const props = { ...sceneCallbacks, embedded };
      const view = renderScene(region, region === '충청'
        ? React.createElement(Screen2RegionMap, { ...props, regionSchools })
        : React.createElement(Screen1NationalMap, { ...props, schools, regionSchools, region }));
      const area = mapArea();
      const ribbons = embedded ? '.game-map-pin-rank' : 'button[data-top-rank]';
      assert.equal(area.querySelectorAll('button[data-school-id]').length, regionSchools.length);
      assert.deepEqual([...area.querySelectorAll<HTMLElement>(ribbons)].map(node => Number(node.dataset.pinId ?? node.dataset.schoolId)),
        regionSchools.slice(0, 3).map(school => school.id));
      view.unmount();
    }
  }
});

test('좌표 없는 상위 학교와 미집계 학교를 제외하고 후보 수만큼 실제 순위 장식을 표시한다', () => {
  const base = getSchoolViews();
  const capital = filterSchools(base, '수도권', '');
  for (const count of [0, 1, 2, 4]) {
    const schools = [
      { ...capital[0], id: 999999, isTop3: true, rank: 1 },
      ...base.map(school => ({ ...school, isTop3: capital.slice(0, count).some(item => item.id === school.id), rank: capital.slice(0, count).some(item => item.id === school.id) ? 2 : 77 })),
    ];
    for (const workspace of [true, false]) {
      const props = { ...sceneCallbacks, schools, region: '수도권' as const, regionSchools: filterSchools(schools, '수도권', ''), embedded: workspace };
      const view = renderScene(props.region, React.createElement(Screen1NationalMap, props));
      const markers = [...screen.getByTestId('map-area').querySelectorAll('button[data-top-rank]')];
      assert.deepEqual(markers.map(node => Number((node as HTMLElement).dataset.schoolId)), capital.slice(0, Math.min(count, 3)).map(school => school.id));
      assert.ok(markers.every(node => node.getAttribute('data-top-rank') === '2'));
      assert.equal(screen.getByTestId('map-area').querySelectorAll('button[data-school-id]').length, capital.length);
      view.unmount();
    }
  }
});

test('전국 지도는 좌표 없는 후보를 제외한 뒤 최대 세 학교를 표시한다', () => {
  const base = getSchoolViews();
  const schools = [{ ...base[0], id: 999999, rank: 1, isTop3: true }, ...base.map(school => ({ ...school, rank: 1, isTop3: true }))];
  for (const workspace of [true, false]) {
    const props = { ...sceneCallbacks, schools, region: '전체' as const, regionSchools: schools, embedded: workspace };
    const view = renderScene(props.region, React.createElement(Screen1NationalMap, props));
    const markers = [...screen.getByTestId('map-area').querySelectorAll('button[data-top-rank]')];
    assert.deepEqual(markers.map(node => Number((node as HTMLElement).dataset.schoolId)), base.slice(0, 3).map(school => school.id));
    view.unmount();
  }
});

test('7개 권역 칩이 모두 동작하고 지도와 목록이 같은 권역을 보여 준다', () => {
  mount();
  assertAbsent(screen.queryByRole('button', { name: '지방 선택하기' }));
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
  const topBefore = markerIds();
  fireEvent.change(input, { target: { value: 'kaist' } });
  assert.deepEqual(markerIds(), topBefore, '검색은 지도 하이라이트를 변경하지 않는다');
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
