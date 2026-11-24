import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { assertAbsent } from './helpers/domAssert';

// API-mode sign-up screen (SCRUM-151) against a fake backend: success, wrong code, duplicate nickname, password
// rules and an expired sign-up token. jsdom checks the flow; the real backend run is the browser check.
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

const { render, screen, fireEvent, cleanup, within } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createApiFaults } = await import('../src/api/faults');
const { createQueryClient } = await import('../src/api/queryClient');
const { ApiSignupPhone } = await import('../src/components/onboarding/ApiSignupPhone');

const queryClients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => {
  cleanup();
  for (const client of queryClients.splice(0)) {
    // MutationCache.clear() drops mutations without cancelling their 5-minute gc timers, which keep node running
    // after the last test; destroy() cancels them.
    for (const mutation of client.getMutationCache().getAll()) mutation.destroy();
    client.clear();
  }
});
after(() => {
  dom.window.close();
  assetHooks.deregister();
});

const HANYANG = { id: 8, name: '한양대학교' };
const DEPARTMENTS = [{ id: 301, name: '컴퓨터소프트웨어학부' }, { id: 302, name: '경영학부' }];
const TAKEN_NICKNAME = '이미있는닉';
const RIGHT_CODE = '123456';
const PASSWORD = 'abcd123!';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const ok = (data: unknown, status = 200) => json(status, { code: 'SUCCESS', message: '요청이 성공했습니다.', data });
const fail = (status: number, code: string, message: string) => json(status, { code, message, data: null });

type Handler = (body: Record<string, unknown>, url: URL) => Response;

/** A backend that answers like the real one for the calls the screen makes; tests override single routes. */
function mount(overrides: Record<string, Handler> = {}) {
  const routes: Record<string, Handler> = {
    'POST /api/v1/auth/email/code': () => ok({ university: HANYANG, expiresInSeconds: 300 }),
    'POST /api/v1/auth/email/verify': (body) => (body.code === RIGHT_CODE
      ? ok({ signupToken: 'signup-token', university: HANYANG, expiresInSeconds: 1800 })
      : fail(400, 'AUTH_INVALID_CODE', '인증 코드가 일치하지 않습니다.')),
    'GET /api/v1/universities/8/departments': () => ok(DEPARTMENTS),
    'GET /api/v1/users/nickname/availability': (_, url) => ok({ available: url.searchParams.get('nickname') !== TAKEN_NICKNAME }),
    'POST /api/v1/auth/signup': (body) => (body.nickname === TAKEN_NICKNAME
      ? fail(409, 'USER_NICKNAME_DUPLICATED', '이미 사용 중인 닉네임입니다.')
      : ok({ userId: 1, nickname: body.nickname, university: HANYANG, department: DEPARTMENTS.find((d) => d.id === body.departmentId) }, 201)),
    'POST /api/v1/auth/login': (body) => (body.password === PASSWORD
      ? ok({ accessToken: 'login-token', expiresInSeconds: 3600, user: { id: 1, nickname: '새내기', universityId: 8, role: 'USER' } })
      : fail(401, 'AUTH_INVALID_CREDENTIALS', '이메일 또는 비밀번호가 올바르지 않습니다.')),
    ...overrides,
  };
  const calls: Array<{ route: string; body: Record<string, unknown> }> = [];
  const fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost');
    const route = `${init?.method ?? 'GET'} ${url.pathname}`;
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) as Record<string, unknown> : {};
    calls.push({ route, body });
    const handler = routes[route];
    return handler ? handler(body, url) : fail(404, 'COMMON_NOT_FOUND', '없음');
  }) as typeof globalThis.fetch;
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  const goneToCampus: string[] = [];
  const client = createApiClient({ fetch, faults: createApiFaults({ delayMs: 0, failDelayMs: 0 }) });
  render(React.createElement(ApiProvider, {
    client,
    queryClient,
    children: React.createElement(ApiSignupPhone, {
      onGoToCampus: () => goneToCampus.push('campus'),
      onGoToLogin: () => goneToCampus.push('login'),
    }),
  }));
  return { client, calls, routes: () => calls.map((call) => call.route), goneToCampus };
}

async function requestCode(email = 'student@hanyang.ac.kr') {
  fireEvent.change(screen.getByLabelText('학교 이메일 주소'), { target: { value: email } });
  fireEvent.click(screen.getByRole('button', { name: '인증 코드 받기' }));
}

async function enterCode(code: string) {
  await screen.findByTestId('code-step');
  [...code].forEach((digit, index) => fireEvent.change(screen.getByLabelText(`인증 코드 ${index + 1}번째 자리`), { target: { value: digit } }));
  fireEvent.click(screen.getByRole('button', { name: '코드 확인' }));
}

async function setPassword(password = PASSWORD, confirm = password) {
  await screen.findByTestId('password-step');
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: password } });
  fireEvent.change(screen.getByLabelText('비밀번호 확인'), { target: { value: confirm } });
  fireEvent.click(screen.getByRole('button', { name: '다음' }));
}

async function fillProfile(nickname: string) {
  const list = await screen.findByRole('group', { name: '소속 학과' });
  fireEvent.click(await within(list).findByRole('button', { name: '경영학부' }));
  fireEvent.change(screen.getByLabelText('플레이어 닉네임'), { target: { value: nickname } });
}

const alertText = async () => (await screen.findByRole('alert')).textContent ?? '';

test('가입 성공: 이메일 → 코드 → 비밀번호 → 닉네임·학과 → 가입 → 같은 계정으로 자동 로그인', async () => {
  const { client, calls, routes, goneToCampus } = mount();
  assertAbsent(screen.queryByLabelText('목업 샘플 이메일'), 'API 모드는 목업 샘플 도메인을 보이지 않는다');

  await requestCode(' student@hanyang.ac.kr ');
  await screen.findByText('인증 코드를 메일로 보냈어!');
  assertAbsent(screen.queryByRole('region', { name: '목업 메일함' }), '실제 메일이 가므로 목업 메일함은 없다');
  assert.equal(screen.getByTestId('attempts-left').textContent, '남은 시도 5회');
  assert.match(screen.getByTestId('code-remaining').textContent ?? '', /0[45]:\d\d/);

  await enterCode(RIGHT_CODE);
  await setPassword();
  await fillProfile('새내기');
  fireEvent.click(screen.getByRole('button', { name: '중복 확인' }));
  assert.equal((await screen.findByText('사용할 수 있는 닉네임입니다.')).getAttribute('data-nickname-ok'), 'true');
  fireEvent.click(screen.getByRole('button', { name: /입학하기/ }));

  await screen.findByText('한양대학교 입학을 환영합니다!');
  assert.ok(screen.queryByText('새내기 (경영학부)') !== null);
  assert.deepEqual(routes(), [
    'POST /api/v1/auth/email/code',
    'POST /api/v1/auth/email/verify',
    'GET /api/v1/universities/8/departments',
    'GET /api/v1/users/nickname/availability',
    'POST /api/v1/auth/signup',
    'POST /api/v1/auth/login',
  ]);
  assert.deepEqual(calls[0].body, { email: 'student@hanyang.ac.kr' }, '앞뒤 공백 없이 보낸다');
  assert.deepEqual(calls[4].body, { signupToken: 'signup-token', nickname: '새내기', password: PASSWORD, departmentId: 302 });
  assert.deepEqual(calls[5].body, { email: 'student@hanyang.ac.kr', password: PASSWORD });
  assert.equal(client.session.getAccessToken(), 'login-token');
  assertAbsent(screen.queryByRole('status'), '로그인까지 되면 안내 문구가 없다');

  fireEvent.click(screen.getByRole('button', { name: '캠퍼스로 이동' }));
  assert.deepEqual(goneToCampus, ['campus']);
});

test('코드 오류: 서버 문구와 남은 시도를 보이고, 맞는 코드로 다음 단계로 간다', async () => {
  const { routes } = mount();
  await requestCode();
  await enterCode('000000');
  assert.equal(await alertText(), '인증 코드가 일치하지 않습니다. 남은 시도 4회');
  assert.equal(screen.getByTestId('attempts-left').textContent, '남은 시도 4회');

  // A short code is caught before a request.
  fireEvent.change(screen.getByLabelText('인증 코드 1번째 자리'), { target: { value: '' } });
  fireEvent.click(screen.getByRole('button', { name: '코드 확인' }));
  assert.equal(await alertText(), '6자리 숫자를 모두 입력해 주세요.');
  assert.equal(routes().filter((route) => route.endsWith('/verify')).length, 1);

  await enterCode(RIGHT_CODE);
  await screen.findByTestId('password-step');
});

test('시도를 모두 쓰면 0회로 보이고, 이메일 단계의 서버 오류는 그 문구를 보인다', async () => {
  mount({
    'POST /api/v1/auth/email/verify': () => fail(429, 'AUTH_CODE_ATTEMPTS_EXCEEDED', '인증 코드를 5번 틀렸습니다. 코드를 다시 요청해주세요.'),
  });
  await requestCode();
  await enterCode('999999');
  assert.equal(await alertText(), '인증 코드를 5번 틀렸습니다. 코드를 다시 요청해주세요.');
  assert.equal(screen.getByTestId('attempts-left').textContent, '남은 시도 0회');

  fireEvent.click(screen.getByRole('button', { name: '이메일 수정' }));
  assert.equal((screen.getByLabelText('학교 이메일 주소') as HTMLInputElement).value, 'student@hanyang.ac.kr', '입력한 이메일을 남긴다');
});

test('비밀번호 규칙과 확인 불일치는 요청 없이 막는다', async () => {
  const { routes } = mount();
  await requestCode();
  await enterCode(RIGHT_CODE);
  await setPassword('abcdefgh');
  assert.match(await alertText(), /영문·숫자·특수문자/);
  await setPassword(PASSWORD, 'abcd123?');
  assert.equal(await alertText(), '비밀번호 확인이 일치하지 않습니다.');
  assert.equal(routes().length, 2);
});

test('닉네임 중복: 확인 단계와 가입 요청 모두에서 중복을 알리고, 바꾸면 가입된다', async () => {
  const { routes } = mount();
  await requestCode();
  await enterCode(RIGHT_CODE);
  await setPassword();
  await fillProfile(TAKEN_NICKNAME);

  fireEvent.click(screen.getByRole('button', { name: '중복 확인' }));
  assert.equal((await screen.findByText('이미 사용 중인 닉네임입니다.')).getAttribute('data-nickname-ok'), 'false');

  fireEvent.change(screen.getByLabelText('플레이어 닉네임'), { target: { value: '새내기' } });
  assertAbsent(screen.queryByText('이미 사용 중인 닉네임입니다.'), '닉네임을 바꾸면 확인 결과를 지운다');

  // Sign-up without a fresh check: the server's 409 is shown.
  fireEvent.change(screen.getByLabelText('플레이어 닉네임'), { target: { value: TAKEN_NICKNAME } });
  fireEvent.click(screen.getByRole('button', { name: /입학하기/ }));
  assert.equal(await alertText(), '이미 사용 중인 닉네임입니다.');
  assert.equal(routes().at(-1), 'POST /api/v1/auth/signup');

  fireEvent.change(screen.getByLabelText('플레이어 닉네임'), { target: { value: '새내기' } });
  fireEvent.click(screen.getByRole('button', { name: /입학하기/ }));
  await screen.findByText('한양대학교 입학을 환영합니다!');
});

test('가입 토큰이 만료되면 이메일 단계로 돌아가 다시 인증하게 한다', async () => {
  mount({
    'POST /api/v1/auth/signup': () => fail(400, 'AUTH_INVALID_SIGNUP_TOKEN', '가입 토큰이 만료되었거나 올바르지 않습니다. 이메일 인증을 다시 해주세요.'),
  });
  await requestCode();
  await enterCode(RIGHT_CODE);
  await setPassword();
  await fillProfile('새내기');
  fireEvent.click(screen.getByRole('button', { name: /입학하기/ }));

  assert.match(await alertText(), /이메일 인증을 다시 해주세요/);
  assert.ok(screen.queryByRole('button', { name: '인증 코드 받기' }) !== null);
});

test('학과 목록을 못 받으면 알리고, 다시 시도로 불러온다. 학과를 고르지 않으면 가입하지 않는다', async () => {
  let departmentCalls = 0;
  const { routes } = mount({
    'GET /api/v1/universities/8/departments': () => (++departmentCalls === 1
      ? fail(400, 'COMMON_INVALID_INPUT', '입력값이 올바르지 않습니다.')
      : ok(DEPARTMENTS)),
  });
  await requestCode();
  await enterCode(RIGHT_CODE);
  await setPassword();

  const list = await screen.findByRole('group', { name: '소속 학과' });
  assert.match((await within(list).findByRole('alert')).textContent ?? '', /학과 목록을 불러오지 못했습니다/);
  fireEvent.click(within(list).getByRole('button', { name: '다시 시도' }));
  await within(list).findByRole('button', { name: '경영학부' });

  fireEvent.change(screen.getByLabelText('플레이어 닉네임'), { target: { value: '새내기' } });
  fireEvent.click(screen.getByRole('button', { name: /입학하기/ }));
  assert.equal(await alertText(), '학과를 선택해 주세요.');
  assert.equal(routes().includes('POST /api/v1/auth/signup'), false);
});

test('가입 뒤 자동 로그인이 실패해도 가입 완료를 보이고 로그인 화면을 안내한다', async () => {
  const { client } = mount({ 'POST /api/v1/auth/login': () => fail(500, 'COMMON_INTERNAL_ERROR', '서버 오류가 발생했습니다.') });
  await requestCode();
  await enterCode(RIGHT_CODE);
  await setPassword();
  await fillProfile('새내기');
  fireEvent.click(screen.getByRole('button', { name: /입학하기/ }));

  await screen.findByText('한양대학교 입학을 환영합니다!');
  assert.match(screen.getByRole('status').textContent ?? '', /로그인 화면에서 다시 로그인해 주세요/);
  assert.equal(client.session.getState().status, 'signed-out');
});

test('이메일 단계에 이미 가입한 회원을 위한 로그인 링크가 있다', () => {
  const { goneToCampus } = mount();
  fireEvent.click(screen.getByRole('button', { name: '이미 가입했어요 · 로그인' }));
  assert.deepEqual(goneToCampus, ['login']);
});
