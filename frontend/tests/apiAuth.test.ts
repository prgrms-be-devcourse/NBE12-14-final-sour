import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { createApiClient } from '../src/api/client';
import { createApiFaults } from '../src/api/faults';
import {
  checkNicknameAvailability,
  login,
  logout,
  nicknameProblem,
  NICKNAME_RULE,
  PASSWORD_PATTERN,
  passwordProblem,
  requestEmailCode,
  restoreSession,
  signup,
  SIGNUP_ERROR_CODES,
  SIGNUP_SERVER_RULES,
  verifyEmailCode,
} from '../src/api/auth';
import { getDepartments } from '../src/api/universities';

// Sign-up API module (SCRUM-151): what each call sends, and the input rules copied from the backend. The copies are
// checked against the Java source, so a rule changed on one side only fails here.

const JAVA_ROOT = new URL('../../src/main/java/com/back/nbe1214finalsour/', import.meta.url);
const javaSource = (path: string) => readFileSync(new URL(path, JAVA_ROOT), 'utf8');

interface Sent { url: string; method: string; body: unknown; authorization: string | null }

function recordingClient(data: unknown = {}) {
  const sent: Sent[] = [];
  const fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    sent.push({
      url: String(input),
      method: init?.method ?? 'GET',
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      authorization: headers.get('Authorization'),
    });
    return new Response(JSON.stringify({ code: 'SUCCESS', message: '요청이 성공했습니다.', data }), { status: 200 });
  }) as typeof globalThis.fetch;
  const client = createApiClient({ fetch, faults: createApiFaults({ delayMs: 0, failDelayMs: 0 }) });
  // A signed-in session must not leak its token into the public sign-up calls.
  client.session.signIn('signed-in-token');
  return { client, sent };
}

test('가입 API는 /api/v1 경로로 보내고, 로그인 중이어도 토큰을 붙이지 않는다', async () => {
  const { client, sent } = recordingClient();
  await requestEmailCode(client, 'a@hanyang.ac.kr');
  await verifyEmailCode(client, 'a@hanyang.ac.kr', '012345');
  await signup(client, { signupToken: 't', nickname: '새내기', password: 'abc123!@', departmentId: 7 });
  await checkNicknameAvailability(client, '새 내기');
  await getDepartments(client, 8);

  assert.deepEqual(sent.map(({ method, url }) => `${method} ${url}`), [
    'POST /api/v1/auth/email/code',
    'POST /api/v1/auth/email/verify',
    'POST /api/v1/auth/signup',
    'GET /api/v1/users/nickname/availability?nickname=%EC%83%88+%EB%82%B4%EA%B8%B0',
    'GET /api/v1/universities/8/departments',
  ]);
  assert.deepEqual(sent.map((entry) => entry.authorization), [null, null, null, null, null]);
  assert.deepEqual(sent[0].body, { email: 'a@hanyang.ac.kr' });
  assert.deepEqual(sent[1].body, { email: 'a@hanyang.ac.kr', code: '012345' });
  assert.deepEqual(sent[2].body, { signupToken: 't', nickname: '새내기', password: 'abc123!@', departmentId: 7 },
    '학교는 보내지 않는다 (서버가 인증된 이메일로 정한다)');
});

test('닉네임 규칙: 2~12자(String.length), 앞뒤 공백 불가', () => {
  for (const ok of ['새내', '열정학번사자', 'a b', 'x'.repeat(12)]) assert.equal(nicknameProblem(ok), null, ok);
  for (const bad of ['', '새', ' 새내기', '새내기 ', 'x'.repeat(13), '😀'.repeat(7)]) {
    assert.match(nicknameProblem(bad) ?? '', /2~12자/, JSON.stringify(bad));
  }
});

test('비밀번호 규칙: 영문·숫자·특수문자 포함 8~64자 ASCII, 확인 값이 같아야 한다', () => {
  assert.equal(passwordProblem('abcd123!', 'abcd123!'), null);
  for (const bad of ['abcd1234', 'abcdefg!', '1234567!', 'ab1!', 'abc 123!', '비밀번호123!', `a1!${'x'.repeat(62)}`]) {
    assert.match(passwordProblem(bad, bad) ?? '', /8~64자/, bad);
  }
  assert.equal(passwordProblem('abcd123!', 'abcd123?'), '비밀번호 확인이 일치하지 않습니다.');
});

// Sensors: the client copies these from the backend; a change there must be copied here.
test('비밀번호·닉네임 규칙은 @Password·@Nickname 소스와 같다', () => {
  const password = javaSource('domain/user/validation/Password.java').match(/@Pattern\(regexp = "(.+)"\)/);
  assert.ok(password, '@Password의 @Pattern을 찾지 못함');
  assert.equal(PASSWORD_PATTERN.source, `^${password[1].replaceAll('\\\\', '\\')}$`,
    'src/api/auth.ts의 PASSWORD_PATTERN을 Password.java에 맞추세요');

  const nickname = javaSource('domain/user/validation/Nickname.java');
  const size = nickname.match(/@Size\(min = (\d+), max = (\d+)\)/);
  const pattern = nickname.match(/@Pattern\(regexp = "(.+)"\)/);
  assert.ok(size && pattern, '@Nickname의 @Size·@Pattern을 찾지 못함');
  assert.deepEqual([NICKNAME_RULE.min, NICKNAME_RULE.max], [Number(size[1]), Number(size[2])]);
  assert.equal(NICKNAME_RULE.pattern.source, `^${pattern[1].replaceAll('\\\\', '\\')}$`);
});

test('재요청 대기·시도 횟수는 EmailVerificationService 상수와 같다', () => {
  const service = javaSource('domain/auth/service/EmailVerificationService.java');
  assert.equal(Number(service.match(/COOLDOWN = Duration\.ofSeconds\((\d+)\)/)?.[1]), SIGNUP_SERVER_RULES.resendCooldownSeconds);
  assert.equal(Number(service.match(/MAX_ATTEMPTS = (\d+);/)?.[1]), SIGNUP_SERVER_RULES.maxCodeAttempts);
});

test('가입 화면이 이름으로 다루는 오류 코드는 ErrorCode.java에 있다', () => {
  const source = javaSource('global/exception/ErrorCode.java');
  const backendCodes = new Set([...source.matchAll(/^\s+([A-Z][A-Z0-9_]+)\(HttpStatus\./gm)].map((m) => m[1]));
  for (const code of Object.values(SIGNUP_ERROR_CODES)) assert.ok(backendCodes.has(code), `${code}가 ErrorCode.java에 없음`);
});

// ---------------------------------------------------------------------------------------------------------------
// Login, logout and the session restore (SCRUM-152)
// ---------------------------------------------------------------------------------------------------------------

type Answer = (url: string, init?: RequestInit) => Response;
const answer = (status: number, code: string, data: unknown = null) =>
  new Response(JSON.stringify({ code, message: code === 'SUCCESS' ? '요청이 성공했습니다.' : `${code} 문구`, data }), { status });

function scriptedClient(answers: Answer[]) {
  const sent: Array<{ route: string; authorization: string | null }> = [];
  const fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    sent.push({ route: `${init?.method ?? 'GET'} ${String(input)}`, authorization: new Headers(init?.headers).get('Authorization') });
    const next = answers.shift();
    if (!next) throw new Error(`예상하지 않은 요청: ${String(input)}`);
    return next(String(input), init);
  }) as typeof globalThis.fetch;
  return { client: createApiClient({ fetch, faults: createApiFaults({ delayMs: 0, failDelayMs: 0 }) }), sent };
}

const LOGIN_DATA = { accessToken: 'access-1', expiresInSeconds: 3600, user: { id: 3, nickname: '새내기', universityId: 8, role: 'USER' } };

test('로그인은 토큰 없이 보내고, 받은 access 토큰으로 세션을 연다', async () => {
  const { client, sent } = scriptedClient([() => answer(200, 'SUCCESS', LOGIN_DATA)]);
  const response = await login(client, 'a@hanyang.ac.kr', 'abcd123!');
  assert.equal(response.user.nickname, '새내기');
  assert.deepEqual(sent, [{ route: 'POST /api/v1/auth/login', authorization: null }]);
  assert.deepEqual(client.session.getState(), { status: 'signed-in', accessToken: 'access-1' });
});

test('로그인 실패는 서버 문구로 던지고 세션을 열지 않는다', async () => {
  const { client } = scriptedClient([() => answer(401, 'AUTH_INVALID_CREDENTIALS')]);
  await assert.rejects(login(client, 'a@hanyang.ac.kr', 'wrong'), { code: 'AUTH_INVALID_CREDENTIALS' });
  assert.equal(client.session.getState().status, 'signed-out');
});

test('로그아웃은 토큰을 붙여 보내고 성공하면 세션을 닫는다. 실패하면 세션을 남긴다', async () => {
  const { client, sent } = scriptedClient([
    () => new Response(null, { status: 500 }),
    () => new Response(null, { status: 204 }),
  ]);
  client.session.signIn('access-1');
  await assert.rejects(logout(client));
  assert.equal(client.session.getState().status, 'signed-in', '쿠키가 남아 있으면 새로 고칠 때 다시 로그인되므로 세션도 남긴다');

  await logout(client);
  assert.deepEqual(client.session.getState(), { status: 'signed-out', ended: null }, '스스로 로그아웃하면 안내 문구가 없다');
  assert.deepEqual(sent.map((entry) => entry.authorization), ['Bearer access-1', 'Bearer access-1']);
});

test('세션 복구: reissue가 되면 true, 복구할 세션이 없으면 false, 연결 실패는 던진다', async () => {
  const restored = scriptedClient([() => answer(200, 'SUCCESS', { accessToken: 'access-2', expiresInSeconds: 3600 })]);
  assert.equal(await restoreSession(restored.client), true);
  assert.deepEqual(restored.sent, [{ route: 'POST /api/v1/auth/reissue', authorization: null }]);
  assert.equal(restored.client.session.getAccessToken(), 'access-2');

  for (const code of ['AUTH_INVALID_REFRESH_TOKEN', 'AUTH_SESSION_REPLACED']) {
    const none = scriptedClient([() => answer(401, code)]);
    assert.equal(await restoreSession(none.client), false, code);
    assert.deepEqual(none.client.session.getState(), { status: 'signed-out', ended: null }, '첫 방문에는 로그아웃 안내를 띄우지 않는다');
  }

  const offline = scriptedClient([() => new Response(null, { status: 502 })]);
  await assert.rejects(restoreSession(offline.client), { kind: 'network' });
});
