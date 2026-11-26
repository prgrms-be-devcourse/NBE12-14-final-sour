import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

// Access token in memory, one shared reissue on COMMON_EXPIRED_TOKEN, and session-ending codes (SCRUM-148).
// Plan: .agents/01-PLAN/261118-1021-frontend_prototype_migration_plan.md §5 단계 0-4.
const { createApiClient, REISSUE_PATH } = await import('../src/api/client');
const { ApiError } = await import('../src/api/errors');
const { createAuthSession, SESSION_ENDING_CODES, sessionEndReason } = await import('../src/api/session');

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const ok = (data: unknown) => json(200, { code: 'SUCCESS', message: '요청이 성공했습니다.', data });
const fail = (status: number, code: string, message = `${code} 문구`) => json(status, { code, message, data: null });
const expired = () => fail(401, 'COMMON_EXPIRED_TOKEN', '토큰이 만료되었습니다.');

type Call = { path: string; method: string; authorization: string | null; credentials?: RequestCredentials };

/** A fake backend: `route` answers each call; `gate` lets a test hold reissue answers until it releases them. */
function backend(route: (call: Call) => Response | Promise<Response>) {
  const calls: Call[] = [];
  const fetch = (async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const headers = (init.headers ?? {}) as Record<string, string>;
    const call: Call = {
      path: String(input).replace('/api/v1', ''),
      method: init.method ?? 'GET',
      authorization: headers.Authorization ?? null,
      credentials: init.credentials,
    };
    calls.push(call);
    return route(call);
  }) as typeof globalThis.fetch;
  return { fetch, calls, count: (path: string) => calls.filter((c) => c.path === path).length };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => { resolve = r; });
  return { promise, resolve };
}

/** Lets pending fetch promises settle. */
const flush = () => new Promise((r) => setTimeout(r, 0));

function signedInClient(fetch: typeof globalThis.fetch, token = 'old') {
  const session = createAuthSession();
  session.signIn(token);
  return { client: createApiClient({ fetch, session }), session };
}

/** Old token expired; reissue returns `new`; requests with `new` succeed with their path. */
function expiringBackend(reissueAnswer: () => Response | Promise<Response> = () => ok({ accessToken: 'new', expiresInSeconds: 3600 })) {
  return backend((call) => {
    if (call.path === REISSUE_PATH) return reissueAnswer();
    return call.authorization === 'Bearer new' ? ok(call.path) : expired();
  });
}

test('로그인 상태면 Authorization: Bearer 토큰을 보내고, anonymous 요청과 로그아웃 상태는 보내지 않는다', async () => {
  const { fetch, calls } = backend(() => ok(null));
  const { client, session } = signedInClient(fetch, 'token-1');
  await client.get('/users/me');
  await client.request('/auth/login', { method: 'POST', body: {}, anonymous: true });
  session.signOut();
  await client.get('/rankings/universities');
  assert.deepEqual(calls.map((c) => c.authorization), ['Bearer token-1', null, null]);
});

test('별도 origin에서 GET과 재발급에 credentials를 포함한다', async () => {
  const calls: { url: string; credentials?: RequestCredentials }[] = [];
  const fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), credentials: init?.credentials });
    return ok(String(input).endsWith(REISSUE_PATH) ? { accessToken: 'new', expiresInSeconds: 3600 } : null);
  }) as typeof globalThis.fetch;
  const client = createApiClient({ baseUrl: 'https://api.example.com/api/v1', fetch });
  await client.get('/users/me');
  await client.reissue();
  assert.deepEqual(calls, [
    { url: 'https://api.example.com/api/v1/users/me', credentials: 'include' },
    { url: 'https://api.example.com/api/v1/auth/reissue', credentials: 'include' },
  ]);
});

test('토큰은 메모리에만 있고 브라우저 저장소를 쓰지 않는다', () => {
  for (const file of ['session.ts', 'client.ts', 'ApiProvider.tsx']) {
    const code = readFileSync(new URL(`../src/api/${file}`, import.meta.url), 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/^\s*\/\/.*$/gm, '');
    const found = code.match(/localStorage|sessionStorage|indexedDB|document\.cookie/);
    assert.ok(!found, `${file}에서 ${found?.[0]} 사용: access 토큰은 메모리에만 둡니다 (SCRUM-148).`);
  }
});

test('COMMON_EXPIRED_TOKEN이면 재발급 후 새 토큰으로 한 번 다시 보낸다', async () => {
  const { fetch, calls } = expiringBackend();
  const { client, session } = signedInClient(fetch);
  assert.equal(await client.get('/users/me'), '/users/me');
  assert.deepEqual(calls.map((c) => [c.path, c.method, c.authorization]), [
    ['/users/me', 'GET', 'Bearer old'],
    [REISSUE_PATH, 'POST', null],
    ['/users/me', 'GET', 'Bearer new'],
  ]);
  assert.equal(calls[1].credentials, 'include', '재발급은 refresh 쿠키를 보낸다');
  assert.deepEqual(session.getState(), { status: 'signed-in', accessToken: 'new' });
});

test('동시에 여러 요청이 401을 받아도 재발급은 한 번이고, 모두 새 토큰으로 다시 보낸다', async () => {
  const gate = deferred<Response>();
  const { fetch, count, calls } = expiringBackend(() => gate.promise);
  const { client } = signedInClient(fetch);

  const results = Promise.all(['/a', '/b', '/c', '/d'].map((path) => client.get(path)));
  await flush();
  assert.equal(count(REISSUE_PATH), 1, '재발급 응답을 기다리는 동안 추가 재발급 없음');
  gate.resolve(ok({ accessToken: 'new', expiresInSeconds: 3600 }));
  assert.deepEqual(await results, ['/a', '/b', '/c', '/d']);
  assert.equal(count(REISSUE_PATH), 1);
  assert.equal(calls.filter((c) => c.authorization === 'Bearer new').length, 4);
});

test('재발급이 끝난 뒤 늦게 401을 받은 요청은 다시 재발급하지 않고 이미 바뀐 토큰을 쓴다', async () => {
  const late = deferred<Response>();
  const { fetch, count } = backend((call) => {
    if (call.path === REISSUE_PATH) return ok({ accessToken: 'new', expiresInSeconds: 3600 });
    if (call.authorization === 'Bearer new') return ok(call.path);
    return call.path === '/slow' ? late.promise : expired();
  });
  const { client } = signedInClient(fetch);
  const slow = client.get('/slow');
  assert.equal(await client.get('/fast'), '/fast');
  late.resolve(expired());
  assert.equal(await slow, '/slow');
  assert.equal(count(REISSUE_PATH), 1);
});

test('다시 보낸 요청도 COMMON_EXPIRED_TOKEN이면 반복하지 않고 오류를 돌려준다', async () => {
  const { fetch, count } = backend((call) => (call.path === REISSUE_PATH ? ok({ accessToken: 'new', expiresInSeconds: 1 }) : expired()));
  const { client, session } = signedInClient(fetch);
  const error = await client.get('/x').then(() => null, (e: unknown) => e);
  assert.ok(error instanceof ApiError);
  assert.equal(error.code, 'COMMON_EXPIRED_TOKEN');
  assert.equal(count(REISSUE_PATH), 1);
  assert.equal(count('/x'), 2);
  assert.equal(session.getState().status, 'signed-in', '만료 자체는 세션을 끝내지 않는다');
});

test('재발급이 거절되면(refresh 만료·사용됨) 세션을 끝내고 대기 중인 요청 모두 그 오류로 끝난다', async () => {
  const { fetch, count } = expiringBackend(() => fail(401, 'AUTH_INVALID_REFRESH_TOKEN', '로그인이 만료되었습니다. 다시 로그인해주세요.'));
  const { client, session } = signedInClient(fetch);
  const results = await Promise.allSettled([client.get('/a'), client.get('/b')]);
  assert.deepEqual(results.map((r) => r.status), ['rejected', 'rejected']);
  for (const result of results) assert.equal((result as PromiseRejectedResult).reason.code, 'AUTH_INVALID_REFRESH_TOKEN');
  assert.equal(count(REISSUE_PATH), 1);
  assert.deepEqual(session.getState(), {
    status: 'signed-out',
    ended: { reason: 'expired', code: 'AUTH_INVALID_REFRESH_TOKEN', message: '로그인이 만료되었습니다. 다시 로그인해주세요.' },
  });
});

test('재발급 중 연결이 끊기면 세션은 유지하고 연결 오류를 돌려준다', async () => {
  const { fetch } = expiringBackend(() => { throw new TypeError('Failed to fetch'); });
  const { client, session } = signedInClient(fetch);
  const error = await client.get('/x').then(() => null, (e: unknown) => e);
  assert.ok(error instanceof ApiError);
  assert.equal(error.kind, 'network');
  assert.deepEqual(session.getState(), { status: 'signed-in', accessToken: 'old' });
});

for (const [code, status, reason] of [
  ['AUTH_SESSION_REPLACED', 401, 'replaced'],
  ['USER_SUSPENDED', 403, 'suspended'],
] as const) {
  test(`${code}를 받으면 재발급하지 않고 세션을 끝내 로그인 화면 사유(${reason})를 남긴다`, async () => {
    const { fetch, count } = backend((call) => (call.path === REISSUE_PATH ? ok({ accessToken: 'new', expiresInSeconds: 1 }) : fail(status, code, `${code} 서버 문구`)));
    const { client, session } = signedInClient(fetch);
    const states: string[] = [];
    session.subscribe(() => states.push(session.getState().status));

    const error = await client.request('/clicks', { method: 'POST', body: {} }).then(() => null, (e: unknown) => e);
    assert.ok(error instanceof ApiError);
    assert.equal(error.code, code);
    assert.equal(count(REISSUE_PATH), 0);
    assert.deepEqual(session.getState(), { status: 'signed-out', ended: { reason, code, message: `${code} 서버 문구` } });
    assert.deepEqual(states, ['signed-out'], '구독자에게 한 번 알린다');
    assert.equal(session.getAccessToken(), null, '토큰을 지운다');
  });
}

test('재발급 응답이 AUTH_SESSION_REPLACED여도 세션을 끝낸다', async () => {
  const { fetch } = expiringBackend(() => fail(401, 'AUTH_SESSION_REPLACED'));
  const { client, session } = signedInClient(fetch);
  await client.get('/x').catch(() => {});
  const state = session.getState();
  assert.equal(state.status === 'signed-out' && state.ended?.reason, 'replaced');
});

test('anonymous 요청(로그인 폼)의 USER_SUSPENDED는 세션을 건드리지 않고 폼의 오류로 남는다', async () => {
  const { fetch } = backend(() => fail(403, 'USER_SUSPENDED'));
  const { client, session } = signedInClient(fetch, 'keep');
  await client.request('/auth/login', { method: 'POST', body: {}, anonymous: true }).catch(() => {});
  assert.deepEqual(session.getState(), { status: 'signed-in', accessToken: 'keep' });
});

test('로그인하지 않은 상태의 실패한 세션 복구는 "로그아웃됨" 안내를 남기지 않는다', async () => {
  const { fetch } = backend(() => fail(401, 'AUTH_INVALID_REFRESH_TOKEN'));
  const client = createApiClient({ fetch });
  const error = await client.reissue().then(() => null, (e: unknown) => e);
  assert.ok(error instanceof ApiError);
  assert.deepEqual(client.session.getState(), { status: 'signed-out', ended: null });
});

test('reissue()는 세션을 복구하고, 동시 호출은 하나의 요청을 공유한다', async () => {
  const gate = deferred<Response>();
  const { fetch, count } = backend(() => gate.promise);
  const client = createApiClient({ fetch });
  const both = Promise.all([client.reissue(), client.reissue()]);
  gate.resolve(ok({ accessToken: 'restored', expiresInSeconds: 3600 }));
  assert.deepEqual(await both, ['restored', 'restored']);
  assert.equal(count(REISSUE_PATH), 1);
  assert.equal(client.session.getAccessToken(), 'restored');

  const { fetch: fetch2, count: count2 } = backend(() => ok({ accessToken: 'again', expiresInSeconds: 3600 }));
  const client2 = createApiClient({ fetch: fetch2 });
  await client2.reissue();
  await client2.reissue();
  assert.equal(count2(REISSUE_PATH), 2, '끝난 재발급은 다시 쓰지 않는다');
});

test('재발급을 기다리던 요청을 취소해도 다른 요청의 재발급은 계속된다', async () => {
  const gate = deferred<Response>();
  const { fetch } = expiringBackend(() => gate.promise);
  const { client, session } = signedInClient(fetch);
  const controller = new AbortController();
  const cancelled = client.get('/a', { signal: controller.signal }).then(() => 'resolved', (e: unknown) => e);
  const other = client.get('/b');
  await flush();
  const reason = new Error('화면을 떠남');
  controller.abort(reason);
  assert.equal(await cancelled, reason);
  gate.resolve(ok({ accessToken: 'new', expiresInSeconds: 3600 }));
  assert.equal(await other, '/b');
  assert.equal(session.getAccessToken(), 'new');
});

test('세션 종료 코드 표는 ErrorCode.java에 있는 코드만 쓴다', () => {
  const source = readFileSync(
    new URL('../../src/main/java/com/back/nbe1214finalsour/global/exception/ErrorCode.java', import.meta.url), 'utf8');
  for (const code of [...Object.keys(SESSION_ENDING_CODES), 'COMMON_EXPIRED_TOKEN']) {
    assert.match(source, new RegExp(`^\\s+${code}\\(HttpStatus\\.`, 'm'), `${code}가 ErrorCode.java에 없습니다. src/api/session.ts·client.ts를 고치세요.`);
  }
  assert.equal(sessionEndReason('COMMON_EXPIRED_TOKEN'), null, '만료는 재발급 대상이지 종료가 아니다');
  assert.equal(sessionEndReason('toString'), null);
});

test('세션: 빈 토큰은 거부, 로그아웃은 안내 없이, 이미 로그아웃이면 end는 무시', () => {
  const session = createAuthSession();
  assert.throws(() => session.signIn(''), /빈 access 토큰/);
  session.end(new ApiError({ kind: 'http', status: 401, code: 'AUTH_SESSION_REPLACED' }));
  assert.deepEqual(session.getState(), { status: 'signed-out', ended: null });
  session.signIn('t');
  assert.throws(() => session.end(new ApiError({ kind: 'http', status: 400, code: 'COMMON_INVALID_INPUT' })), /세션을 끝내는 코드가 아닙니다/);
  session.signOut();
  assert.deepEqual(session.getState(), { status: 'signed-out', ended: null });
});
