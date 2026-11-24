import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';

// GET /users/me fills the game's player (SCRUM-153). Pure parts first, then the app's root sync against a fake
// backend; the real HUD against the real backend is the browser check.
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

const { render, screen, fireEvent, cleanup, act, waitFor } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createApiFaults } = await import('../src/api/faults');
const { createQueryClient } = await import('../src/api/queryClient');
const { accountFromMyInfo, getMyInfo, useIsAdmin, userKeys, USER_STATUSES } = await import('../src/api/users');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { useMySchool } = await import('../src/hooks/useSchoolViews');
const { ApiPlayerSync } = await import('../src/components/ApiPlayerSync');
const { ApiLoginPhone, ApiSessionWatcher } = await import('../src/components/onboarding/ApiLoginPhone');
const { DEFAULT_PLAYER, initialSchoolScore } = await import('../src/data/gameRules');
const { REAL_UNIVERSITIES } = await import('../src/data/universitiesData');
const { SCHOOL_ID } = await import('../src/data/schoolIds');
const { countByRegion, getSchoolViews } = await import('../src/data/schoolDirectory');
type MyInfo = import('../src/api/users').MyInfo;

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

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const ok = (data: unknown) => json(200, { code: 'SUCCESS', message: '요청이 성공했습니다.', data });
const fail = (status: number, code: string, message: string) => json(status, { code, message, data: null });

const STUDENT: MyInfo = {
  id: 3,
  nickname: '카이새내기',
  email: 'student@kaist.ac.kr',
  role: 'USER',
  status: 'ACTIVE',
  profileImageUrl: null,
  university: { id: SCHOOL_ID.kaist, name: 'KAIST', logoUrl: null },
  department: { id: 41, name: '전산학부' },
  clicker: { level: 2, clickPower: 1, nextLevelRequiredScore: null },
  totalContribScore: 1234,
  totalAttackScore: 0,
};
const ADMIN: MyInfo = {
  ...STUDENT,
  id: 9,
  nickname: '운영자',
  email: 'admin@hanyang.ac.kr',
  role: 'ADMIN',
  university: { id: SCHOOL_ID.hanyang, name: '한양대학교', logoUrl: null },
  department: { id: 7, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: null },
  totalContribScore: 50,
};

/** The developer account's school (#144): hidden on the server, so it is not in the static school list. */
const OFF_LIST_SCHOOL_ID = 9001;
const OPERATOR: MyInfo = {
  ...STUDENT,
  id: 77,
  nickname: '와플운영팀',
  email: 'dev-account@example.com',
  role: 'ADMIN',
  university: { id: OFF_LIST_SCHOOL_ID, name: '와플대학교', logoUrl: null },
  department: { id: 6697, name: '운영팀' },
  totalContribScore: 0,
};

// ---------------------------------------------------------------------------------------------------------------
// API module
// ---------------------------------------------------------------------------------------------------------------

test('내 정보는 GET /api/v1/users/me에 토큰을 붙여 받는다', async () => {
  const sent: Array<[string, string, string | null]> = [];
  const fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    sent.push([init?.method ?? 'GET', String(input), new Headers(init?.headers).get('Authorization')]);
    return ok(STUDENT);
  }) as typeof globalThis.fetch;
  const client = createApiClient({ fetch, faults: createApiFaults({ delayMs: 0, failDelayMs: 0 }) });
  client.session.signIn('access-1');
  assert.deepEqual(await getMyInfo(client), STUDENT);
  assert.deepEqual(sent, [['GET', '/api/v1/users/me', 'Bearer access-1']]);
});

test('게임에 넘기는 값: 학교 ID·학교 이름·학과 이름·닉네임·이메일·기여도·클리커 레벨', () => {
  assert.deepEqual(accountFromMyInfo(STUDENT), {
    userId: '3',
    schoolId: SCHOOL_ID.kaist,
    schoolName: 'KAIST',
    department: '전산학부',
    nickname: '카이새내기',
    email: 'student@kaist.ac.kr',
    contribution: 1234,
    clickerLevel: 2,
  });
});

// Sensor: MyInfo copies MyInfoResponse by hand; a field added, renamed or removed there must be copied here.
test('MyInfo 필드는 MyInfoResponse.java 레코드와 같고, 상태 값은 UserStatus.java와 같다', () => {
  const javaRoot = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/user/', import.meta.url);
  const source = readFileSync(new URL('dto/MyInfoResponse.java', javaRoot), 'utf8');
  const records = new Map([...source.matchAll(/record (\w+)\(([^)]*)\)/g)].map((m) => [
    m[1],
    m[2].split(',').map((part) => part.trim().split(/\s+/).at(-1)),
  ]));
  const fix = 'src/api/users.ts의 MyInfo를 MyInfoResponse.java에 맞추세요';
  assert.deepEqual(records.get('MyInfoResponse'), Object.keys(STUDENT), fix);
  assert.deepEqual(records.get('UniversityInfo'), Object.keys(STUDENT.university), fix);
  assert.deepEqual(records.get('DepartmentInfo'), Object.keys(STUDENT.department), fix);
  assert.deepEqual(records.get('ClickerInfo'), Object.keys(STUDENT.clicker), fix);

  const statuses = [...readFileSync(new URL('entity/UserStatus.java', javaRoot), 'utf8').matchAll(/^\s+([A-Z_]+)[,;(]/gm)].map((m) => m[1]);
  assert.deepEqual(statuses, [...USER_STATUSES], 'src/api/users.ts의 USER_STATUSES를 UserStatus.java에 맞추세요');
});

// ---------------------------------------------------------------------------------------------------------------
// The app's root sync
// ---------------------------------------------------------------------------------------------------------------

const PASSWORDS: Record<string, MyInfo> = { 'student-pass1!': STUDENT, 'admin-pass1!': ADMIN };
type Game = ReturnType<typeof useMockGame>;

/** What the HUD and the account screens read: the school from the player's ID, the player and the game values. */
function Probe({ onGame }: { onGame: (game: Game) => void }) {
  const game = useMockGame();
  const school = useMySchool();
  const admin = useIsAdmin();
  onGame(game);
  return React.createElement('output', { 'data-testid': 'probe' }, JSON.stringify({
    school: school.name,
    nickname: game.player.nickname,
    department: game.player.department,
    email: game.player.email,
    contribution: game.state.contribution,
    clicker: game.clicker.level,
    schoolScore: game.state.schoolScore,
    admin,
  }));
}
const probe = () => JSON.parse(screen.getByTestId('probe').textContent ?? '{}') as Record<string, unknown>;

function mount({ restoreAs = STUDENT as MyInfo | null, me }: { restoreAs?: MyInfo | null; me?: () => Response } = {}) {
  const tokens = new Map<string, MyInfo>();
  let issued = 0;
  const issue = (user: MyInfo) => {
    const token = `token-${++issued}`;
    tokens.set(token, user);
    return token;
  };
  const calls: string[] = [];
  const fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const route = `${init?.method ?? 'GET'} ${new URL(String(input), 'http://localhost').pathname}`;
    const auth = new Headers(init?.headers).get('Authorization');
    calls.push(auth ? `${route} ${auth}` : route);
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) as Record<string, string> : {};
    switch (route) {
      case 'POST /api/v1/auth/reissue':
        return restoreAs
          ? ok({ accessToken: issue(restoreAs), expiresInSeconds: 3600 })
          : fail(401, 'AUTH_INVALID_REFRESH_TOKEN', '로그인이 만료되었습니다. 다시 로그인해주세요.');
      case 'POST /api/v1/auth/login': {
        const user = PASSWORDS[body.password];
        if (!user) return fail(401, 'AUTH_INVALID_CREDENTIALS', '이메일 또는 비밀번호가 올바르지 않습니다.');
        return ok({ accessToken: issue(user), expiresInSeconds: 3600, user: { id: user.id, nickname: user.nickname, universityId: user.university.id, role: user.role } });
      }
      case 'POST /api/v1/auth/logout':
        return new Response(null, { status: 204 });
      case 'GET /api/v1/users/me': {
        if (me) return me();
        const user = tokens.get(auth?.replace('Bearer ', '') ?? '');
        return user ? ok(user) : fail(401, 'COMMON_UNAUTHORIZED', '로그인이 필요합니다.');
      }
      default:
        return fail(404, 'COMMON_NOT_FOUND', '없음');
    }
  }) as typeof globalThis.fetch;
  const client = createApiClient({ fetch, faults: createApiFaults({ delayMs: 0, failDelayMs: 0 }) });
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  const game = { current: null as Game | null };
  render(React.createElement(React.StrictMode, null, React.createElement(ApiProvider, {
    client,
    queryClient,
    children: React.createElement(MockGameProvider, {
      children: [
        React.createElement(ApiSessionWatcher, { key: 'watcher', onEnded: () => undefined }),
        React.createElement(ApiPlayerSync, { key: 'sync' }),
        React.createElement(Probe, { key: 'probe', onGame: (value) => { game.current = value; } }),
        React.createElement(ApiLoginPhone, { key: 'login', onGoToSignup: () => undefined, onGoToCampus: () => undefined }),
      ],
    }),
  })));
  return { calls, queryClient, game };
}

const KAIST_NAME = REAL_UNIVERSITIES.find((school) => school.id === SCHOOL_ID.kaist)!.name;

test('새로 고친 뒤 세션이 복구되면 /users/me 값으로 학교·닉네임·학과·기여도·클리커를 채운다', async () => {
  const { calls } = mount();
  const before = probe();
  assert.equal(before.nickname, DEFAULT_PLAYER.nickname, '내 정보가 오기 전에는 목업 기본값');

  await waitFor(() => assert.equal(probe().nickname, '카이새내기'));
  assert.deepEqual(probe(), {
    school: KAIST_NAME,
    nickname: '카이새내기',
    department: '전산학부',
    email: 'student@kaist.ac.kr',
    contribution: 1234,
    clicker: 2,
    schoolScore: initialSchoolScore(SCHOOL_ID.kaist),
    admin: false,
  });
  assert.equal((await screen.findByTestId('signed-in')).querySelector('h2')?.textContent, '카이새내기님, 로그인되어 있어요',
    '새로 고친 뒤에도 계정 화면에 닉네임을 보인다');
  assert.deepEqual(calls, ['POST /api/v1/auth/reissue', 'GET /api/v1/users/me Bearer token-1']);
});

test('#144: 정적 학교 목록에 없는 학교(숨김 학교) 회원도 멈추지 않고, 내 학교는 서버 이름·점수 0으로 보이며 지도 목록에는 넣지 않는다', async () => {
  assert.equal(REAL_UNIVERSITIES.some((school) => school.id === OFF_LIST_SCHOOL_ID), false, '테스트 전제: 목록 밖 학교 ID');
  mount({ restoreAs: OPERATOR });

  await waitFor(() => assert.equal(probe().nickname, '와플운영팀'));
  assert.deepEqual(probe(), {
    school: '와플대학교',
    nickname: '와플운영팀',
    department: '운영팀',
    email: 'dev-account@example.com',
    contribution: 0,
    clicker: 2,
    schoolScore: 0,
    admin: true,
  });
  assert.equal(initialSchoolScore(OFF_LIST_SCHOOL_ID), 0);
  const views = getSchoolViews(0, OFF_LIST_SCHOOL_ID);
  assert.equal(views.some((school) => school.id === OFF_LIST_SCHOOL_ID), false, '지도·검색 목록에 숨김 학교를 넣지 않는다');
  assert.equal([...countByRegion(views).values()].reduce((sum, count) => sum + count, 0), REAL_UNIVERSITIES.length);
});

test('같은 값으로 다시 받으면 게임을 건드리지 않는다 (서버 클릭 연동 전 로컬 점수 보존)', async () => {
  const { queryClient, game, calls } = mount();
  await waitFor(() => assert.equal(probe().contribution, 1234));
  await act(async () => {
    game.current!.tap(false);
  });
  const tapped = probe().contribution as number;
  assert.ok(tapped > 1234);

  await act(async () => {
    await queryClient.refetchQueries({ queryKey: userKeys.me });
  });
  assert.equal(calls.filter((call) => call.startsWith('GET /api/v1/users/me')).length, 2);
  assert.equal(probe().contribution, tapped);
});

test('로그아웃하면 내 정보 캐시를 지우고, 다른 계정으로 로그인하면 그 계정 값과 역할을 쓴다', async () => {
  const { queryClient, calls } = mount();
  await waitFor(() => assert.equal(probe().nickname, '카이새내기'));

  fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }));
  await screen.findByRole('heading', { name: '로그인' });
  assert.equal(queryClient.getQueryData(userKeys.me), undefined, '다음 로그인에 이전 계정 정보를 쓰지 않는다');
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 20));
  });
  assert.equal(calls.at(-1), 'POST /api/v1/auth/logout Bearer token-1', '로그아웃한 뒤 토큰 없이 내 정보를 다시 요청하지 않는다');
  assert.equal(queryClient.getQueryState(userKeys.me)?.status, 'pending', '실패 상태가 남으면 다음 로그인에 오류 문구가 잠깐 보인다');

  fireEvent.change(screen.getByLabelText('학교 이메일 주소'), { target: { value: ADMIN.email } });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'admin-pass1!' } });
  fireEvent.click(screen.getByRole('button', { name: '로그인하기' }));
  await waitFor(() => assert.equal(probe().nickname, '운영자'));
  assert.deepEqual(probe(), {
    school: '한양대학교',
    nickname: '운영자',
    department: '컴퓨터소프트웨어학부',
    email: 'admin@hanyang.ac.kr',
    contribution: 50,
    clicker: 1,
    schoolScore: initialSchoolScore(SCHOOL_ID.hanyang),
    admin: true,
  });
  assert.deepEqual(calls.slice(-2), ['POST /api/v1/auth/login', 'GET /api/v1/users/me Bearer token-2']);
});

test('내 정보를 받지 못하면 계정 화면에 알리고 게임은 기본값 그대로 둔다', async () => {
  const { queryClient } = mount({ me: () => fail(500, 'COMMON_INTERNAL_ERROR', '서버 오류가 발생했습니다.') });
  // The default retries wait seconds between tries; this test is about what the screen shows after the last one.
  queryClient.setQueryDefaults(userKeys.me, { retry: false });
  assert.match((await screen.findByRole('alert')).textContent ?? '', /^내 정보를 불러오지 못했습니다\./);
  assert.equal(screen.getByTestId('signed-in').querySelector('h2')?.textContent, '로그인되어 있어요');
  assert.equal(probe().nickname, DEFAULT_PLAYER.nickname);
});

test('복구할 세션이 없으면 /users/me를 보내지 않는다', async () => {
  const { calls } = mount({ restoreAs: null });
  await screen.findByRole('heading', { name: '로그인' });
  assert.deepEqual(calls, ['POST /api/v1/auth/reissue']);
  assert.equal(probe().nickname, DEFAULT_PLAYER.nickname);
});
