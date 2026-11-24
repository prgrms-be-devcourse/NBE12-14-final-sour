import assert from 'node:assert/strict';
import { assertAbsent } from './helpers/domAssert';
import { installDialogStub } from './helpers/dialogStub';
import { after, afterEach, test, type TestContext } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';

// Click bot check in the app (SCRUM-121, plan .agents/01-PLAN/261117-0848-turnstile_bot_verification_plan.md §3-4).
// The mockup has no server: a demo switch stands in for the 403 ABUSE_VERIFICATION_REQUIRED flag, and the
// verification request goes through the mock request layer. A fake Turnstile replaces the Cloudflare script.
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

const { render, screen, fireEvent, act, cleanup } = await import('@testing-library/react');
const { clickTestView, withToolSetting } = await import('./helpers/mockTools');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { default: App } = await import('../src/App');
const { MOCK_REQUEST_RULES } = await import('../src/utils/mockRequest');
const { TURNSTILE_TEST_SITEKEY } = await import('../src/utils/turnstile');
type TurnstileApi = import('../src/utils/turnstile').TurnstileApi;
type TurnstileLoader = import('../src/utils/turnstile').TurnstileLoader;

type Controller = ReturnType<typeof useMockGame>;
let latest: Controller;
function Probe() {
  latest = useMockGame();
  return null;
}

/** Records render/reset/remove and lets a test hand the widget's token to the page. */
function fakeTurnstile() {
  const rendered: { sitekey: string; action?: string }[] = [];
  const reset: (string | undefined)[] = [];
  const removed: (string | undefined)[] = [];
  let onToken: ((token: string) => void) | undefined;
  const api: TurnstileApi = {
    render(_container, options) {
      rendered.push({ sitekey: options.sitekey, action: options.action });
      onToken = options.callback;
      return `widget-${rendered.length}`;
    },
    reset(id) { reset.push(id); },
    remove(id) { removed.push(id); },
  };
  return { api, rendered, reset, removed, solve: (token = 'XXXX.DUMMY.TOKEN.XXXX') => act(() => onToken?.(token)) };
}

async function mountApp(loader: TurnstileLoader) {
  render(React.createElement(MockGameProvider, {
    turnstileLoader: loader,
    children: React.createElement(React.Fragment, null, React.createElement(App), React.createElement(Probe)),
  }));
  clickTestView('🎮 메인 게임 화면');
}
const flagLabel = '[시연] 어뷰징 의심 표시 (봇 확인 요구)';
const flagChecked = () => withToolSetting(flagLabel, (control) => (control as HTMLInputElement).checked);
const toggleFlag = () => withToolSetting(flagLabel, fireEvent.click);
const campus = () => screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
const dialog = () => screen.queryByTestId('bot-check-dialog');
const settle = () => act(async () => {});
const setRequestMode = (mode: string) => withToolSetting('[시연] 요청 상태', (control) => fireEvent.change(control, { target: { value: mode } }));

function useClock(t: TestContext) {
  t.mock.timers.enable({ apis: ['Date', 'setInterval', 'setTimeout'], now: 1000 });
  return (ms: number) => act(() => t.mock.timers.tick(ms));
}

afterEach(() => cleanup());
after(() => { assetHooks.deregister(); dom.window.close(); });

test('의심 표시가 있으면 클릭을 반영하지 않고 확인 창을 띄운다. 통과하면 표시가 지워지고 바로 다시 클릭된다', async (t) => {
  t.mock.method(Math, 'random', () => 0.9);
  const tick = useClock(t);
  const turnstile = fakeTurnstile();
  await mountApp(() => Promise.resolve(turnstile.api));

  toggleFlag();
  const before = latest.state;
  fireEvent.click(campus());
  await settle();

  assert.deepEqual(latest.state, before, '점수·버프 그대로');
  assert.ok(dialog(), '확인 창이 열린다');
  assert.deepEqual(turnstile.rendered, [{ sitekey: TURNSTILE_TEST_SITEKEY, action: 'abuse_click' }]);

  turnstile.solve();
  await settle();
  assertAbsent(dialog());
  assert.equal(flagChecked(), false, '표시가 지워진다');
  assert.deepEqual(turnstile.removed, ['widget-1'], '창을 닫으며 위젯을 지운다');

  tick(100);
  fireEvent.click(campus());
  assert.ok(latest.state.contribution > before.contribution, '확인 뒤 클릭이 반영된다');
});

test('위젯 스크립트를 불러오지 못하면 위젯 없이 확인을 요청한다 (계획 P6, 목업 서버는 통과)', async (t) => {
  useClock(t);
  await mountApp(() => Promise.reject(new Error('Turnstile 스크립트를 불러오지 못했습니다')));

  toggleFlag();
  fireEvent.click(campus());
  await settle();

  assertAbsent(dialog());
  assert.equal(flagChecked(), false);
});

test('확인 요청이 실패하면 표시를 남기고 다시 시도할 수 있다. 다시 시도는 위젯을 초기화해 새 토큰을 받는다', async (t) => {
  const tick = useClock(t);
  const turnstile = fakeTurnstile();
  await mountApp(() => Promise.resolve(turnstile.api));
  setRequestMode('fail-once');

  toggleFlag();
  fireEvent.click(campus());
  await settle();
  turnstile.solve();
  assert.match(screen.getByTestId('bot-check-status').textContent ?? '', /확인 중/);
  tick(MOCK_REQUEST_RULES.failDelayMs);

  assert.match(screen.getByTestId('bot-check-error').textContent ?? '', /확인 요청이 실패했습니다/);
  assert.equal(flagChecked(), true, '실패하면 표시가 남는다');

  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
  assert.deepEqual(turnstile.reset, ['widget-1'], '토큰은 한 번만 쓸 수 있어 위젯을 초기화한다');
  turnstile.solve();
  await settle();
  assertAbsent(dialog());
  assert.equal(flagChecked(), false);
});

test('확인하지 않고 창을 닫으면 표시가 남아 다음 클릭에서 다시 뜬다', async (t) => {
  t.mock.method(Math, 'random', () => 0.9);
  const tick = useClock(t);
  const turnstile = fakeTurnstile();
  await mountApp(() => Promise.resolve(turnstile.api));

  toggleFlag();
  const before = latest.state;
  fireEvent.click(campus());
  await settle();
  fireEvent.click(screen.getByRole('button', { name: '▼ 터치하여 닫기' }));
  assertAbsent(dialog());
  assert.deepEqual(turnstile.removed, ['widget-1']);

  tick(100);
  fireEvent.click(campus());
  await settle();
  assert.deepEqual(latest.state, before, '닫은 뒤에도 클릭은 반영되지 않는다');
  assert.ok(dialog(), '다시 뜬다');
  assert.equal(turnstile.rendered.length, 2);
});

test('시연 스위치를 끄면 표시와 확인 창이 함께 사라진다', async (t) => {
  useClock(t);
  const turnstile = fakeTurnstile();
  await mountApp(() => Promise.resolve(turnstile.api));

  toggleFlag();
  fireEvent.click(campus());
  await settle();
  toggleFlag();

  assertAbsent(dialog());
  assert.equal(flagChecked(), false);
});
