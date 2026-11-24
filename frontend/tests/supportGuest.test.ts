import assert from 'node:assert/strict';
import { assertAbsent } from './helpers/domAssert';
import { installDialogStub } from './helpers/dialogStub';
import { after, afterEach, test, type TestContext } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';

// Guest inquiry form with the bot check (SCRUM-122, plan .agents/01-PLAN/261117-0848-turnstile_bot_verification_plan.md §3-4).
// The mockup has no server: the mock server counts this page's inquiries (the real one counts per IP), answers the
// third without a token with 403 ABUSE_VERIFICATION_REQUIRED and the sixth with 429. A fake replaces the Cloudflare script.
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
const { MockGameProvider } = await import('../src/hooks/useMockGame');
const { default: App } = await import('../src/App');
const { MOCK_REQUEST_RULES } = await import('../src/utils/mockRequest');
const { TURNSTILE_TEST_SITEKEY } = await import('../src/utils/turnstile');
type TurnstileApi = import('../src/utils/turnstile').TurnstileApi;
type TurnstileLoader = import('../src/utils/turnstile').TurnstileLoader;

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

function mountApp(loader: TurnstileLoader) {
  render(React.createElement(MockGameProvider, { turnstileLoader: loader, children: React.createElement(App) }));
  clickTestView('📮 비회원 문의');
}
const field = (label: string) => screen.getByLabelText(label) as HTMLInputElement | HTMLTextAreaElement;
const fill = (title = '결제 문의') => {
  fireEvent.change(field('받을 이메일'), { target: { value: 'guest@example.com' } });
  fireEvent.change(field('제목'), { target: { value: title } });
  fireEvent.change(field('본문'), { target: { value: '결제가 두 번 됐어요.' } });
};
const send = () => fireEvent.click(screen.getByRole('button', { name: '보내기' }));
const botCheck = () => screen.queryByTestId('support-bot-check');
const result = () => screen.queryByTestId('support-result')?.textContent ?? '';
const sentCount = () => screen.getByTestId('support-sent-count').textContent ?? '';
const settle = () => act(async () => {});
const setRequestMode = (mode: string) => withToolSetting('[시연] 요청 상태', (control) => fireEvent.change(control, { target: { value: mode } }));

/** Sends one inquiry; from the third on, solves the widget that appears. */
async function sendOne(turnstile: ReturnType<typeof fakeTurnstile>) {
  fill();
  send();
  await settle();
  if (botCheck()) {
    turnstile.solve();
    await settle();
  }
}

function useClock(t: TestContext) {
  t.mock.timers.enable({ apis: ['Date', 'setInterval', 'setTimeout'], now: 1000 });
  return (ms: number) => act(() => t.mock.timers.tick(ms));
}

afterEach(() => cleanup());
after(() => { assetHooks.deregister(); dom.window.close(); });

test('1~2건째는 확인 없이 접수된다', async (t) => {
  useClock(t);
  const turnstile = fakeTurnstile();
  mountApp(() => Promise.resolve(turnstile.api));

  for (const n of [1, 2]) {
    fill();
    send();
    await settle();
    assert.match(result(), /접수되었어요/, `${n}건째`);
  }
  assert.match(sentCount(), /2/);
  assert.equal(turnstile.rendered.length, 0, '위젯을 띄우지 않는다');
  assertAbsent(botCheck());
  assert.equal(field('제목').value, '', '접수되면 제목과 본문을 비운다');
  assert.equal(field('받을 이메일').value, 'guest@example.com', '받을 이메일은 남긴다');
});

test('3건째는 403을 받아 폼 아래에 위젯을 띄우고, 토큰을 받으면 같은 내용을 다시 보낸다', async (t) => {
  useClock(t);
  const turnstile = fakeTurnstile();
  mountApp(() => Promise.resolve(turnstile.api));
  await sendOne(turnstile);
  await sendOne(turnstile);

  fill('세 번째 문의');
  send();
  await settle();
  assert.match(botCheck()?.textContent ?? '', /자동 입력 방지 확인/);
  assert.deepEqual(turnstile.rendered, [{ sitekey: TURNSTILE_TEST_SITEKEY, action: 'support_guest' }]);
  assert.match(sentCount(), /2/, '403은 한도를 쓰지 않는다');
  assert.equal(field('제목').value, '세 번째 문의', '내용은 그대로 둔다');
  assert.equal(field('제목').disabled, true, '확인 중에는 내용을 고칠 수 없다');

  turnstile.solve();
  await settle();
  assert.match(result(), /접수되었어요/);
  assert.match(sentCount(), /3/);
  assertAbsent(botCheck());
  assert.deepEqual(turnstile.removed, ['widget-1'], '접수되면 위젯을 지운다');
  assert.equal(field('제목').disabled, false);
});

test('위젯 스크립트를 불러오지 못하면 빈 토큰으로 다시 보낸다 (계획 P6, 목업 서버는 통과)', async (t) => {
  useClock(t);
  mountApp(() => Promise.reject(new Error('Turnstile 스크립트를 불러오지 못했습니다')));
  for (let i = 0; i < 2; i++) {
    fill();
    send();
    await settle();
  }

  fill();
  send();
  await settle();
  assert.match(result(), /접수되었어요/);
  assert.match(sentCount(), /3/);
  assertAbsent(botCheck());
});

test('3~5건째는 건마다 확인을 거치고, 6건째는 한도에 걸린다', async (t) => {
  useClock(t);
  const turnstile = fakeTurnstile();
  mountApp(() => Promise.resolve(turnstile.api));
  for (let i = 0; i < 5; i++) await sendOne(turnstile);
  assert.match(sentCount(), /5/);
  assert.equal(turnstile.rendered.length, 3, '3·4·5건째마다 위젯');

  fill();
  send();
  await settle();
  assert.match(screen.getByTestId('support-error').textContent ?? '', /1시간에 5건/);
  assertAbsent(botCheck(), '한도를 넘으면 확인을 묻지 않는다');
  assert.equal(turnstile.rendered.length, 3);
  assert.match(sentCount(), /5/);
});

test('토큰을 붙인 요청이 실패하면 다시 시도로 위젯을 초기화해 새 토큰으로 보낸다', async (t) => {
  const tick = useClock(t);
  const turnstile = fakeTurnstile();
  mountApp(() => Promise.resolve(turnstile.api));
  await sendOne(turnstile);
  await sendOne(turnstile);

  fill();
  send();
  await settle();
  setRequestMode('fail-once');
  turnstile.solve();
  assert.equal(screen.getByRole('button', { name: '보내기' }).hasAttribute('disabled'), true, '보내는 중');
  tick(MOCK_REQUEST_RULES.failDelayMs);

  assert.match(screen.getByTestId('support-error').textContent ?? '', /\[시연\] 네트워크 오류/);
  assert.ok(botCheck(), '위젯은 남는다');
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
  assert.deepEqual(turnstile.reset, ['widget-1'], '토큰은 한 번만 쓸 수 있어 위젯을 초기화한다');
  turnstile.solve();
  await settle();
  assert.match(result(), /접수되었어요/);
  assert.match(sentCount(), /3/);
});

test('입력이 틀리면 보내지 않는다', async (t) => {
  useClock(t);
  const turnstile = fakeTurnstile();
  mountApp(() => Promise.resolve(turnstile.api));

  fill(' ');
  send();
  await settle();
  assert.match(screen.getByTestId('support-error').textContent ?? '', /제목을 입력/);
  assert.match(sentCount(), /0/);
});

test('확인을 취소하면 내용을 고칠 수 있고, 시연 초기화는 보낸 수와 위젯을 지운다', async (t) => {
  useClock(t);
  const turnstile = fakeTurnstile();
  mountApp(() => Promise.resolve(turnstile.api));
  await sendOne(turnstile);
  await sendOne(turnstile);

  fill();
  send();
  await settle();
  fireEvent.click(screen.getByRole('button', { name: '확인 취소' }));
  assertAbsent(botCheck());
  assert.equal(field('제목').disabled, false);
  assert.deepEqual(turnstile.removed, ['widget-1']);

  send();
  await settle();
  assert.ok(botCheck(), '다시 보내면 다시 묻는다');
  fireEvent.click(screen.getByRole('button', { name: '[시연] 보낸 문의 수 초기화' }));
  assertAbsent(botCheck());
  assert.match(sentCount(), /0/);

  send();
  await settle();
  assert.match(result(), /접수되었어요/, '초기화 뒤 1건째');
});
