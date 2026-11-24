import { SCHOOL_ID } from '../src/data/schoolIds';
import assert from 'node:assert/strict';
import { assertAbsent } from './helpers/domAssert';
import { after, afterEach, test, type TestContext } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';

// Click refusal, keyboard press rule, failure/delay/retry and demo grouping in the app (M5).
// Plan: docs/261108-2051-m5_failure_retry_click_guard_plan.md 7-1. jsdom checks flow and state, not layout.
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

const { render, screen, fireEvent, act, cleanup, within } = await import('@testing-library/react');
const { clickTestView, openTestTools, withToolSetting } = await import('./helpers/mockTools');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { default: App } = await import('../src/App');
const { MOCK_CLICK_RULES } = await import('../src/utils/clickGuard');
const { MOCK_REQUEST_RULES, REQUEST_FAILED_MESSAGE } = await import('../src/utils/mockRequest');
const { MOCK_AUTH_RULES } = await import('../src/utils/mockAuth');
const { STORE_ITEMS } = await import('../src/data/gameRules');
const { getSchoolById, getSchoolViews } = await import('../src/data/schoolDirectory');

type Controller = ReturnType<typeof useMockGame>;
let latest: Controller;
function Probe() {
  latest = useMockGame();
  return null;
}
function mountApp() {
  render(React.createElement(MockGameProvider, {
    generateAuthCode: () => '111111',
    children: React.createElement(React.Fragment, null, React.createElement(App), React.createElement(Probe)),
  }));
}
const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));
const campus = () => screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
const setRequestMode = (mode: string) => withToolSetting('[시연] 요청 상태', (control) => fireEvent.change(control, { target: { value: mode } }));
const requestMode = () => withToolSetting('[시연] 요청 상태', (control) => (control as HTMLSelectElement).value);
const toggleRefusal = () => withToolSetting('[시연] 서버 클릭 거절 응답', fireEvent.click);
const COFFEE = STORE_ITEMS.find((item) => item.id === 'coffee')!;

function useClock(t: TestContext) {
  t.mock.timers.enable({ apis: ['Date', 'setInterval', 'setTimeout'], now: 1000 });
  return (ms: number) => act(() => t.mock.timers.tick(ms));
}

afterEach(() => cleanup());
after(() => { assetHooks.deregister(); dom.window.close(); });

test('기준 3: 시연 거절 응답이 켜지면 점수·버프가 그대로이고, 끄면 바로 정상으로 돌아온다', (t) => {
  t.mock.method(Math, 'random', () => 0.9);
  const tick = useClock(t);
  mountApp();
  clickTestView('🎮 메인 게임 화면');
  act(() => { latest.buy('coffee'); });
  const before = latest.state;

  toggleRefusal();
  tick(100);
  fireEvent.click(campus());
  assert.deepEqual(latest.state, before, '개인·학교·학과 점수와 버프 그대로');
  assert.match(screen.getByTestId('click-rejected-notice').textContent ?? '', /\[시연\] 서버가 클릭을 거절했습니다/);
  assert.ok(screen.getByText('거절'), '눌린 자리에 거절 표시');

  toggleRefusal();
  assertAbsent(screen.queryByTestId('click-rejected-notice'));
  tick(100);
  fireEvent.click(campus());
  assert.ok(latest.state.contribution > before.contribution, '토글을 끄면 클릭이 반영된다');
  assert.equal(latest.state.contribution - before.contribution, latest.state.schoolScore - before.schoolScore);
});

test('기준 3: 간격이 너무 짧은 클릭은 반영하지 않고, 차단 시간이 지나면 다시 반영한다', (t) => {
  t.mock.method(Math, 'random', () => 0.9);
  const tick = useClock(t);
  mountApp();
  clickTestView('🎮 메인 게임 화면');
  fireEvent.click(campus());
  const afterFirst = latest.state;
  fireEvent.click(campus());
  assert.deepEqual(latest.state, afterFirst, '같은 시각 두 번째 클릭은 거절');
  assert.match(screen.getByTestId('click-rejected-notice').textContent ?? '', /클릭 간격이 너무 짧아 반영하지 않았습니다\. 3초 후/);

  tick(1000);
  fireEvent.click(campus());
  assert.deepEqual(latest.state, afterFirst, '차단 중에는 간격이 충분해도 거절');
  assert.match(screen.getByTestId('click-rejected-notice').textContent ?? '', /2초 후/, '차단 이유는 처음 거절 이유를 유지');

  tick(MOCK_CLICK_RULES.blockMs);
  assertAbsent(screen.queryByTestId('click-rejected-notice'), '차단이 끝나면 안내가 사라진다');
  fireEvent.click(campus());
  assert.ok(latest.state.contribution > afterFirst.contribution, '정상 복귀');
});

test('키보드: 눌렀다 뗄 때 한 번만 반영하고, 누르고 있는 동안 반복 입력은 막는다', (t) => {
  t.mock.method(Math, 'random', () => 0.9);
  const tick = useClock(t);
  mountApp();
  clickTestView('🎮 메인 게임 화면');
  const start = latest.state.contribution;
  const area = campus();

  fireEvent.keyDown(area, { key: 'Enter' });
  for (let i = 0; i < 5; i++) {
    tick(100); // well apart, so only the press rule can refuse these
    fireEvent.keyDown(area, { key: 'Enter', repeat: true });
  }
  assert.equal(latest.state.contribution, start, '누르고 있는 동안은 0회');
  fireEvent.keyUp(area, { key: 'Enter' });
  const once = latest.state.contribution - start;
  assert.ok(once > 0, '떼는 순간 1회');

  tick(100);
  fireEvent.keyUp(area, { key: 'Enter' });
  assert.equal(latest.state.contribution - start, once, '누르지 않고 뗀 것은 클릭이 아니다');

  tick(100);
  fireEvent.keyDown(area, { key: ' ' });
  fireEvent.blur(area);
  fireEvent.keyUp(area, { key: ' ' });
  assert.equal(latest.state.contribution - start, once, '누른 채 포커스를 잃으면 그 입력은 버린다');

  tick(100);
  fireEvent.keyDown(area, { key: ' ' });
  fireEvent.keyUp(area, { key: ' ' });
  assert.equal(latest.state.contribution - start, once * 2, '스페이스도 눌렀다 떼면 1회');
});

test('기준 2: 지연 중 구매를 두 번 눌러도 기여도는 한 번만 줄어든다', (t) => {
  const tick = useClock(t);
  mountApp();
  setRequestMode('delay');
  clickTestView('🏪 상점 & 강화 (Single Phone)');
  const before = latest.state.contribution;
  click('커피 한 잔 구매 선택');
  act(() => {
    fireEvent.click(screen.getByRole('button', { name: '구매 및 사용' }));
    fireEvent.click(screen.getByRole('button', { name: '구매 및 사용' }));
  });
  const pendingButton = screen.getByRole('button', { name: /요청 중/ }) as HTMLButtonElement;
  assert.equal(pendingButton.disabled, true, '대기 중 버튼 비활성');
  assert.equal(latest.state.contribution, before, '지연 중에는 아직 반영 전');

  tick(MOCK_REQUEST_RULES.delayMs);
  assert.equal(latest.state.contribution, before - COFFEE.cost, '한 번만 차감');
  assert.equal(latest.state.activeBuff?.itemId, 'coffee');
  assertAbsent(screen.queryByRole('dialog', { name: '아이템 구매 확인' }), '성공하면 확인 창이 닫힌다');
});

test('기준 1·2: 구매가 1회 실패하면 기여도는 그대로이고, 다시 누르면 한 번만 반영된다', (t) => {
  const tick = useClock(t);
  mountApp();
  setRequestMode('fail-once');
  clickTestView('🏪 상점 & 강화 (Single Phone)');
  const before = latest.state.contribution;
  click('커피 한 잔 구매 선택');
  click('구매 및 사용');
  tick(MOCK_REQUEST_RULES.failDelayMs);
  assert.match(within(screen.getByRole('dialog', { name: '아이템 구매 확인' })).getByRole('alert').textContent ?? '', /구매 요청이 실패했습니다/);
  assert.equal(latest.state.contribution, before);
  assert.equal(latest.state.activeBuff?.itemId, undefined, '버프도 적용되지 않는다');
  assert.equal(requestMode(), 'normal', '1회 실패 뒤 정상 모드');

  click('구매 및 사용');
  assert.equal(latest.state.contribution, before - COFFEE.cost);
  assert.equal(latest.state.activeBuff?.itemId, 'coffee');
});

test('기준 1·2: 코드 확인 요청이 실패하면 시도 횟수를 쓰지 않고 입력이 남으며, 다시 시도하면 진행된다', (t) => {
  const tick = useClock(t);
  mountApp();
  clickTestView('📱 순차 온보딩 (Single Phone)');
  const phone = () => within(screen.getByRole('region', { name: '순차 온보딩' }));
  fireEvent.change(phone().getByLabelText('학교 이메일 주소'), { target: { value: 'student@kaist.ac.kr' } });
  click('인증 코드 받기');
  setRequestMode('fail-once');
  '111111'.split('').forEach((digit, index) => {
    fireEvent.change(phone().getByLabelText(`인증 코드 ${index + 1}번째 자리`), { target: { value: digit } });
  });
  click('코드 확인');
  assert.equal((phone().getByRole('button', { name: /요청 중/ }) as HTMLButtonElement).disabled, true);
  tick(MOCK_REQUEST_RULES.failDelayMs);
  assert.equal(phone().getByRole('alert').textContent, REQUEST_FAILED_MESSAGE);
  assert.equal(phone().getByTestId('attempts-left').textContent, `남은 시도 ${MOCK_AUTH_RULES.maxAttempts}회`, '실패한 요청은 시도 횟수를 쓰지 않는다');
  assert.equal((phone().getByLabelText('인증 코드 6번째 자리') as HTMLInputElement).value, '1', '입력 보존');

  click('코드 확인');
  assert.match(phone().getByTestId('verified-school').textContent ?? '', /KAIST/);
});

test('기준 1: 순위·지도·방문 조회 실패를 고정 조작으로 재현하고 다시 시도·복귀로 정상 화면에 돌아온다', (t) => {
  const tick = useClock(t);
  mountApp();

  setRequestMode('fail');
  clickTestView('🏆 랭킹 화면 (Single Phone)');
  assert.match(screen.getByRole('status').textContent ?? '', /순위 불러오는 중/);
  tick(MOCK_REQUEST_RULES.failDelayMs);
  assert.match(screen.getByRole('alert').textContent ?? '', /순위 조회에 실패했습니다/);
  click('다시 시도');
  tick(MOCK_REQUEST_RULES.failDelayMs);
  assert.match(screen.getByRole('alert').textContent ?? '', /순위 조회에 실패했습니다/, '계속 실패 모드');
  setRequestMode('normal');
  click('다시 시도');
  assert.ok(screen.getByRole('heading', { name: '전국 랭킹 대항전' }), '정상 화면');

  setRequestMode('fail-once');
  clickTestView('🗺️ 단일 폰 지도');
  tick(MOCK_REQUEST_RULES.failDelayMs);
  assert.match(screen.getByRole('alert').textContent ?? '', /학교 지도 조회에 실패했습니다/);
  click('다시 시도');
  const pusan = getSchoolById(getSchoolViews(), SCHOOL_ID.pusan)!;
  assert.ok(screen.getByRole('button', { name: `${pusan.name} 방문` }), '1회 실패 뒤 다시 시도하면 지도');

  setRequestMode('fail');
  click(`${pusan.name} 방문`);
  tick(MOCK_REQUEST_RULES.failDelayMs);
  assert.match(screen.getByRole('alert').textContent ?? '', /방문 학교 조회에 실패했습니다/);
  setRequestMode('normal');
  click('지도로 돌아가기');
  assert.ok(screen.getByRole('button', { name: `${pusan.name} 방문` }), '실패 화면에서 지도로 복귀');

  // The ranking tab inside the game screen fails in place and leads back to the campus.
  clickTestView('🎮 메인 게임 화면');
  setRequestMode('fail');
  click('순위');
  tick(MOCK_REQUEST_RULES.failDelayMs);
  assert.match(screen.getByRole('alert').textContent ?? '', /순위 조회에 실패했습니다/);
  setRequestMode('normal');
  click('캠퍼스로');
  assert.ok(campus(), '캠퍼스로 복귀');
});

test('기준 4: 테스트 도구를 열면 서비스 흐름과 시안 비교가 나뉘고 닫으면 둘 다 사라진다', () => {
  mountApp();
  assertAbsent(screen.queryByRole('navigation', { name: '서비스 흐름' }));
  const sheet = openTestTools();
  const service = within(sheet.getByRole('navigation', { name: '서비스 흐름' }));
  for (const name of ['🎮 메인 게임 화면', '📱 순차 온보딩 (Single Phone)', '🗺️ 단일 폰 지도', '🏆 랭킹 화면 (Single Phone)', '🏪 상점 & 강화 (Single Phone)']) {
    assert.ok(service.getByRole('button', { name }), name);
  }
  // The menu's accessible name is its visible heading (M5 이연 #6).
  const demoNav = screen.getByRole('navigation', { name: '[시연] 시안 비교·도구' });
  assert.equal(demoNav.querySelector('span')!.textContent, demoNav.getAttribute('aria-label'));
  const demo = within(demoNav);
  for (const name of ['3단 온보딩 화면', '🏛️ 10단계 캠퍼스 성장 시트 (5x2)', '📱📱 2-Phone 지도 비교', '🖼️ 배경 맵 일러스트 (1080x1600)']) {
    assert.ok(demo.getByRole('button', { name }), name);
  }
  assertAbsent(service.queryByRole('button', { name: '3단 온보딩 화면' }));
  const tools = within(screen.getByRole('region', { name: '[시연] 도구' }));
  assert.ok(tools.getByLabelText('[시연] 요청 상태'));
  assert.ok(tools.getByLabelText('[시연] 서버 클릭 거절 응답'));
  clickTestView('🎮 메인 게임 화면');
  assertAbsent(screen.queryByRole('navigation', { name: '[시연] 시안 비교·도구' }));
  assert.ok(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }));
});
