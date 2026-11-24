import assert from 'node:assert/strict';
import { test } from 'node:test';
import { JSDOM } from 'jsdom';

// Turnstile script loader and constants (SCRUM-121). jsdom does not fetch scripts, so each test fires the
// script's load or error event itself. Values checked against Cloudflare docs (testing page, 2026-11-05;
// client-side rendering page, 2026-11-16).
const {
  createScriptTurnstileLoader, TURNSTILE_SCRIPT_URL, TURNSTILE_TEST_SITEKEY, TURNSTILE_ACTIONS, turnstileSitekey,
} = await import('../src/utils/turnstile');

const fakeApi = { render: () => 'widget-1', reset: () => {}, remove: () => {} };

function page() {
  const dom = new JSDOM('<!doctype html><html><head></head><body></body></html>', { url: 'http://localhost' });
  return { dom, doc: dom.window.document, scripts: () => dom.window.document.querySelectorAll('script') };
}

test('공식 주소의 explicit 스크립트를 한 번만 넣고, 불러오면 window.turnstile을 돌려준다', async (t) => {
  const { dom, doc, scripts } = page();
  t.after(() => dom.window.close());
  const load = createScriptTurnstileLoader(doc, 1000);

  const first = load();
  const second = load();
  assert.equal(scripts().length, 1, '동시에 불러도 스크립트는 하나');
  const script = scripts()[0];
  assert.equal(script.src, TURNSTILE_SCRIPT_URL);
  assert.equal(script.async, true);

  Object.assign(dom.window, { turnstile: fakeApi });
  script.dispatchEvent(new dom.window.Event('load'));
  assert.equal(await first, fakeApi);
  assert.equal(await second, fakeApi);
  assert.equal(await load(), fakeApi, '불러온 뒤에는 스크립트를 다시 넣지 않는다');
  assert.equal(scripts().length, 1);
});

test('이미 window.turnstile이 있으면 스크립트를 넣지 않는다', async (t) => {
  const { dom, doc, scripts } = page();
  t.after(() => dom.window.close());
  Object.assign(dom.window, { turnstile: fakeApi });

  assert.equal(await createScriptTurnstileLoader(doc, 1000)(), fakeApi);
  assert.equal(scripts().length, 0);
});

test('스크립트를 못 불러오면 실패하고, 실패한 스크립트를 지워 다음 호출이 다시 시도한다', async (t) => {
  const { dom, doc, scripts } = page();
  t.after(() => dom.window.close());
  const load = createScriptTurnstileLoader(doc, 1000);

  const failed = load();
  scripts()[0].dispatchEvent(new dom.window.Event('error'));
  await assert.rejects(failed, /Turnstile/);
  assert.equal(scripts().length, 0);

  const retried = load();
  assert.equal(scripts().length, 1, '다시 부르면 새 스크립트');
  Object.assign(dom.window, { turnstile: fakeApi });
  scripts()[0].dispatchEvent(new dom.window.Event('load'));
  assert.equal(await retried, fakeApi);
});

test('불러왔는데 window.turnstile이 없으면 실패한다', async (t) => {
  const { dom, doc, scripts } = page();
  t.after(() => dom.window.close());
  const loading = createScriptTurnstileLoader(doc, 1000)();

  scripts()[0].dispatchEvent(new dom.window.Event('load'));
  await assert.rejects(loading, /Turnstile/);
});

test('정해진 시간 안에 불러오지 못하면 실패한다 (위젯 없이 확인을 요청하는 경로, 계획 P6)', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { dom, doc, scripts } = page();
  t.after(() => dom.window.close());
  const loading = createScriptTurnstileLoader(doc, 8000)();

  t.mock.timers.tick(8000);
  await assert.rejects(loading, /Turnstile/);
  assert.equal(scripts().length, 0);
});

test('sitekey는 VITE_TURNSTILE_SITEKEY를 쓰고, 없거나 비었으면 Cloudflare 테스트 키(항상 통과)를 쓴다', () => {
  assert.equal(TURNSTILE_TEST_SITEKEY, '1x00000000000000000000AA');
  assert.equal(turnstileSitekey({ VITE_TURNSTILE_SITEKEY: '0x4AAAAAAA' }), '0x4AAAAAAA');
  assert.equal(turnstileSitekey({ VITE_TURNSTILE_SITEKEY: '  ' }), TURNSTILE_TEST_SITEKEY);
  assert.equal(turnstileSitekey({}), TURNSTILE_TEST_SITEKEY);
  assert.equal(turnstileSitekey(undefined), TURNSTILE_TEST_SITEKEY);
});

test('action은 서버 상수와 같고 Cloudflare 규칙(32자 이하, 영문·숫자·_·-)을 지킨다', () => {
  assert.deepEqual(TURNSTILE_ACTIONS, { click: 'abuse_click', supportGuest: 'support_guest' });
  for (const action of Object.values(TURNSTILE_ACTIONS)) assert.match(action, /^[A-Za-z0-9_-]{1,32}$/);
});
