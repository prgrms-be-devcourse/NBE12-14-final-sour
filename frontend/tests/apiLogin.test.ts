import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { assertAbsent, assertSameNode } from './helpers/domAssert';

// Login screen, logout and the session restore in API mode (SCRUM-152) against a fake backend. jsdom checks the flow;
// the cookie round trip across a reload is the browser check against the real backend.
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

const { render, screen, fireEvent, cleanup, act, within } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createApiFaults } = await import('../src/api/faults');
const { createQueryClient } = await import('../src/api/queryClient');
const { ApiLoginPhone, ApiSessionWatcher, ApiAccountNavLabel } = await import('../src/components/onboarding/ApiLoginPhone');
const { ApiEntry } = await import('../src/components/ApiEntry');

const queryClients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => {
  cleanup();
  for (const client of queryClients.splice(0)) {
    // MutationCache.clear() leaves each mutation's gc timer running (see apiSignup.test.ts).
    for (const mutation of client.getMutationCache().getAll()) mutation.destroy();
    client.clear();
  }
});
after(() => {
  dom.window.close();
  assetHooks.deregister();
});

const EMAIL = 'student@hanyang.ac.kr';
const PASSWORD = 'abcd123!';
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const ok = (data: unknown) => json(200, { code: 'SUCCESS', message: '요청이 성공했습니다.', data });
const fail = (status: number, code: string, message: string) => json(status, { code, message, data: null });
const REPLACED_MESSAGE = '다른 기기에서 로그인해 로그아웃되었습니다. 다시 로그인해주세요.';
const PUBLIC_TOP = 'GET /api/v1/rankings/universities?page=0&size=10';
const publicPage = (items: unknown[]) => ok({ items, page: 0, size: 10, totalCount: items.length, hasNext: items.length > 10, myUniversity: null });
const PROFILE = {
  id: 3, nickname: '새내기', email: EMAIL, role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: 8, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: null }, totalContribScore: 0, totalAttackScore: 0,
};

type Handler = (body: Record<string, unknown>) => Response | Promise<Response>;

/** Renders the account screen, the root watcher and the nav label in StrictMode, the way the app mounts them. */
function mount({ restore = true, overrides = {}, entry = false }: { restore?: boolean; overrides?: Record<string, Handler>; entry?: boolean } = {}) {
  let token = 0;
  const routes: Record<string, Handler> = {
    'POST /api/v1/auth/reissue': () => (restore
      ? ok({ accessToken: `restored-${++token}`, expiresInSeconds: 3600 })
      : fail(401, 'AUTH_INVALID_REFRESH_TOKEN', '로그인이 만료되었습니다. 다시 로그인해주세요.')),
    'POST /api/v1/auth/login': (body) => (body.password === PASSWORD
      ? ok({ accessToken: 'login-token', expiresInSeconds: 3600, user: { id: 3, nickname: '새내기', universityId: 8, role: 'USER' } })
      : fail(401, 'AUTH_INVALID_CREDENTIALS', '이메일 또는 비밀번호가 올바르지 않습니다.')),
    'POST /api/v1/auth/logout': () => new Response(null, { status: 204 }),
    [PUBLIC_TOP]: () => publicPage([]),
    // The account screen shows the nickname from the profile (SCRUM-153); apiMyInfo.test.ts covers the profile itself.
    'GET /api/v1/users/me': () => ok(PROFILE),
    ...overrides,
  };
  const calls: Array<{ route: string; authorization: string | null }> = [];
  const fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost');
    const route = `${init?.method ?? 'GET'} ${url.pathname}${url.search}`;
    calls.push({ route, authorization: new Headers(init?.headers).get('Authorization') });
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) as Record<string, unknown> : {};
    const handler = routes[route];
    return handler ? handler(body) : fail(404, 'COMMON_NOT_FOUND', '없음');
  }) as typeof globalThis.fetch;
  const client = createApiClient({ fetch, faults: createApiFaults({ delayMs: 0, failDelayMs: 0 }) });
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  const events = { ended: 0, signup: 0, campus: 0 };
  render(React.createElement(React.StrictMode, null, React.createElement(ApiProvider, {
    client,
    queryClient,
    children: entry ? React.createElement(ApiEntry, { children: React.createElement('h1', null, '내 캠퍼스') }) : [
      React.createElement(ApiSessionWatcher, { key: 'watcher', onEnded: () => { events.ended += 1; } }),
      React.createElement('nav', { key: 'nav' }, React.createElement(ApiAccountNavLabel)),
      React.createElement(ApiLoginPhone, {
        key: 'login',
        onGoToSignup: () => { events.signup += 1; },
        onGoToCampus: () => { events.campus += 1; },
      }),
    ],
  })));
  return { client, calls, routes: () => calls.map((call) => call.route), events };
}

const navLabel = () => screen.getByRole('navigation').textContent;

async function logIn(password = PASSWORD) {
  await screen.findByRole('heading', { name: '로그인' });
  fireEvent.change(screen.getByLabelText('학교 이메일 주소'), { target: { value: ` ${EMAIL} ` } });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: password } });
  fireEvent.click(screen.getByRole('button', { name: '로그인하기' }));
}

test('앱을 열면 refresh 쿠키로 세션을 복구한다. 컴포넌트가 여럿이고 StrictMode여도 reissue는 한 번이다', async () => {
  const { routes, events } = mount();
  assert.ok(screen.queryByText('로그인 상태를 확인하는 중…') !== null, '확인하는 동안 로그인 폼을 보이지 않는다');
  await screen.findByTestId('signed-in');
  await screen.findByRole('heading', { level: 2, name: '새내기님, 로그인되어 있어요' });
  assert.deepEqual(routes(), ['POST /api/v1/auth/reissue', 'GET /api/v1/users/me']);
  assert.equal(navLabel(), '🔑 내 계정 (로그인됨)');
  assert.equal(events.ended, 0);

  fireEvent.click(screen.getByRole('button', { name: '캠퍼스로 이동' }));
  assert.equal(events.campus, 1);
});

test('복구할 세션이 없으면 안내 없이 로그인 폼을 보이고, 틀린 비밀번호는 서버 문구로 알린다', async () => {
  const { calls, routes, events } = mount({ restore: false });
  await screen.findByRole('heading', { name: '로그인' });
  assertAbsent(screen.queryByRole('status'), '첫 방문에는 로그아웃 안내가 없다');
  assert.equal(navLabel(), '🔑 로그인');
  fireEvent.click(screen.getByRole('button', { name: /학교 이메일로 가입하기/ }));
  assert.equal(events.signup, 1);

  fireEvent.click(screen.getByRole('button', { name: '로그인하기' }));
  assert.equal((await screen.findByRole('alert')).textContent, '이메일과 비밀번호를 입력해 주세요.');
  assert.equal(routes().length, 1, '빈 입력은 보내지 않는다');

  await logIn('wrong-pass1!');
  assert.equal((await screen.findByRole('alert')).textContent, '이메일 또는 비밀번호가 올바르지 않습니다.');

  await logIn();
  assert.equal((await screen.findByTestId('signed-in')).querySelector('h2')?.textContent, '새내기님, 로그인되어 있어요');
  assert.deepEqual(calls.slice(1).map((call) => [call.route, call.authorization]), [
    ['POST /api/v1/auth/login', null],
    ['POST /api/v1/auth/login', null],
    ['GET /api/v1/users/me', 'Bearer login-token'],
  ]);
  assert.equal(navLabel(), '🔑 내 계정 (로그인됨)');
  assert.equal(events.ended, 0);
  assertAbsent(screen.queryByRole('button', { name: /학교 이메일로 가입하기/ }), '로그인된 뒤에는 가입 링크가 없다');
});

test('로그아웃: 토큰을 붙여 보내고 로그인 폼으로 돌아간다. 실패하면 로그인 상태를 남기고 알린다', async () => {
  let logoutCalls = 0;
  const { calls, events } = mount({
    overrides: {
      'POST /api/v1/auth/logout': () => (++logoutCalls === 1
        ? fail(500, 'COMMON_INTERNAL_ERROR', '서버 오류가 발생했습니다.')
        : new Response(null, { status: 204 })),
    },
  });
  await screen.findByTestId('signed-in');

  fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));
  assert.match((await screen.findByRole('alert')).textContent ?? '', /로그아웃하지 못했습니다/);
  assert.ok(screen.queryByTestId('signed-in') !== null);

  fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));
  await screen.findByRole('heading', { name: '로그인' });
  assertAbsent(screen.queryByRole('status'), '스스로 로그아웃하면 안내 문구가 없다');
  assert.deepEqual(calls.filter((call) => call.route.endsWith('/logout')).map((call) => call.authorization),
    ['Bearer restored-1', 'Bearer restored-1']);
  assert.equal(events.ended, 0, '스스로 로그아웃하면 강제 종료로 보지 않는다');
});

test('다른 기기 로그인으로 세션이 끝나면 앱에 알리고 로그인 화면에 이유를 보인다', async () => {
  const { client, events } = mount({
    // Any signed-in request may be the one that finds the session replaced; this path stands for it.
    overrides: { 'GET /api/v1/replaced-probe': () => fail(401, 'AUTH_SESSION_REPLACED', REPLACED_MESSAGE) },
  });
  await screen.findByTestId('signed-in');

  await act(async () => {
    await client.get('/replaced-probe').catch(() => undefined);
  });
  assert.equal((await screen.findByRole('status')).textContent, REPLACED_MESSAGE);
  assert.equal(events.ended, 1);

  // Typing re-renders everything; the app is told once, so it does not pull the user back to this screen.
  fireEvent.change(screen.getByLabelText('학교 이메일 주소'), { target: { value: EMAIL } });
  assert.equal(events.ended, 1);

  await logIn();
  await screen.findByTestId('signed-in');
  assert.equal(events.ended, 1);
});

test('세션을 확인하지 못하면(연결 실패) 이유를 보이고 로그인 폼은 그대로 쓸 수 있다', async () => {
  const { routes } = mount({ overrides: { 'POST /api/v1/auth/reissue': () => { throw new TypeError('Failed to fetch'); } } });
  assert.equal((await screen.findByText(/로그인 상태를 확인하지 못했습니다/)).textContent,
    '로그인 상태를 확인하지 못했습니다. 서버에 연결하지 못했습니다. 네트워크를 확인하고 다시 시도해 주세요.');
  assert.deepEqual(routes(), ['POST /api/v1/auth/reissue'], '응답이 사라졌을 수 있으니 다시 보내지 않는다 (refresh 토큰은 한 번만 쓴다)');
  await logIn();
  await screen.findByTestId('signed-in');
});

test('로그아웃 요청이 세션 교체로 실패하면 로그인 화면에 교체 이유만 보인다', async () => {
  const { events } = mount({ overrides: { 'POST /api/v1/auth/logout': () => fail(401, 'AUTH_SESSION_REPLACED', REPLACED_MESSAGE) } });
  await screen.findByTestId('signed-in');
  fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));
  assert.equal((await screen.findByRole('status')).textContent, REPLACED_MESSAGE);
  assertAbsent(screen.queryByRole('alert'), '로그아웃 실패 문구를 함께 띄우지 않는다');
  assert.equal(events.ended, 1);
});

test('SCRUM-210: 복원 도중에는 게스트 진입점도 캠퍼스도 표시하지 않는다', async () => {
  const { routes } = mount({ entry: true });
  assert.equal(screen.getByRole('status').textContent, '로그인 상태를 확인하는 중…');
  assertAbsent(screen.queryByRole('button', { name: '로그인' }));
  assertAbsent(screen.queryByRole('heading', { name: '내 캠퍼스' }));
  await screen.findByRole('heading', { name: '내 캠퍼스' });
  assertAbsent(screen.queryByRole('heading', { name: '대대대' }));
  assert.deepEqual(routes(), ['POST /api/v1/auth/reissue']);
});

test('SCRUM-210: 첫 화면에서 로그인 후 내 캠퍼스로, 세션 종료 후 안내와 함께 첫 화면으로 돌아간다', async () => {
  const { client, routes } = mount({ entry: true, restore: false });
  await screen.findByRole('heading', { level: 1, name: '대대대' });
  assert.ok(screen.getByText('대학교 대 대학교 캠퍼스 대항전'));
  assert.ok(screen.getByText('학교 이메일로 인증하고 클릭으로 우리 학교 순위를 끌어올리는 실시간 캠퍼스 대항전. 전국 대학과 겨루며 캠퍼스를 키워 보세요.'));
  assertAbsent(screen.queryByRole('heading', { name: '내 캠퍼스' }));
  assert.ok(screen.getByRole('button', { name: '회원가입' }));
  assert.ok(screen.getByRole('button', { name: '비회원 문의' }));
  fireEvent.click(screen.getByRole('button', { name: '로그인' }));
  assertSameNode(document.activeElement, screen.getByRole('main', { name: '비로그인 서비스' }));
  fireEvent.click(screen.getByRole('button', { name: '첫 화면으로' }));
  assertSameNode(document.activeElement, screen.getByRole('heading', { name: '대대대' }));
  fireEvent.click(screen.getByRole('button', { name: '로그인' }));
  await logIn();
  await screen.findByRole('heading', { name: '내 캠퍼스' });
  assert.deepEqual(routes(), ['POST /api/v1/auth/reissue', PUBLIC_TOP, 'POST /api/v1/auth/login']);

  await act(async () => {
    client.faults.setClickRefusal(true);
    client.session.close('password-changed');
  });
  assert.equal(client.faults.getClickRefusal(), false);
  assertAbsent(screen.queryByRole('heading', { name: '내 캠퍼스' }));
  assert.ok(screen.getByText(/새 비밀번호로 다시 로그인/) !== null);
});

test('SCRUM-211: 무인증 첫 페이지만 요청하고 공동 순위·서버 순서대로 최대 10개를 표시한다', async () => {
  const rows = Array.from({ length: 12 }, (_, index) => ({ universityId: 200 - index, rank: index < 2 ? 1 : index + 1,
    name: index === 0 ? '하늘대학교' : index === 1 ? '가람대학교' : `검증대 ${index + 1}`, logoUrl: null, totalScore: 12345, rankChange: null }));
  const { calls } = mount({ entry: true, restore: false, overrides: { [PUBLIC_TOP]: () => publicPage(rows) } });
  const table = await screen.findByRole('table', { name: '전국 대학 TOP 10' });
  assert.equal(within(table).getAllByRole('row').length, 11);
  assert.deepEqual(within(table).getAllByRole('rowheader').map((node) => node.textContent), rows.slice(0, 10).map(({ name }) => name));
  assert.deepEqual(within(table).getAllByRole('cell').slice(0, 4).map((node) => node.textContent), ['1위', '12,345점', '1위', '12,345점']);
  assertAbsent(within(table).queryByRole('button'));
  assertAbsent(within(table).queryByRole('link'));
  fireEvent.click(screen.getByRole('button', { name: '로그인' }));
  assertAbsent(screen.queryByRole('table'));
  fireEvent.click(screen.getByRole('button', { name: '첫 화면으로' }));
  await screen.findByRole('table', { name: '전국 대학 TOP 10' });
  assert.deepEqual(calls, [{ route: 'POST /api/v1/auth/reissue', authorization: null }, { route: PUBLIC_TOP, authorization: null }]);
});

test('SCRUM-211: 순위 조회 중과 실패에도 가입할 수 있고, 명시적 재시도 후 빈 상태를 표시한다', async () => {
  let answer: (value: Response) => void = () => { throw new Error('순위 요청이 시작되지 않았습니다'); };
  const pending = new Promise<Response>((resolve) => { answer = resolve; });
  let attempts = 0;
  const { routes } = mount({ entry: true, restore: false, overrides: { [PUBLIC_TOP]: () => ++attempts === 1 ? pending : publicPage([]) } });
  await screen.findByText('대학 순위를 불러오는 중이에요.');
  assertAbsent(screen.queryByRole('table'));
  fireEvent.click(screen.getByRole('button', { name: '회원가입' }));
  assert.ok(screen.getByRole('region', { name: '회원가입' }) !== null);
  fireEvent.click(screen.getByRole('button', { name: '첫 화면으로' }));
  await act(async () => { answer(fail(503, 'COMMON_SERVICE_UNAVAILABLE', '일시적으로 순위를 조회할 수 없습니다.')); });
  await screen.findByText('대학 순위를 불러오지 못했어요.');
  assertAbsent(screen.queryByRole('table'));
  assert.ok(screen.getByRole('button', { name: '로그인' }) !== null);
  assert.ok(screen.getByRole('button', { name: '회원가입' }) !== null);
  assert.equal(attempts, 1);
  fireEvent.click(screen.getByRole('button', { name: '순위 다시 불러오기' }));
  await screen.findByText('아직 공개된 대학 순위가 없어요.');
  assertAbsent(screen.queryByRole('table'));
  assert.deepEqual(routes(), ['POST /api/v1/auth/reissue', PUBLIC_TOP, PUBLIC_TOP]);
});
