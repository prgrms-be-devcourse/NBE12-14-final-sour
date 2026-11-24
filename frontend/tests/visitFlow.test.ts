import { SCHOOL_ID, type SchoolId } from '../src/data/schoolIds';
import assert from 'node:assert/strict';
import { assertAbsent, assertSameNode } from './helpers/domAssert';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { installDialogStub } from './helpers/dialogStub';

// Map → visit → return (M2). Plan: docs/261108-2000-m2_map_visit_return_plan.md 7-1.
// jsdom checks routing and state; layout and real taps still need a browser check.
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
installDialogStub(dom.window);

const { render, screen, fireEvent, act, cleanup, within } = await import('@testing-library/react');
const { clickTestView } = await import('./helpers/mockTools');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { default: App } = await import('../src/App');
const { VisitingScreen1Phone } = await import('../src/components/VisitingScreen1Phone');
const { INITIAL_GAME_STATE } = await import('../src/utils/gameModel');
const { DEMO_VISIT_SCHOOL_ID, filterSchools, getSchoolById, getSchoolViews } = await import('../src/data/schoolDirectory');

type Controller = ReturnType<typeof useMockGame>;
type GameState = Controller['state'];
let latest: Controller;
function Probe() {
  latest = useMockGame();
  return null;
}
const SINGLE_USE = [{ id: 'single-use-fixture', name: 'test', description: '', cost: 0, iconType: 'pass' as const, multiplier: 1, bonus: 50, durationMs: 300000, singleUse: true }];

function mountApp(initialState: GameState = INITIAL_GAME_STATE, extraBuffs: typeof SINGLE_USE = []) {
  render(React.createElement(MockGameProvider, { initialState, extraBuffs,
    children: React.createElement(React.Fragment, null, React.createElement(App), React.createElement(Probe)) }));
}
const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));
const chip = (name: string) => within(screen.getByRole('group', { name: '권역 선택' })).getByRole('button', { name });
const searchBox = () => screen.getByRole('textbox', { name: '학교 검색' }) as HTMLInputElement;
const visitHeading = (name: string) => screen.getByRole('heading', { level: 1, name });
const views = getSchoolViews();
const view = (id: SchoolId) => getSchoolById(views, id)!;

afterEach(() => cleanup());
after(() => { assetHooks.deregister(); dom.window.close(); });

test('실제 경로: 캠퍼스 지도 탭 → 단일 지도 → 방문 → 지도 복귀 시 권역·검색·선택이 그대로다', () => {
  mountApp();
  clickTestView('🎮 메인 게임 화면');
  click('지도');
  click('전국 지도');
  assert.equal(screen.getAllByTestId('map-area').length, 1, '단일 지도로 들어간다(D1)');

  fireEvent.click(chip('영남'));
  fireEvent.change(searchBox(), { target: { value: '부산' } });
  const pusan = view(SCHOOL_ID.pusan);
  click(`${pusan.name} 방문`);

  visitHeading(pusan.name);
  assert.equal(screen.getByTestId('visit-rank').textContent?.trim(), `${pusan.rank}위`);
  assert.equal(screen.getByTestId('visit-score').textContent, `${pusan.score.toLocaleString('ko-KR')}점`);
  // Contributors moved from the panels under the campus into the 학교 정보 sheet (V2, Q2).
  fireEvent.click(screen.getByTestId('visit-info-button'));
  const info = screen.getByRole('dialog', { name: `${pusan.name} 학교 정보` });
  assert.ok(within(info).getByText('기여 랭커 정보 없음 (목업)'), '고정 랭커 대신 빈 상태(D2)');
  assert.equal(within(info).getByTestId('visit-info-score').textContent, `${pusan.score.toLocaleString('ko-KR')}점`);
  assertAbsent(screen.queryByText(/푸른바다|등대지기/));
  fireEvent.click(within(info).getByRole('button', { name: /터치하여 닫기/ }));
  assertAbsent(screen.queryByRole('dialog', { name: `${pusan.name} 학교 정보` }));

  click('지도로 돌아가기');
  assert.equal(chip('영남').getAttribute('aria-pressed'), 'true');
  assert.equal(searchBox().value, '부산');
  const marker = within(screen.getByTestId('map-area')).getByRole('button', { name: new RegExp(`^${pusan.name} 전국`) });
  assert.equal(marker.getAttribute('aria-pressed'), 'true');
});

test('A 방문 → 지도 → B 방문에서 학교 정보가 바뀌고 내 학교로 돌아가면 캠퍼스가 열린다', () => {
  mountApp();
  clickTestView('🗺️ 단일 폰 지도');
  fireEvent.click(chip('영남'));
  const [a, b] = filterSchools(views, '영남', '');
  click(`${a.name} 방문`);
  visitHeading(a.name);
  click('지도로 돌아가기');
  click(`${b.name} 방문`);
  visitHeading(b.name);
  assertAbsent(screen.queryByRole('heading', { level: 1, name: a.name }));
  assert.equal(screen.getByTestId('visit-rank').textContent?.trim(), `${b.rank}위`);
  click('내 학교로 돌아가기');
  assert.ok(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }));
});

test('지도에서 내 학교를 고르면 방문 화면이 아니라 내 캠퍼스로 간다', () => {
  mountApp();
  clickTestView('🗺️ 단일 폰 지도');
  click('한양대학교 방문');
  assert.ok(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }));
  assertAbsent(screen.queryByText('다른 학교는 구경만 할 수 있어요'));
});

test('방문 화면의 방명록은 같은 학교 이름으로 열리고 닫으면 같은 학교로 돌아온다 (D3)', () => {
  mountApp();
  clickTestView('🗺️ 단일 폰 지도');
  const kaist = view(SCHOOL_ID.kaist);
  click(`${kaist.name} 방문`);
  const trigger = screen.getByRole('button', { name: `${kaist.name} 방명록 보기` });
  fireEvent.click(trigger);
  const dialog = screen.getByRole('dialog', { name: `${kaist.name} 방명록` });
  assert.ok(within(dialog).getByText(`${kaist.name} 방명록`));
  assert.ok(dialog.contains(document.activeElement));
  fireEvent.click(within(dialog).getByTitle('방명록 닫기'));
  visitHeading(kaist.name);
  assertSameNode(document.activeElement, trigger, '방문 화면을 유지해 연 버튼으로 복귀');
});

test('학교 정보 시트의 방명록 전체보기는 정보 시트를 닫고 같은 학교 방명록을 연다 (V2, Q2)', () => {
  mountApp();
  clickTestView('🗺️ 단일 폰 지도');
  const kaist = view(SCHOOL_ID.kaist);
  click(`${kaist.name} 방문`);
  fireEvent.click(screen.getByTestId('visit-info-button'));
  const info = screen.getByRole('dialog', { name: `${kaist.name} 학교 정보` });
  assert.equal(within(info).getAllByTestId('guestbook-preview-note').length, 2);
  fireEvent.click(within(info).getByRole('button', { name: '방명록 전체보기' }));
  assertAbsent(screen.queryByRole('dialog', { name: `${kaist.name} 학교 정보` }));
  assert.ok(screen.getByRole('dialog', { name: `${kaist.name} 방명록` }));
});

test('방문 방명록 새 글은 좋아요 0·미선택으로 시작하고 명시적 좋아요만 반영한다', () => {
  mountApp();
  clickTestView('🗺️ 단일 폰 지도');
  click(`${view(SCHOOL_ID.kaist).name} 방문`);
  click(`${view(SCHOOL_ID.kaist).name} 방명록 보기`);
  const dialog = screen.getByRole('dialog', { name: `${view(SCHOOL_ID.kaist).name} 방명록` });
  fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: '새 방명록 좋아요 회귀 검사' } });
  fireEvent.click(within(dialog).getByRole('button', { name: '남기기' }));
  const message = within(dialog).getByText('새 방명록 좋아요 회귀 검사');
  const entry = message.parentElement!;
  const like = within(entry).getByRole('button');
  assert.equal(like.textContent?.trim(), '0', '글 작성이 좋아요 클릭을 대신하지 않는다');
  assert.equal(like.querySelector('svg')?.classList.contains('fill-[#e11d48]'), false);
  fireEvent.click(like);
  assert.equal(like.textContent?.trim(), '1');
  assert.equal(like.querySelector('svg')?.classList.contains('fill-[#e11d48]'), true);
  fireEvent.click(like);
  assert.equal(like.textContent?.trim(), '0');
  assert.equal(like.querySelector('svg')?.classList.contains('fill-[#e11d48]'), false);
  assert.equal(within(dialog).getAllByText('새 방명록 좋아요 회귀 검사').length, 1);
});

test('공통 목록에 없는 학교 ID는 다른 학교로 바꾸지 않고 안내와 지도 복귀만 보인다', () => {
  let backToMap = 0;
  render(React.createElement(MockGameProvider, null,
    React.createElement(VisitingScreen1Phone, { schoolId: 999_999, onBackToMap: () => { backToMap++; } })));
  assert.match(screen.getByRole('alert').textContent ?? '', /학교를 찾을 수 없습니다/);
  assertAbsent(screen.queryByRole('heading', { level: 1 }));
  click('지도로 돌아가기');
  assert.equal(backToMap, 1);
});

test('방문 화면의 순위·점수는 내 점수가 바뀌어 순위가 밀리면 함께 바뀐다', () => {
  const chonnam = view(SCHOOL_ID.chonnam);
  render(React.createElement(MockGameProvider, { initialState: { ...INITIAL_GAME_STATE, schoolScore: 3_000_000 },
    children: React.createElement(VisitingScreen1Phone, { schoolId: SCHOOL_ID.chonnam }) }));
  assert.equal(chonnam.rank, 3);
  assert.equal(screen.getByTestId('visit-rank').textContent?.trim(), '4위');
});

test('관전 클릭은 시간을 고정하면 게임 상태를 바꾸지 않고 1회 버프도 소비하지 않는다; 복귀 후 첫 클릭이 소비한다', (t) => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval', 'setTimeout'], now: 1000 });
  t.mock.method(Math, 'random', () => 0.9);
  const initial = { ...INITIAL_GAME_STATE, activeBuff: { itemId: 'single-use-fixture', expiresAt: 300000 } };
  mountApp(initial, SINGLE_USE);
  clickTestView('🗺️ 단일 폰 지도');
  const pusan = view(SCHOOL_ID.pusan);
  click(`${pusan.name} 방문`);
  const before = latest.state;
  const campus = screen.getByRole('button', { name: `${pusan.name} 캠퍼스 (관전 모드)` });
  for (let i = 0; i < 5; i++) fireEvent.click(campus);
  assert.ok(screen.getByText('타 대학 캠퍼스는 클릭할 수 없습니다!'));
  assert.deepEqual(latest.state, before, '점수·기여도·클리커·버프 그대로');
  assert.equal(latest.state.activeBuff?.itemId, 'single-use-fixture');

  click('내 학교로 돌아가기');
  assert.deepEqual(latest.state, before, '이동만으로는 상태가 바뀌지 않는다');
  fireEvent.click(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }));
  assert.equal(latest.state.contribution - before.contribution, 80, '복귀 후 첫 클릭은 1회 버프를 적용해 득점');
  assert.equal(latest.state.activeBuff, null);
});

test('방문 중에도 시간 버프는 정상 만료되고 이동이 만료 시각을 바꾸지 않는다', (t) => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval', 'setTimeout'], now: 1000 });
  mountApp();
  act(() => { latest.buy('coffee'); });
  const expiresAt = latest.state.activeBuff?.expiresAt;
  assert.equal(expiresAt, 31000);
  clickTestView('🗺️ 단일 폰 지도');
  click(`${view(SCHOOL_ID.kaist).name} 방문`);
  act(() => { t.mock.timers.tick(10000); });
  assert.equal(latest.state.activeBuff?.expiresAt, expiresAt);
  click('지도로 돌아가기');
  click(`${view(SCHOOL_ID.kaist).name} 방문`);
  assert.equal(latest.state.activeBuff?.expiresAt, expiresAt);
  act(() => { t.mock.timers.tick(21000); });
  assert.equal(latest.state.activeBuff, null);
});

test('2-Phone 방문 비교 화면은 실제 학교를 보이고 관전 클릭이 점수를 바꾸지 않는다 (D4)', () => {
  mountApp();
  clickTestView('📖 2-Phone 방문교 방명록');
  const demo = view(DEMO_VISIT_SCHOOL_ID);
  const before = latest.state;
  fireEvent.click(screen.getByRole('button', { name: `${demo.name} 캠퍼스 (관전 모드)` }));
  assert.deepEqual(latest.state, before);
  assert.ok(screen.getByText(`${demo.name} 방명록`));
  assertAbsent(screen.queryByText(/푸른바다/));
  click('지도');
  assert.equal(screen.getAllByTestId('map-area').length, 1, '방문 화면의 지도 이동은 단일 지도로 간다(D1)');
});
