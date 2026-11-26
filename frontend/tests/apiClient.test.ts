import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

// Common API client, error messages, fault injection and query defaults (SCRUM-147).
// Plan: .agents/01-PLAN/261118-1021-frontend_prototype_migration_plan.md §5 단계 0-2·0-3.
const { buildUrl, createApiClient, API_BASE_PATH } = await import('../src/api/client');
const {
  ApiError, CLIENT_ERROR_CODES, ERROR_MESSAGE_OVERRIDES, errorMessage, fieldErrorMap, parseFieldErrors, userMessageFor,
} = await import('../src/api/errors');
const { createApiFaults } = await import('../src/api/faults');
const { createQueryClient, QUERY_DEFAULTS, shouldRetryQuery } = await import('../src/api/queryClient');
const { searchUniversities, universityKeys } = await import('../src/api/universities');

type Call = { url: string; init: RequestInit };

/** A fetch that records calls and answers from the given responses in order. */
function fakeFetch(...answers: Array<Response | Error | (() => Response)>) {
  const calls: Call[] = [];
  const fetch = (async (input: RequestInfo | URL, init: RequestInit = {}) => {
    calls.push({ url: String(input), init });
    const answer = answers[Math.min(calls.length, answers.length) - 1];
    if (answer instanceof Error) throw answer;
    return typeof answer === 'function' ? answer() : answer.clone();
  }) as typeof globalThis.fetch;
  return { fetch, calls };
}

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
});
const ok = (data: unknown) => json(200, { code: 'SUCCESS', message: '요청이 성공했습니다.', data });
const fail = (status: number, code: string, message: string, data: unknown = null) => json(status, { code, message, data });

/** Rejects unless `promise` rejects with an ApiError; returns it for further checks. */
async function apiErrorOf(promise: Promise<unknown>) {
  const error = await promise.then(() => null, (reason: unknown) => reason);
  assert.ok(error instanceof ApiError, `ApiError가 아님: ${String(error)}`);
  return error;
}

test('URL은 /api/v1 아래에 붙고, null·undefined 쿼리는 빠지며 한글은 인코딩된다', () => {
  assert.equal(API_BASE_PATH, '/api/v1');
  assert.equal(buildUrl('/api/v1', '/universities'), '/api/v1/universities');
  assert.equal(
    buildUrl('/api/v1', '/universities', { keyword: '한양', size: 20, page: undefined, sort: null, mine: false }),
    '/api/v1/universities?keyword=%ED%95%9C%EC%96%91&size=20&mine=false',
  );
  assert.throws(() => buildUrl('/api/v1', 'universities'), /\/로 시작/);
});

test('성공 응답은 data만 돌려주고, GET은 본문 없이 쿠키를 포함해 보낸다', async () => {
  const schools = [{ id: 7, name: '한양대학교', logoUrl: null }];
  const { fetch, calls } = fakeFetch(ok(schools));
  const client = createApiClient({ fetch });

  assert.deepEqual(await searchUniversities(client, '한양'), schools);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/api/v1/universities?keyword=%ED%95%9C%EC%96%91&size=20');
  assert.equal(calls[0].init.method, 'GET');
  assert.equal(calls[0].init.credentials, 'include');
  assert.equal(calls[0].init.body, undefined);
  assert.deepEqual(calls[0].init.headers, { Accept: 'application/json' });
});

test('JSON 본문은 Content-Type을 붙이고, FormData는 브라우저가 경계를 정하도록 비워 둔다', async () => {
  const { fetch, calls } = fakeFetch(ok(null));
  const client = createApiClient({ fetch });

  await client.request('/clicks', { method: 'POST', body: { submissionId: 'a', clickCount: 3 } });
  assert.equal(calls[0].init.body, '{"submissionId":"a","clickCount":3}');
  assert.equal((calls[0].init.headers as Record<string, string>)['Content-Type'], 'application/json');

  const form = new FormData();
  form.append('logoImage', new Blob(['x'], { type: 'image/png' }), 'logo.png');
  await client.request('/items/1/purchase', { method: 'POST', body: form });
  assert.equal(calls[1].init.body, form);
  assert.equal('Content-Type' in (calls[1].init.headers as Record<string, string>), false);
});

test('요청별 헤더는 예약 헤더를 대소문자와 관계없이 보호하면서 전달한다', async () => {
  const { fetch, calls } = fakeFetch(ok(null));
  const client = createApiClient({ fetch });
  client.session.signIn('member');
  const options = { method: 'POST' as const, body: { value: 1 }, headers: {
    'Idempotency-Key': 'key-1', accept: 'text/plain', 'content-type': 'text/plain', authorization: 'evil',
  } };
  await client.request('/x', options);
  const headers = new Headers(calls[0].init.headers);
  assert.equal(headers.get('Idempotency-Key'), 'key-1');
  assert.equal(headers.get('Accept'), 'application/json');
  assert.equal(headers.get('Content-Type'), 'application/json');
  assert.equal(headers.get('Authorization'), 'Bearer member');
});

test('토큰 재발급 뒤 원래 요청에 같은 멱등 헤더를 보낸다', async () => {
  const { fetch, calls } = fakeFetch(
    fail(401, 'COMMON_EXPIRED_TOKEN', '만료'),
    ok({ accessToken: 'renewed', expiresInSeconds: 3600 }),
    ok(null),
  );
  const client = createApiClient({ fetch });
  client.session.signIn('old');
  const options = { method: 'POST' as const, headers: { 'Idempotency-Key': 'same-key' } };
  await client.request('/items/1/purchase', options);
  assert.equal(calls.length, 3);
  assert.equal(new Headers(calls[0].init.headers).get('Idempotency-Key'), 'same-key');
  assert.equal(new Headers(calls[1].init.headers).get('Idempotency-Key'), null);
  assert.equal(new Headers(calls[2].init.headers).get('Idempotency-Key'), 'same-key');
  assert.equal(new Headers(calls[2].init.headers).get('Authorization'), 'Bearer renewed');
});

test('204는 본문 없이 undefined를 돌려준다', async () => {
  const { fetch } = fakeFetch(() => new Response(null, { status: 204 }));
  assert.equal(await createApiClient({ fetch }).request('/anything', { method: 'DELETE' }), undefined);
});

test('오류 응답은 코드·상태·서버 문구를 가진 ApiError가 되고, 화면 문구는 서버 문구다', async () => {
  const { fetch } = fakeFetch(fail(404, 'UNIV_NOT_FOUND', '학교를 찾을 수 없습니다.'));
  const error = await apiErrorOf(createApiClient({ fetch }).get('/universities/999'));
  assert.equal(error.kind, 'http');
  assert.equal(error.status, 404);
  assert.equal(error.code, 'UNIV_NOT_FOUND');
  assert.equal(error.serverMessage, '학교를 찾을 수 없습니다.');
  assert.equal(error.message, '학교를 찾을 수 없습니다.');
  assert.deepEqual(error.fieldErrors, []);
});

test('COMMON_INVALID_INPUT의 필드 오류를 전달하고, 필드별로 첫 사유를 고른다', async () => {
  const details = [
    { field: 'keyword', reason: '공백일 수 없습니다' },
    { field: 'size', reason: '100 이하여야 합니다' },
    { field: 'keyword', reason: '두 번째 사유' },
  ];
  const { fetch } = fakeFetch(fail(400, 'COMMON_INVALID_INPUT', '입력값이 올바르지 않습니다.', details));
  const error = await apiErrorOf(createApiClient({ fetch }).get('/universities', { query: { keyword: ' ' } }));
  assert.equal(error.message, '입력값이 올바르지 않습니다.');
  assert.deepEqual(error.fieldErrors, details);
  assert.deepEqual(fieldErrorMap(error), { keyword: '공백일 수 없습니다', size: '100 이하여야 합니다' });
  assert.deepEqual(fieldErrorMap(new Error('x')), {});
});

test('필드 오류 형식이 아닌 data는 빈 목록이 된다', () => {
  assert.deepEqual(parseFieldErrors(null), []);
  assert.deepEqual(parseFieldErrors({ field: 'a', reason: 'b' }), []);
  assert.deepEqual(parseFieldErrors([{ field: 'a' }, 'x', { field: 'b', reason: 'ok' }]), [{ field: 'b', reason: 'ok' }]);
});

test('연결 실패는 network ApiError, 응답 형식이 아니면 invalid-response ApiError가 된다', async () => {
  const network = await apiErrorOf(createApiClient({ fetch: fakeFetch(new TypeError('Failed to fetch')).fetch }).get('/x'));
  assert.equal(network.kind, 'network');
  assert.equal(network.status, null);
  assert.equal(network.code, CLIENT_ERROR_CODES.network);
  assert.equal(network.message, ERROR_MESSAGE_OVERRIDES[CLIENT_ERROR_CODES.network]);
  assert.ok(network.cause instanceof TypeError);

  // Vite 프록시는 백엔드가 꺼져 있으면 빈 502를 준다(2026-11-18 확인). 연결 실패로 알린다.
  for (const response of [
    new Response('', { status: 502 }),
    new Response('<html>Service Unavailable</html>', { status: 503 }),
    new Response('', { status: 504 }),
  ]) {
    const error = await apiErrorOf(createApiClient({ fetch: fakeFetch(response).fetch }).get('/x'));
    assert.equal(error.kind, 'network');
    assert.equal(error.status, response.status);
    assert.equal(error.code, CLIENT_ERROR_CODES.network);
  }
  const backendUnavailable = await apiErrorOf(createApiClient({
    fetch: fakeFetch(fail(503, 'COMMON_INTERNAL_ERROR', '서버 오류가 발생했습니다.')).fetch,
  }).get('/x'));
  assert.equal(backendUnavailable.kind, 'http', '백엔드가 직접 보낸 503은 서버 오류 그대로');

  for (const response of [
    new Response('<html>Error</html>', { status: 500 }),
    new Response('', { status: 500 }),
    json(200, { hello: 'world' }),
  ]) {
    const error = await apiErrorOf(createApiClient({ fetch: fakeFetch(response).fetch }).get('/x'));
    assert.equal(error.kind, 'invalid-response');
    assert.equal(error.status, response.status);
    assert.equal(error.code, CLIENT_ERROR_CODES.invalidResponse);
  }
});

test('취소된 요청은 ApiError로 바꾸지 않고 signal의 이유를 그대로 던진다', async () => {
  const controller = new AbortController();
  const reason = new DOMException('취소', 'AbortError');
  const fetch = (async () => {
    controller.abort(reason);
    throw reason;
  }) as typeof globalThis.fetch;
  const thrown = await createApiClient({ fetch }).get('/x', { signal: controller.signal }).then(() => null, (e: unknown) => e);
  assert.equal(thrown, reason);
});

test('화면 문구: 덮어쓰기 표 → 서버 문구 → 상태별 기본 문구', () => {
  assert.equal(userMessageFor('COMMON_NOT_FOUND', '요청한 경로를 찾을 수 없습니다.', 404), '요청한 정보를 찾을 수 없습니다.');
  assert.equal(userMessageFor('USER_SUSPENDED', '이용이 제한된 계정입니다.', 403), '이용이 제한된 계정입니다.');
  assert.equal(userMessageFor('UNKNOWN', '  ', 503), '서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.');
  assert.equal(userMessageFor('UNKNOWN', null, 400), '요청을 처리하지 못했습니다. 다시 시도해 주세요.');
  assert.equal(errorMessage(new Error('내부 메시지')), '요청을 처리하지 못했습니다. 다시 시도해 주세요.');
});

// Sensor: a key in the override table that the backend renamed or never had would silently stop applying.
test('덮어쓰기 표의 키는 클라이언트 코드이거나 ErrorCode.java에 있고, 클라이언트 코드는 백엔드와 겹치지 않는다', () => {
  const source = readFileSync(
    new URL('../../src/main/java/com/back/nbe1214finalsour/global/exception/ErrorCode.java', import.meta.url), 'utf8');
  const backendCodes = new Set([...source.matchAll(/^\s+([A-Z][A-Z0-9_]+)\(HttpStatus\./gm)].map((m) => m[1]));
  assert.ok(backendCodes.size > 40, `ErrorCode.java 파싱 결과가 너무 적음: ${backendCodes.size}`);
  const clientCodes = new Set<string>(Object.values(CLIENT_ERROR_CODES));
  for (const code of clientCodes) assert.equal(backendCodes.has(code), false, `${code}가 백엔드 코드와 겹침`);
  for (const code of Object.keys(ERROR_MESSAGE_OVERRIDES)) {
    assert.ok(clientCodes.has(code) || backendCodes.has(code),
      `${code}는 ErrorCode.java에 없습니다. 백엔드 코드 이름이 바뀌었다면 ERROR_MESSAGE_OVERRIDES(src/api/errors.ts)를 고치세요.`);
  }
});

function recordingSleep() {
  const waits: number[] = [];
  return { waits, sleep: async (ms: number) => { waits.push(ms); } };
}

test('지연 주입은 기다린 뒤 요청하고, 실패 주입은 서버에 보내지 않고 injected 오류를 낸다', async () => {
  const rules = { delayMs: 1500, failDelayMs: 600 };
  const { waits, sleep } = recordingSleep();
  const faults = createApiFaults(rules, sleep);
  const { fetch, calls } = fakeFetch(ok('data'));
  const client = createApiClient({ fetch, faults });

  faults.setMode('delay');
  assert.equal(await client.get('/x'), 'data');
  assert.deepEqual(waits, [1500]);
  assert.equal(faults.getMode(), 'delay', '지연은 계속 유지');

  faults.setMode('fail');
  for (let i = 0; i < 2; i++) {
    const error = await apiErrorOf(client.get('/x'));
    assert.equal(error.kind, 'injected');
    assert.equal(error.code, CLIENT_ERROR_CODES.injected);
    assert.match(error.message, /^\[테스트 도구\]/);
  }
  assert.deepEqual(waits, [1500, 600, 600]);
  assert.equal(calls.length, 1, '실패 주입은 fetch를 부르지 않는다');

  faults.setMode('normal');
  assert.equal(await client.get('/x'), 'data');
  assert.deepEqual(waits, [1500, 600, 600], '정상 모드는 기다리지 않는다');
});

test('1회 실패는 동시에 보낸 요청 중 첫 요청만 실패시키고 정상으로 돌아가며 구독자에게 알린다', async () => {
  const faults = createApiFaults({ delayMs: 0, failDelayMs: 0 }, recordingSleep().sleep);
  const client = createApiClient({ fetch: fakeFetch(ok(1)).fetch, faults });
  let notified = 0;
  const unsubscribe = faults.subscribe(() => { notified++; });

  faults.setMode('fail-once');
  const results = await Promise.allSettled([client.get('/a'), client.get('/b')]);
  assert.deepEqual(results.map((r) => r.status), ['rejected', 'fulfilled']);
  assert.equal(faults.getMode(), 'normal');
  assert.equal(notified, 2, 'fail-once 설정과 정상 복귀');

  unsubscribe();
  faults.setMode('fail');
  assert.equal(notified, 2, '구독 해제 후에는 알리지 않는다');
});

test('지연 중 취소하면 기다림을 멈추고 signal의 이유로 끝난다', async () => {
  const faults = createApiFaults({ delayMs: 60_000, failDelayMs: 60_000 });
  const { fetch, calls } = fakeFetch(ok(1));
  const client = createApiClient({ fetch, faults });
  faults.setMode('delay');
  const controller = new AbortController();
  const pending = client.get('/x', { signal: controller.signal }).then(() => 'resolved', (e: unknown) => e);
  const reason = new Error('그만');
  controller.abort(reason);
  assert.equal(await pending, reason);
  assert.equal(calls.length, 0);
});

test('조회 재시도: 응답 없음·읽을 수 없는 응답·5xx만 최대 2번, 4xx와 주입 실패는 재시도하지 않는다', () => {
  const error = (kind: 'http' | 'network' | 'invalid-response' | 'injected', status: number | null) =>
    new ApiError({ kind, status, code: 'X' });
  assert.equal(QUERY_DEFAULTS.maxRetries, 2);
  const cases: Array<[unknown, boolean]> = [
    [error('network', null), true],
    [error('network', 502), true],
    [error('invalid-response', 502), true],
    [error('http', 500), true],
    [error('http', 503), true],
    [new Error('알 수 없는 오류'), true],
    [error('http', 400), false],
    [error('http', 401), false],
    [error('http', 404), false],
    [error('http', 429), false],
    [error('invalid-response', 404), false],
    [error('injected', null), false],
  ];
  for (const [value, expected] of cases) {
    assert.equal(shouldRetryQuery(0, value), expected, String((value as Error).message ?? value));
    assert.equal(shouldRetryQuery(1, value), expected);
    assert.equal(shouldRetryQuery(2, value), false, '세 번째 실패 뒤에는 멈춘다');
  }
});

test('QueryClient 기본값: 조회는 재시도 정책과 10초 신선도, 변경은 재시도 없음', () => {
  const client = createQueryClient();
  const defaults = client.getDefaultOptions();
  assert.equal(defaults.queries?.retry, shouldRetryQuery);
  assert.equal(defaults.queries?.staleTime, 10_000);
  assert.equal(defaults.mutations?.retry, false);
  client.clear();
});

test('학교 검색 queryKey는 검색어와 크기를 담는다', () => {
  assert.deepEqual(universityKeys.search('한양', 20), ['universities', 'search', { keyword: '한양', size: 20 }]);
});
