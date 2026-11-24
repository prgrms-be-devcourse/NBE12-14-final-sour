import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import type { MyInfo } from '../src/api/users';
import type { ClickRequest } from '../src/api/clicks';
import type { ClickerLevel } from '../src/api/clickers';

const assets = registerHooks({ load(url, context, nextLoad) {
  if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) return { format: 'module', source: 'export default "/asset";', shortCircuit: true };
  return nextLoad(url, context);
} });
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
const { render, screen, fireEvent, act, cleanup, waitFor } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { getClickerLevels, upgradeClicker } = await import('../src/api/clickers');
const { userKeys } = await import('../src/api/users');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { INITIAL_GAME_STATE } = await import('../src/utils/gameModel');
const { useApiCampusClicks } = await import('../src/hooks/useCampusClicks');
const { useApiClickerController } = await import('../src/hooks/useClicker');
const { ApiPlayerSync } = await import('../src/components/ApiPlayerSync');
const { ApiSessionWatcher } = await import('../src/components/onboarding/ApiLoginPhone');
const clients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => { cleanup(); for (const client of clients.splice(0)) client.clear(); });
after(() => { assets.deregister(); dom.window.close(); });

const ME: MyInfo = {
  id: 10, nickname: '강화학생', email: 'test@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: 1, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 11, clickPower: 7, nextLevelRequiredScore: 50 }, totalContribScore: 80, totalAttackScore: 0,
};
const LEVELS: ClickerLevel[] = [
  { level: 11, clickPower: 7, requiredScore: 0 }, { level: 12, clickPower: 19, requiredScore: 50 },
  { level: 13, clickPower: 31, requiredScore: 150 },
];
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status: 200 });
const fail = (status: number, code: string) => new Response(JSON.stringify({ code, message: code === 'CLICKER_NOT_ENOUGH_SCORE' ? '업그레이드에 필요한 기여도가 부족합니다.' : code, data: null }), { status });
let latest: ReturnType<typeof useApiClickerController>;
let clickController: ReturnType<typeof useApiCampusClicks>;
const game = () => JSON.parse(screen.getByTestId('game').textContent ?? '{}') as { contribution: number; clickerId: number; schoolScore: number; departmentScore: number };
function Probe() {
  const clicks = useApiCampusClicks();
  clickController = clicks;
  const view = useApiClickerController(clicks);
  latest = view;
  const mock = useMockGame();
  const [visible, setVisible] = React.useState(true);
  return React.createElement(React.Fragment, null,
    React.createElement(ApiPlayerSync, { getPendingScore: clicks.getPendingScore }),
    React.createElement('output', { 'data-testid': 'game' }, JSON.stringify(mock.state)),
    React.createElement('button', { onClick: () => clicks.tap(false) }, '클릭'),
    React.createElement('button', { onClick: () => setVisible((value) => !value) }, '화면 전환'),
    visible && React.createElement('button', { onClick: view.api!.upgrade, disabled: !view.api!.canUpgrade }, '강화'),
    view.api?.reconcile && React.createElement('button', { onClick: view.api.reconcile }, '서버 상태 확인'),
    view.api?.error && React.createElement('p', { role: 'alert' }, view.api.error),
  );
}
interface Call { path: string; body?: unknown; auth: string | null; signal?: AbortSignal | null }
function mount(options: { profile?: MyInfo; levels?: () => Promise<Response>; upgrade?: () => Promise<Response>; readMe?: () => Promise<Response>; click?: () => Promise<Response>; signedIn?: boolean } = {}) {
  let profile = options.profile ?? ME;
  let schoolScore = 100;
  let departmentScore = 20;
  const calls: Call[] = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    const path = String(input);
    const body = init?.body ? JSON.parse(String(init.body)) as unknown : undefined;
    calls.push({ path, body, auth: new Headers(init?.headers).get('Authorization'), signal: init?.signal });
    if (path.endsWith('/clickers')) return options.levels ? options.levels() : ok(LEVELS);
    if (path.endsWith('/clickers/upgrade')) {
      if (options.upgrade) return options.upgrade();
      const row = LEVELS.find((row) => row.level === profile.clicker.level + 1)!;
      profile = { ...profile, totalContribScore: profile.totalContribScore - (profile.clicker.nextLevelRequiredScore ?? 0),
        clicker: { level: row.level, clickPower: row.clickPower, nextLevelRequiredScore: 150 } };
      return ok({ ...profile.clicker, myContribScore: profile.totalContribScore });
    }
    if (path.endsWith('/users/me')) return options.readMe ? options.readMe() : ok(profile);
    if (path.endsWith('/clicks')) {
      if (options.click) return options.click();
      const earnedScore = (body as ClickRequest).clickCount * profile.clicker.clickPower;
      schoolScore += earnedScore; departmentScore += earnedScore;
      profile = { ...profile, totalContribScore: profile.totalContribScore + earnedScore };
      return ok({ acceptedClicks: (body as ClickRequest).clickCount, earnedScore, multiplier: 1, myContribScore: profile.totalContribScore,
        university: { id: 1, totalScore: schoolScore, rank: 1, logoUrl: null }, department: { id: 7, totalScore: departmentScore }, schoolClicksLastSecond: 1 });
    }
    if (path.endsWith('/auth/reissue')) return options.signedIn === false ? fail(401, 'AUTH_INVALID_REFRESH_TOKEN') : ok({ accessToken: 'member-a', expiresInSeconds: 3600 });
    throw new Error(`Unexpected path: ${path}`);
  }) as typeof fetch });
  const queryClient = createQueryClient();
  if (options.signedIn !== false) { client.session.signIn('member-a'); queryClient.setQueryData(userKeys.me, profile); }
  clients.push(queryClient);
  render(React.createElement(React.StrictMode, null, React.createElement(ApiProvider, { client, queryClient,
    children: React.createElement(MockGameProvider, { initialState: { ...INITIAL_GAME_STATE, schoolScore, departmentScore },
      children: [React.createElement(ApiSessionWatcher, { key: 'session', onEnded: () => undefined }), React.createElement(Probe, { key: 'probe' })],
    }),
  })));
  return { client, queryClient, calls, upgrades: () => calls.filter((call) => call.path.endsWith('/upgrade')),
    setServerProfile: (value: MyInfo) => { profile = value; } };
}
const ready = async () => waitFor(() => assert.equal(latest.api?.loadNotice, null));
const click = () => fireEvent.click(screen.getByRole('button', { name: '클릭' }));
const upgrade = () => fireEvent.click(screen.getByRole('button', { name: '강화' }));

test('공개 레벨표는 익명 GET, 업그레이드는 본문 없는 인증 POST이며 DTO를 그대로 해제한다', async () => {
  const calls: Array<{ path: string; method?: string; body?: BodyInit | null; auth: string | null }> = [];
  const result = { level: 12, clickPower: 19, nextLevelRequiredScore: 150, myContribScore: 30 };
  const client = createApiClient({ fetch: (async (input, init) => {
    calls.push({ path: String(input), method: init?.method, body: init?.body, auth: new Headers(init?.headers).get('Authorization') });
    return ok(String(input).endsWith('/upgrade') ? result : LEVELS);
  }) as typeof fetch });
  client.session.signIn('member');
  assert.deepEqual(await getClickerLevels(client), LEVELS);
  assert.deepEqual(await upgradeClicker(client), result);
  assert.deepEqual(calls, [
    { path: '/api/v1/clickers', method: 'GET', body: undefined, auth: null },
    { path: '/api/v1/clickers/upgrade', method: 'POST', body: undefined, auth: 'Bearer member' },
  ]);
});

test('현재 세기·비용은 프로필, 다음 세기는 서버 레벨표를 사용하며 목업 10레벨 상한을 섞지 않는다', async () => {
  mount(); await ready();
  assert.equal(latest.level, 11); assert.equal(latest.power, 7); assert.equal(latest.upgradeCost, 50);
  assert.deepEqual(latest.nextClicker, { level: 12, power: 19 });
  assert.equal(latest.normalPower, 7); assert.equal(latest.criticalPower, 7);
  assert.equal(latest.api?.canUpgrade, true);
});

test('강화 응답 전에는 차감하지 않고 같은 프레임 중복 POST·클릭을 막으며 화면 전환 후에도 결과를 반영한다', async () => {
  let finish!: (response: Response) => void;
  const f = mount({ upgrade: () => new Promise((resolve) => { finish = resolve; }) }); await ready();
  const before = game();
  await act(async () => { latest.api!.upgrade(); latest.api!.upgrade(); clickController.tap(false); });
  assert.equal(f.upgrades().length, 1); assert.equal(game().contribution, 80);
  assert.equal(latest.api?.pending, true);
  fireEvent.click(screen.getByRole('button', { name: '화면 전환' }));
  await act(async () => { finish(ok({ level: 12, clickPower: 19, nextLevelRequiredScore: 150, myContribScore: 30 })); });
  await waitFor(() => assert.equal(game().contribution, 30));
  assert.equal(latest.level, 12); assert.equal(latest.power, 19); assert.equal(game().clickerId, 12);
  assert.equal(game().schoolScore, before.schoolScore); assert.equal(game().departmentScore, before.departmentScore);
  assert.equal(f.queryClient.getQueryData<MyInfo>(userKeys.me)?.clicker.clickPower, 19);
  click(); assert.equal(game().contribution, 49, '성공 직후 다음 탭은 새 세기를 사용한다');
});

test('미확정 클릭은 버리지 않고 확정까지 강화를 막으며 이후 새 클릭 세기를 실제 전송에도 반영한다', async () => {
  const f = mount(); await ready(); click();
  assert.equal(latest.api?.canUpgrade, false);
  await act(async () => { latest.api!.upgrade(); });
  assert.equal(f.upgrades().length, 0);
  await waitFor(() => assert.equal(latest.api?.canUpgrade, true), { timeout: 2500 });
  upgrade(); await waitFor(() => assert.equal(game().contribution, 37));
  click(); assert.equal(game().contribution, 56);
  await waitFor(() => assert.equal(f.calls.filter((call) => call.path.endsWith('/clicks')).length, 2), { timeout: 2500 });
  await waitFor(() => assert.equal(f.queryClient.getQueryData<MyInfo>(userKeys.me)?.totalContribScore, 56));
});

test('프로필 observer가 렌더되기 전 같은 프레임의 클릭도 캐시의 새 세기를 사용한다', async () => {
  const f = mount(); await ready();
  await act(async () => {
    f.queryClient.setQueryData<MyInfo>(userKeys.me, { ...ME, totalContribScore: 30,
      clicker: { level: 12, clickPower: 19, nextLevelRequiredScore: 150 } });
    assert.equal(clickController.tap(false), 19);
  });
  assert.equal(game().contribution, 49);
});

test('403으로 보류된 큐는 강화로 우회하거나 삭제할 수 없다', async () => {
  const f = mount({ click: async () => fail(403, 'ABUSE_VERIFICATION_REQUIRED') }); await ready(); click();
  await waitFor(() => assert.ok(clickController.notice?.includes('미확정 1회')), { timeout: 2500 });
  await act(async () => { latest.api!.upgrade(); });
  assert.equal(f.upgrades().length, 0); assert.equal(game().contribution, 87);
  assert.equal(latest.api?.canUpgrade, false);
});

test('400 CLICKER_NOT_ENOUGH_SCORE를 알리고 차감·레벨 변경 없이 명시적 재시도를 허용한다', async () => {
  let count = 0;
  const f = mount({ upgrade: async () => ++count === 1 ? fail(400, 'CLICKER_NOT_ENOUGH_SCORE') : ok({ level: 12, clickPower: 19, nextLevelRequiredScore: 150, myContribScore: 30 }) });
  await ready(); upgrade(); await screen.findByRole('alert');
  assert.match(screen.getByRole('alert').textContent ?? '', /기여도가 부족/);
  assert.equal(game().contribution, 80); assert.equal(latest.level, 11);
  upgrade(); await waitFor(() => assert.equal(latest.level, 12)); assert.equal(f.upgrades().length, 2);
});

for (const state of ['max', 'insufficient', 'signed-out'] as const) {
  test(`${state}: 불가능한 강화는 POST를 보내지 않는다`, async () => {
    const f = mount({ signedIn: state !== 'signed-out', profile: { ...ME, totalContribScore: state === 'insufficient' ? 49 : 80,
      clicker: { ...ME.clicker, nextLevelRequiredScore: state === 'max' ? null : 50 } } });
    await waitFor(() => assert.equal(latest.api?.canUpgrade, false));
    await act(async () => { latest.api!.upgrade(); });
    assert.equal(f.upgrades().length, 0);
    if (state === 'max') assert.equal(latest.nextClicker, null);
  });
}

for (const state of ['empty', 'failure', 'missing-next'] as const) {
  test(`레벨표 ${state}는 목업/MAX로 대체하지 않고 재조회를 제공한다`, async () => {
    let healthy = false;
    const f = mount({ levels: async () => healthy ? ok(LEVELS) : state === 'failure' ? fail(400, 'COMMON_INVALID_INPUT') : ok(state === 'empty' ? [] : LEVELS.slice(0, 1)) });
    await waitFor(() => assert.match(latest.api?.loadNotice ?? '', state === 'empty' ? /비어/ : state === 'failure' ? /불러오지 못/ : /다음 클리커/));
    assert.equal(latest.api?.canUpgrade, false); assert.equal(latest.power, 7);
    await act(async () => { latest.api!.upgrade(); }); assert.equal(f.upgrades().length, 0);
    healthy = true;
    await act(async () => { latest.api!.reload(); }); await ready(); assert.equal(latest.nextClicker?.power, 19);
  });
}

test('응답 유실은 자동·수동 POST 재전송을 막고 서버 재조회가 실패해도 잠금을 유지한다', async () => {
  let reads = 0;
  const afterUpgrade: MyInfo = { ...ME, totalContribScore: 30, clicker: { level: 12, clickPower: 19, nextLevelRequiredScore: 150 } };
  const f = mount({ upgrade: async () => { throw new TypeError('lost response'); }, readMe: async () => ++reads === 1 ? fail(503, 'COMMON_INTERNAL_ERROR') : ok(afterUpgrade) });
  await ready(); upgrade(); await screen.findByRole('button', { name: '서버 상태 확인' });
  await act(async () => { latest.api!.upgrade(); }); click();
  assert.equal(f.upgrades().length, 1); assert.equal(game().contribution, 80);
  fireEvent.click(screen.getByRole('button', { name: '서버 상태 확인' }));
  await screen.findByRole('button', { name: '서버 상태 확인' });
  assert.equal(latest.api?.canUpgrade, false); assert.equal(reads, 1);
  fireEvent.click(screen.getByRole('button', { name: '서버 상태 확인' }));
  await waitFor(() => assert.equal(latest.level, 12));
  assert.equal(game().contribution, 30); assert.equal(f.upgrades().length, 1);
  click(); assert.equal(game().contribution, 49);
});

for (const [httpStatus, code] of [[409, 'CLICKER_UPGRADE_CONFLICT'], [500, 'COMMON_INTERNAL_ERROR'], [400, 'CLICKER_MAX_LEVEL']] as const) {
  test(`${code}: 재강화 전에 서버 상태를 확인하며 POST는 자동 재시도하지 않는다`, async () => {
    const f = mount({ upgrade: async () => fail(httpStatus, code) }); await ready(); upgrade();
    await screen.findByRole('button', { name: '서버 상태 확인' });
    await act(async () => { latest.api!.upgrade(); }); assert.equal(f.upgrades().length, 1);
    click(); assert.equal(game().contribution, 80);
  });
}

test('같은 프레임 로그아웃·다른 계정 로그인 뒤 늦은 성공을 캐시·큐에 적용하지 않는다', async () => {
  let finish!: (response: Response) => void;
  const f = mount({ upgrade: () => new Promise((resolve) => { finish = resolve; }) }); await ready(); upgrade();
  await waitFor(() => assert.equal(f.upgrades().length, 1));
  f.setServerProfile({ ...ME, id: 11, totalContribScore: 500 });
  await act(async () => { f.client.session.signOut(); f.client.session.signIn('member-b'); });
  assert.equal(f.upgrades()[0].signal?.aborted, true);
  await act(async () => { finish(ok({ level: 12, clickPower: 19, nextLevelRequiredScore: 150, myContribScore: 30 })); });
  await waitFor(() => assert.equal(game().contribution, 500));
  assert.equal(f.queryClient.getQueryData<MyInfo>(userKeys.me)?.id, 11); assert.equal(latest.level, 11);
  click(); assert.equal(game().contribution, 507, '이전 강화의 잠금이나 결과가 새 계정에 남지 않는다');
});

test('강화 전에 시작한 오래된 내 정보 조회가 늦게 끝나도 강화 결과를 덮지 않는다', async () => {
  let finishRead!: (response: Response) => void;
  const f = mount({ readMe: () => new Promise((resolve) => { finishRead = resolve; }) }); await ready();
  let pending!: Promise<void>;
  await act(async () => { pending = f.queryClient.refetchQueries({ queryKey: userKeys.me }); await Promise.resolve(); });
  upgrade(); await waitFor(() => assert.equal(latest.level, 12));
  await act(async () => { finishRead(ok(ME)); await pending; });
  assert.equal(game().contribution, 30); assert.equal(latest.power, 19);
});

test('응답 유실 뒤 GET이 이전 레벨이어도 미처리로 단정하지 않고 확인된 레벨 상승 전까지 보류한다', async () => {
  const f = mount({ upgrade: async () => { throw new TypeError('late commit'); } }); await ready(); upgrade();
  await screen.findByRole('button', { name: '서버 상태 확인' });
  fireEvent.click(screen.getByRole('button', { name: '서버 상태 확인' }));
  await waitFor(() => assert.match(latest.api?.error ?? '', /아직 새 레벨이 확인되지/));
  click(); await act(async () => { latest.api!.upgrade(); });
  assert.equal(f.upgrades().length, 1); assert.equal(game().contribution, 80);
  f.setServerProfile({ ...ME, totalContribScore: 30, clicker: { level: 12, clickPower: 19, nextLevelRequiredScore: 150 } });
  fireEvent.click(screen.getByRole('button', { name: '서버 상태 확인' }));
  await waitFor(() => assert.equal(latest.level, 12));
  click(); assert.equal(game().contribution, 49); assert.equal(f.upgrades().length, 1);
});

test('다음 레벨 비용 0도 최고 단계와 구분하고 잔액 0에서 강화할 수 있다', async () => {
  mount({ profile: { ...ME, totalContribScore: 0, clicker: { ...ME.clicker, nextLevelRequiredScore: 0 } },
    levels: async () => ok(LEVELS.map((row) => row.level === 12 ? { ...row, requiredScore: 0 } : row)) });
  await ready(); assert.equal(latest.upgradeCost, 0); assert.equal(latest.api?.canUpgrade, true);
  upgrade(); await waitFor(() => assert.equal(latest.level, 12)); assert.equal(game().contribution, 0);
});

test('Java 계약 센서: DTO 필드·레벨 도달 비용·최대 레벨·인증 POST 변경 시 API 어댑터를 재검토한다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/clicker/', import.meta.url);
  const recordFields = (file: string) => readFileSync(new URL(`dto/${file}.java`, root), 'utf8').match(/record \w+\(([^)]*)\)/)?.[1]
    .replace(/\/\/[^\n]*/g, '').split(',').map((part) => part.trim().split(/\s+/).at(-1));
  const fix = 'api/clickers.ts 및 useClicker의 비용·응답 보정·재시도 정책을 Java 클리커 계약에 맞추세요';
  assert.deepEqual(recordFields('ClickerResponse'), Object.keys(LEVELS[0]), fix);
  assert.deepEqual(recordFields('ClickerUpgradeResponse'), ['level', 'clickPower', 'nextLevelRequiredScore', 'myContribScore'], fix);
  const controller = readFileSync(new URL('controller/ClickerController.java', root), 'utf8');
  assert.match(controller, /@PostMapping\("\/upgrade"\)/, fix);
  assert.match(controller, /upgrade\(@AuthenticationPrincipal AuthUser authUser\)/, fix);
  // #109: one Redis Lua call compares the expected level, then deducts and raises it. A conflict (-2) or a short
  // balance (-1) changes nothing, so a 409 needs only a fresh profile, not an observed level rise (useClicker).
  const service = readFileSync(new URL('service/ClickerUpgradeService.java', root), 'utf8');
  assert.match(service, /redisClickerUpgrader\.upgrade\(\s*userId, universityId, currentLevel, nextClicker\.getRequiredScore\(\)\)/, fix);
  assert.match(service, /upgradeResult == -1\)\s*\{\s*throw new BusinessException\(ErrorCode\.CLICKER_NOT_ENOUGH_SCORE\)/, fix);
  assert.match(service, /upgradeResult == -2\)\s*\{\s*throw new BusinessException\(ErrorCode\.CLICKER_UPGRADE_CONFLICT\)/, fix);
  const lua = readFileSync(new URL('../../../../../../resources/redis/clicker-upgrade.lua', root), 'utf8');
  const at = (text: string) => lua.indexOf(text);
  assert.ok(at('return -2') >= 0 && at('return -1') > at('return -2') && at('ZINCRBY') > at('return -1'), fix);
  const errors = readFileSync(new URL('../../global/exception/ErrorCode.java', root), 'utf8');
  assert.match(errors, /CLICKER_UPGRADE_CONFLICT\(HttpStatus\.CONFLICT,/, fix);
  // The reconcile GET must see the upgraded level before the 1-minute batch writes it to the DB.
  const users = readFileSync(new URL('../user/service/UserService.java', root), 'utf8');
  assert.match(users, /clickerInfo\(userClickerLevelCache\.find\(userId\)\.orElse\(user\.getClickerId\(\)\)\)/, fix);
});
