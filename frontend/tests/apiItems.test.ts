import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import type { MyInfo } from '../src/api/users';
import type { ActiveItemEffect, EffectSnapshot, ShopItem } from '../src/api/items';
import type { ClickRequest } from '../src/api/clicks';
import { installDialogStub } from './helpers/dialogStub';
import { assertAbsent } from './helpers/domAssert';

const assets = registerHooks({ load(url, context, nextLoad) {
  if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) return { format: 'module', source: 'export default "/asset";', shortCircuit: true };
  return nextLoad(url, context);
} });
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
installDialogStub(dom.window);
for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
const { render, screen, within, fireEvent, act, cleanup, waitFor } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { getItems, getActiveItemEffect, purchaseItem, itemKeys, effectSecondsLeft } = await import('../src/api/items');
const { userKeys } = await import('../src/api/users');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { useApiCampusClicks } = await import('../src/hooks/useCampusClicks');
const { useApiClickerController } = await import('../src/hooks/useClicker');
const { useApiItemShopController } = await import('../src/hooks/useItemShop');
const { ApiPlayerSync } = await import('../src/components/ApiPlayerSync');
const { ApiSessionWatcher } = await import('../src/components/onboarding/ApiLoginPhone');
const { ApiItemShop } = await import('../src/components/ApiItemShop');
const clients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => { cleanup(); for (const client of clients.splice(0)) client.clear(); });
after(() => { assets.deregister(); dom.window.close(); });
const ME: MyInfo = {
  id: 10, nickname: '구매학생', email: 'test@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: 1, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 11, clickPower: 7, nextLevelRequiredScore: 50 }, totalContribScore: 80, totalAttackScore: 0,
};
const COFFEE: ShopItem = { id: 801, name: '서버 커피', content: '서버에서 정한 효과', type: 'MULTIPLIER', payType: 'CONTRIBUTION', price: 70, durationSeconds: 60 };
const CREDIT: ShopItem = { id: 802, name: '서버 로고', content: '크레딧 상품', type: 'LOGO_PROFILE', payType: 'CREDIT', price: 400, durationSeconds: null };
const EFFECT: ActiveItemEffect = { itemId: 801, name: '서버 커피', multiplier: 1.5, expiredAt: '2030-01-01T12:00:00+09:00', remainingSeconds: 60 };
const ok = (data: unknown, status = 200) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status });
const fail = (status: number, code: string) => new Response(JSON.stringify({ code, message: code === 'ITEM_NOT_ENOUGH_SCORE' ? '아이템 구매에 필요한 기여도가 부족합니다.' : code, data: null }), { status });
let latest: ReturnType<typeof useApiItemShopController>;
let clicks: ReturnType<typeof useApiCampusClicks>;
let clicker: ReturnType<typeof useApiClickerController>;
const game = () => JSON.parse(screen.getByTestId('game').textContent ?? '{}') as { contribution: number; clickerId: number; schoolScore: number; departmentScore: number };
function Probe() {
  clicks = useApiCampusClicks(); clicker = useApiClickerController(clicks); latest = useApiItemShopController(clicks);
  const mock = useMockGame();
  const [visible, setVisible] = React.useState(true);
  return React.createElement(React.Fragment, null,
    React.createElement(ApiPlayerSync, { getPendingScore: clicks.getPendingScore }),
    React.createElement('output', { 'data-testid': 'game' }, JSON.stringify(mock.state)),
    React.createElement('button', { onClick: () => clicks.tap(false) }, '클릭'),
    React.createElement('button', { onClick: () => setVisible((value) => !value) }, '화면 전환'),
    visible && React.createElement(ApiItemShop, { shop: latest }),
  );
}
interface Call { path: string; method?: string; body?: BodyInit | null; auth: string | null; signal?: AbortSignal | null; idempotencyKey?: string | null }
function mount(options: { signedIn?: boolean; profile?: MyInfo; effect?: ActiveItemEffect | null; catalog?: () => Promise<Response>;
  buy?: () => Promise<Response>; activeRead?: () => Promise<Response>; readMe?: () => Promise<Response> } = {}) {
  let profile = options.profile ?? ME;
  let effect = options.effect ?? null;
  const calls: Call[] = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    const path = String(input); calls.push({ path, method: init?.method, body: init?.body, auth: new Headers(init?.headers).get('Authorization'), signal: init?.signal, idempotencyKey: new Headers(init?.headers).get('Idempotency-Key') });
    if (path.endsWith('/items')) return options.catalog ? options.catalog() : ok([COFFEE, CREDIT]);
    if (path.endsWith('/items/me/active')) return options.activeRead ? options.activeRead() : ok(effect);
    if (path.endsWith('/purchase')) {
      if (options.buy) return options.buy();
      const item = COFFEE;
      profile = { ...profile, totalContribScore: profile.totalContribScore - item.price }; effect = EFFECT;
      return ok({ userItemId: 991, item: { id: item.id, name: item.name, payType: item.payType }, price: item.price,
        expiredAt: effect.expiredAt, myContribScore: profile.totalContribScore, myCreditBalance: 1200 }, 201);
    }
    if (path.endsWith('/clickers')) return ok([{ level: 11, clickPower: 7, requiredScore: 0 }, { level: 12, clickPower: 19, requiredScore: 50 }]);
    if (path.endsWith('/upgrade')) {
      profile = { ...profile, totalContribScore: profile.totalContribScore - 50, clicker: { level: 12, clickPower: 19, nextLevelRequiredScore: null } };
      return ok({ ...profile.clicker, myContribScore: profile.totalContribScore });
    }
    if (path.endsWith('/users/me')) return options.readMe ? options.readMe() : ok(profile);
    if (path.endsWith('/clicks')) {
      const body = JSON.parse(String(init?.body)) as ClickRequest;
      const earnedScore = Math.floor(body.clickCount * profile.clicker.clickPower * (effect?.multiplier ?? 1));
      profile = { ...profile, totalContribScore: profile.totalContribScore + earnedScore };
      return ok({ acceptedClicks: body.clickCount, earnedScore, multiplier: effect?.multiplier ?? 1, myContribScore: profile.totalContribScore,
        university: { id: 1, totalScore: 100 + earnedScore, rank: 1, logoUrl: null }, department: { id: 7, totalScore: 20 + earnedScore }, schoolClicksLastSecond: body.clickCount });
    }
    if (path.endsWith('/auth/reissue')) return options.signedIn === false ? fail(401, 'AUTH_INVALID_REFRESH_TOKEN') : ok({ accessToken: 'member-a', expiresInSeconds: 3600 });
    throw new Error(`Unexpected path: ${path}`);
  }) as typeof fetch });
  const queryClient = createQueryClient(); clients.push(queryClient);
  if (options.signedIn !== false) { client.session.signIn('member-a'); queryClient.setQueryData(userKeys.me, profile); }
  render(React.createElement(React.StrictMode, null, React.createElement(ApiProvider, { client, queryClient,
    children: React.createElement(MockGameProvider, { children: [React.createElement(ApiSessionWatcher, { key: 'session', onEnded: () => undefined }), React.createElement(Probe, { key: 'probe' })] }),
  })));
  return { client, queryClient, calls, buys: () => calls.filter((call) => call.path.endsWith('/purchase')),
    setProfile: (value: MyInfo) => { profile = value; }, setEffect: (value: ActiveItemEffect | null) => { effect = value; } };
}
const ready = async () => waitFor(() => { assert.equal(latest.catalogNotice, null); assert.equal(latest.effectNotice, null); });
const buy = () => { void act(() => latest.purchase(COFFEE.id)); };
const confirm = () => { fireEvent.click(screen.getByRole('button', { name: '서버 커피 구매 선택' })); return screen.getByRole('dialog', { name: '구매 확인 · 서버 커피' }); };

test('공개 목록·인증 활성 효과·본문 없는 201 구매 DTO를 그대로 사용한다', async () => {
  const calls: Call[] = [];
  const receipt = { userItemId: 99, item: { id: 801, name: '서버 커피', payType: 'CONTRIBUTION' }, price: 70, expiredAt: EFFECT.expiredAt, myContribScore: 10, myCreditBalance: 1200 };
  const client = createApiClient({ fetch: (async (input, init) => {
    const path = String(input); calls.push({ path, method: init?.method, body: init?.body, auth: new Headers(init?.headers).get('Authorization') });
    return ok(path.endsWith('/purchase') ? receipt : path.endsWith('/active') ? null : [COFFEE, CREDIT], path.endsWith('/purchase') ? 201 : 200);
  }) as typeof fetch }); client.session.signIn('member');
  assert.deepEqual(await getItems(client), [COFFEE, CREDIT]); assert.equal(await getActiveItemEffect(client), null);
  assert.deepEqual(await purchaseItem(client, 801, 'purchase-key'), receipt);
  assert.deepEqual(calls, [
    { path: '/api/v1/items', method: 'GET', body: undefined, auth: null },
    { path: '/api/v1/items/me/active', method: 'GET', body: undefined, auth: 'Bearer member' },
    { path: '/api/v1/items/801/purchase', method: 'POST', body: undefined, auth: 'Bearer member' },
  ]);
});

test('별도 구매 시도마다 UUID 멱등 키를 전송한다', async () => {
  const f = mount({ buy: async () => fail(400, 'ITEM_NOT_ENOUGH_SCORE') });
  await ready();
  buy(); await waitFor(() => assert.equal(f.buys().length, 1));
  await waitFor(() => assert.equal(latest.pending, false));
  buy(); await waitFor(() => assert.equal(f.buys().length, 2));
  const [first, second] = f.buys().map((call) => call.idempotencyKey);
  assert.match(first ?? '', /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  assert.match(second ?? '', /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  assert.notEqual(first, second);
});

test('중복 구매 409는 성공으로 표시하지 않고 서버 조회로 확인한다', async () => {
  const f = mount({ buy: async () => fail(409, 'ITEM_DUPLICATE_PURCHASE') });
  await ready(); buy();
  await waitFor(() => assert.ok(latest.reconcile));
  assert.equal(latest.receipt, null);
  assert.doesNotMatch(latest.status ?? '', /구매 완료/);
  const before = f.calls.filter((call) => call.path.endsWith('/users/me')).length;
  await act(async () => latest.reconcile?.());
  await waitFor(() => assert.ok(f.calls.filter((call) => call.path.endsWith('/users/me')).length > before));
  assert.equal(f.buys().length, 1);
});

test('서버 가격·이름·기간만 표시하고 크레딧 상품은 비활성이며 null은 활성 효과 없음이다', async () => {
  const f = mount(); await ready();
  assert.ok(screen.getByText('지속 60초')); assert.ok(screen.getByText('활성 효과 없음'));
  assert.equal(screen.getByRole('button', { name: '서버 로고 구매 준비 중' }).hasAttribute('disabled'), true);
  assert.ok(screen.getByText('400 크레딧')); assert.ok(screen.getByText('결제 준비 중'));
  assertAbsent(screen.getByRole('button', { name: '서버 커피 구매 선택' }).querySelector('h1,h2,h3,h4,h5,h6,p,div,section,article'), '상품 버튼 내부는 span 등 phrasing content로 구성하세요.');
  await act(async () => latest.purchase(CREDIT.id)); assert.equal(f.buys().length, 0);
  const dialog = confirm(); assert.match(dialog.textContent ?? '', /비용 70점.*구매 후 10점/);
  assert.doesNotMatch(dialog.textContent ?? '', /120점|2배/); assert.equal(f.buys().length, 0);
  fireEvent(dialog, new dom.window.Event('close')); assertAbsent(screen.queryByRole('dialog'));
});

for (const state of ['empty', 'failure'] as const) {
  test(`목록 ${state}를 목업으로 대체하지 않고 실제 재조회를 제공한다`, async () => {
    let healthy = false;
    const f = mount({ catalog: async () => healthy ? ok([COFFEE, CREDIT]) : state === 'empty' ? ok([]) : fail(400, 'COMMON_INVALID_INPUT') });
    await waitFor(() => assert.match(latest.catalogNotice ?? '', state === 'empty' ? /판매 중인 아이템이 없/ : /불러오지 못/));
    assertAbsent(screen.queryByRole('button', { name: '커피 한 잔 구매 선택' }));
    await act(async () => latest.purchase(COFFEE.id)); assert.equal(f.buys().length, 0);
    healthy = true; fireEvent.click(screen.getByRole('button', { name: '아이템 목록 다시 조회' })); await ready();
  });
}

test('비회원은 목록만 보고 구매·명시적 효과 조회로도 인증 요청을 보내지 않는다', async () => {
  const f = mount({ signedIn: false }); await screen.findByRole('button', { name: '서버 커피 구매 선택' });
  const dialog = confirm(); assert.match(dialog.textContent ?? '', /로그인/);
  await act(async () => { latest.purchase(COFFEE.id); latest.reloadEffect(); });
  assert.equal(f.buys().length, 0); assert.equal(f.calls.filter((call) => call.path.endsWith('/active')).length, 0);
  assert.equal(latest.balance, null);
});

test('잔액 부족과 지원하지 않는 상품은 직접 호출해도 차감·POST가 없다', async () => {
  const f = mount({ profile: { ...ME, totalContribScore: 69 } }); await ready();
  assert.match(latest.disabledReason(COFFEE) ?? '', /기여도가 부족/);
  assert.match(latest.disabledReason({ ...COFFEE, type: 'LOGO_PROFILE' }) ?? '', /지원하지 않는/);
  await act(async () => latest.purchase(COFFEE.id)); assert.equal(f.buys().length, 0); assert.equal(game().contribution, 69);
});

test('같은 프레임 중복 구매·클릭·강화를 막고 성공 전 차감하지 않으며 화면 이동 뒤에도 결과를 반영한다', async () => {
  let finish!: (response: Response) => void;
  const f = mount({ buy: () => new Promise((resolve) => { finish = resolve; }) }); await ready();
  const before = game();
  await act(async () => { latest.purchase(COFFEE.id); latest.purchase(COFFEE.id); clicks.tap(false); clicker.api!.upgrade(); });
  assert.equal(f.buys().length, 1); assert.equal(f.calls.filter((call) => call.path.endsWith('/upgrade')).length, 0);
  assert.equal(game().contribution, 80); assert.equal(latest.pending, true);
  fireEvent.click(screen.getByRole('button', { name: '화면 전환' }));
  await act(async () => { f.setEffect(EFFECT); finish(ok({ userItemId: 991, item: { id: 801, name: '서버 커피', payType: 'CONTRIBUTION' }, price: 70, expiredAt: EFFECT.expiredAt, myContribScore: 10, myCreditBalance: 1200 }, 201)); });
  await waitFor(() => assert.equal(game().contribution, 10));
  assert.equal(game().schoolScore, before.schoolScore); assert.equal(game().departmentScore, before.departmentScore); assert.equal(game().clickerId, 11);
  fireEvent.click(screen.getByRole('button', { name: '화면 전환' }));
  await screen.findByText(/서버 커피 구매 완료/); await waitFor(() => assert.equal(latest.effect?.effect?.multiplier, 1.5));
});

test('미확정 클릭은 버리지 않으며 전송 확정까지 구매를 막는다', async () => {
  const f = mount(); await ready(); fireEvent.click(screen.getByRole('button', { name: '클릭' }));
  await act(async () => latest.purchase(COFFEE.id)); assert.equal(f.buys().length, 0); assert.equal(game().contribution, 87);
  await waitFor(() => assert.equal(latest.disabledReason(COFFEE), null), { timeout: 2500 });
  buy(); await waitFor(() => assert.equal(game().contribution, 17)); assert.equal(f.buys().length, 1);
});

test('강화가 먼저 잠금을 얻으면 구매가 뒤따르지 않는다', async () => {
  const f = mount(); await ready();
  await act(async () => { clicker.api!.upgrade(); latest.purchase(COFFEE.id); });
  await waitFor(() => assert.equal(game().contribution, 30)); assert.equal(f.buys().length, 0);
});

for (const code of ['ITEM_NOT_ENOUGH_SCORE', 'ITEM_NOT_FOUND']) {
  test(`${code}: 실패는 차감·효과 적용 없이 안내하고 POST는 자동 재시도하지 않는다`, async () => {
    const f = mount({ buy: async () => fail(code === 'ITEM_NOT_FOUND' ? 404 : 400, code) }); await ready(); buy();
    await waitFor(() => assert.ok(latest.error)); assert.equal(game().contribution, 80); assert.equal(latest.effect?.effect, null);
    assert.equal(latest.pending, false); assert.equal(latest.reconcile, null); assert.equal(f.buys().length, 1);
    buy(); await waitFor(() => assert.equal(f.buys().length, 2));
  });
}

test('구매 201 뒤 효과 조회 실패는 성공을 유지하고 GET만 재시도한다', async () => {
  let readFails = false;
  const f = mount({ activeRead: async () => readFails ? fail(400, 'COMMON_INVALID_INPUT') : ok(null), buy: async () => {
    readFails = true; return ok({ userItemId: 991, item: { id: 801, name: '서버 커피', payType: 'CONTRIBUTION' }, price: 70, expiredAt: EFFECT.expiredAt, myContribScore: 10, myCreditBalance: 1200 }, 201);
  } }); await ready();
  const dialog = confirm(); fireEvent.submit(within(dialog).getByRole('button', { name: '70점 구매 및 사용' }).closest('form')!);
  await waitFor(() => assert.match(latest.effectNotice ?? '', /확인하지 못/));
  assert.equal(game().contribution, 10); assert.equal(latest.receipt?.userItemId, 991); assert.equal(latest.reconcile, null);
  assertAbsent(screen.queryByRole('dialog')); assert.equal(latest.pending, false);
  readFails = false; fireEvent.click(screen.getByRole('button', { name: '적용 효과 다시 조회' })); await ready();
  assert.equal(f.buys().length, 1); assert.ok(screen.getByText('활성 효과 없음'), '적용되지 않은 서버 효과를 구매 응답만으로 만들지 않는다');
});

for (const failure of ['network', '500', 'invalid-response'] as const) {
  test(`${failure}: 불명확한 구매는 이전 효과/null 조회만으로 해제하거나 재전송하지 않는다`, async () => {
    const f = mount({ effect: EFFECT, buy: async () => {
      if (failure === 'network') throw new TypeError('lost response');
      if (failure === 'invalid-response') return new Response('broken', { status: 201 });
      return fail(500, 'COMMON_INTERNAL_ERROR');
    } }); await ready(); buy(); await waitFor(() => assert.ok(latest.reconcile));
    await act(async () => latest.reconcile!());
    await waitFor(() => assert.match(latest.error ?? '', /새 적용 효과가 확인되지/));
    await act(async () => { latest.purchase(COFFEE.id); clicks.tap(false); clicker.api!.upgrade(); });
    assert.equal(f.buys().length, 1); assert.equal(game().contribution, 80);
    f.setProfile({ ...ME, totalContribScore: 10 }); f.setEffect({ ...EFFECT, expiredAt: '2030-01-01T12:01:00+09:00' });
    await act(async () => latest.reconcile!());
    await waitFor(() => assert.equal(latest.reconcile, null)); assert.equal(game().contribution, 10); assert.equal(f.buys().length, 1);
    fireEvent.click(screen.getByRole('button', { name: '클릭' })); assert.equal(game().contribution, 17);
  });
}

test('효과 조회 실패 중에는 교체 기준이 없으므로 새 구매를 시작하지 않는다', async () => {
  const f = mount({ activeRead: async () => fail(400, 'COMMON_INVALID_INPUT') });
  await waitFor(() => assert.match(latest.effectNotice ?? '', /확인하지 못/));
  await act(async () => latest.purchase(COFFEE.id)); assert.equal(f.buys().length, 0);
});

test('구매 후 배율을 클라이언트에서 중복 곱하지 않고 배치 전체 floor 결과로 보정한다', async () => {
  const f = mount(); await ready(); buy(); await waitFor(() => assert.equal(latest.effect?.effect?.multiplier, 1.5));
  fireEvent.click(screen.getByRole('button', { name: '클릭' })); fireEvent.click(screen.getByRole('button', { name: '클릭' }));
  assert.equal(game().contribution, 24, '낙관 값은 실제 기본 세기 7점만 2회 더한다');
  await waitFor(() => assert.equal(game().contribution, 31), { timeout: 2500 });
  assert.equal(f.buys().length, 1); assert.equal(game().schoolScore, 121); assert.equal(game().departmentScore, 41);
});

test('같은 프레임 로그아웃·다른 계정 로그인 뒤 늦은 구매 성공과 이전 효과를 적용하지 않는다', async () => {
  let finish!: (response: Response) => void;
  const f = mount({ effect: EFFECT, buy: () => new Promise((resolve) => { finish = resolve; }) }); await ready(); buy();
  await waitFor(() => assert.equal(f.buys().length, 1));
  await act(async () => {
    f.client.session.signOut(); f.client.session.signIn('member-b'); f.setEffect(null);
    f.setProfile({ ...ME, id: 11, totalContribScore: 500 }); f.queryClient.setQueryData(userKeys.me, { ...ME, id: 11, totalContribScore: 500 });
  });
  assert.equal(f.buys()[0].signal?.aborted, true);
  await act(async () => finish(ok({ userItemId: 991, item: { id: 801, name: '서버 커피', payType: 'CONTRIBUTION' }, price: 70, expiredAt: EFFECT.expiredAt, myContribScore: 10, myCreditBalance: 1200 }, 201)));
  await waitFor(() => assert.equal(latest.effect?.effect, null)); assert.equal(game().contribution, 500); assert.equal(latest.receipt, null);
  fireEvent.click(screen.getByRole('button', { name: '클릭' })); assert.equal(game().contribution, 507);
  assert.equal(f.queryClient.getQueryData(itemKeys.active(10, 0)), undefined);
});

test('구매 전에 시작한 오래된 내 정보 조회는 늦게 끝나도 구매 후 기여도를 덮지 않는다', async () => {
  let finishRead!: (response: Response) => void;
  const f = mount({ readMe: () => new Promise((resolve) => { finishRead = resolve; }) }); await ready();
  let pending!: Promise<void>;
  await act(async () => { pending = f.queryClient.refetchQueries({ queryKey: userKeys.me }); await Promise.resolve(); });
  buy(); await waitFor(() => assert.equal(game().contribution, 10));
  await act(async () => { finishRead(ok(ME)); await pending; }); assert.equal(game().contribution, 10);
});

test('남은 시간은 서버 초와 단조 시간 경과를 사용해 백그라운드 지연을 따라잡고 0 아래로 내려가지 않는다', () => {
  const snapshot: EffectSnapshot = { effect: EFFECT, receivedAt: 1000 };
  assert.equal(effectSecondsLeft(snapshot, 1000), 60); assert.equal(effectSecondsLeft(snapshot, 1999), 60);
  assert.equal(effectSecondsLeft(snapshot, 2000), 59); assert.equal(effectSecondsLeft(snapshot, 60_999), 1);
  assert.equal(effectSecondsLeft(snapshot, 61_000), 0); assert.equal(effectSecondsLeft(snapshot, 900_000), 0);
  assert.equal(effectSecondsLeft(snapshot, 0), 60); assert.equal(effectSecondsLeft({ effect: null, receivedAt: 0 }, 1000), 0);
  assert.equal(effectSecondsLeft(undefined, 1000), 0);
});

test('효과 교체·이름 없음·서버 만료 null은 목업과 중첩하지 않고 새 조회 값만 표시한다', async () => {
  let effect: ActiveItemEffect | null = { ...EFFECT, name: null };
  mount({ activeRead: async () => ok(effect) }); await ready();
  assert.ok(screen.getByText(/아이템 효과 · 서버 배율 ×1.5/));
  effect = { ...EFFECT, name: '새 물약', multiplier: 4, remainingSeconds: 120, expiredAt: '2030-01-02T12:00:00+09:00' };
  await act(async () => latest.reloadEffect()); await waitFor(() => assert.equal(latest.effect?.effect?.multiplier, 4));
  assert.ok(screen.getByText(/새 물약 · 서버 배율 ×4/)); assertAbsent(screen.queryByText(/서버 배율 ×1.5/));
  assert.equal(screen.getByRole('timer').getAttribute('aria-live'), 'off');
  effect = null; await act(async () => latest.reloadEffect()); await waitFor(() => assert.equal(latest.effect?.effect, null));
  assert.ok(screen.getByText('활성 효과 없음')); assertAbsent(screen.queryByRole('timer'));
});

test('가격 0도 기여도 상품으로 정상 구매하며 클라이언트 가격을 전송하지 않는다', async () => {
  const f = mount({ profile: { ...ME, totalContribScore: 0 }, catalog: async () => ok([{ ...COFFEE, price: 0 }]),
    buy: async () => { f.setEffect(EFFECT); return ok({ userItemId: 991, item: { id: 801, name: '서버 커피', payType: 'CONTRIBUTION' }, price: 0, expiredAt: EFFECT.expiredAt, myContribScore: 0, myCreditBalance: 0 }, 201); } });
  await ready(); assert.equal(latest.disabledReason(latest.items[0]), null); buy();
  await waitFor(() => assert.equal(latest.receipt?.price, 0)); assert.equal(game().contribution, 0); assert.equal(f.buys()[0].body, undefined);
});

test('POST 진행 중 시작한 이전 효과 GET도 성공 뒤 취소되어 새 효과를 덮지 못한다', async () => {
  let finishBuy!: (response: Response) => void;
  let finishRead!: (response: Response) => void;
  let hold = false;
  let effect: ActiveItemEffect | null = null;
  const f = mount({ buy: () => new Promise((resolve) => { finishBuy = resolve; }),
    activeRead: () => hold ? new Promise((resolve) => { finishRead = resolve; }) : Promise.resolve(ok(effect)) });
  await ready(); buy(); await waitFor(() => assert.equal(f.buys().length, 1));
  hold = true; await act(async () => latest.reloadEffect());
  const oldRead = f.calls.filter((call) => call.path.endsWith('/active')).at(-1)!;
  hold = false; effect = EFFECT;
  await act(async () => finishBuy(ok({ userItemId: 991, item: { id: 801, name: '서버 커피', payType: 'CONTRIBUTION' }, price: 70, expiredAt: EFFECT.expiredAt, myContribScore: 10, myCreditBalance: 1200 }, 201)));
  await waitFor(() => assert.equal(latest.effect?.effect?.multiplier, 1.5)); assert.equal(oldRead.signal?.aborted, true);
  await act(async () => finishRead(ok(null))); assert.equal(latest.effect?.effect?.multiplier, 1.5); assert.equal(game().contribution, 10);
});

test('결과 재조회 실패 뒤에도 잠금을 유지하고 명시적 GET 재시도만 허용한다', async () => {
  let healthy = false;
  const f = mount({ buy: async () => { throw new TypeError('lost response'); },
    readMe: async () => healthy ? ok({ ...ME, totalContribScore: 10 }) : fail(400, 'COMMON_INVALID_INPUT') });
  await ready(); buy(); await waitFor(() => assert.ok(latest.reconcile)); f.setEffect(EFFECT);
  await act(async () => latest.reconcile!()); await waitFor(() => assert.match(latest.error ?? '', /구매 결과를 확인하지 못/));
  await act(async () => { clicks.tap(false); latest.purchase(COFFEE.id); }); assert.equal(f.buys().length, 1); assert.equal(game().contribution, 80);
  healthy = true; await act(async () => latest.reconcile!()); await waitFor(() => assert.equal(latest.reconcile, null));
  assert.equal(game().contribution, 10); assert.equal(f.buys().length, 1);
});

test('같은 사용자라도 같은 프레임 재로그인한 세션에 이전 구매·효과 캐시를 재사용하지 않는다', async () => {
  let finish!: (response: Response) => void;
  const f = mount({ effect: EFFECT, buy: () => new Promise((resolve) => { finish = resolve; }) }); await ready(); buy();
  await waitFor(() => assert.equal(f.buys().length, 1));
  await act(async () => {
    f.client.session.signOut(); f.client.session.signIn('member-new'); f.setEffect(null);
    f.setProfile({ ...ME, totalContribScore: 500 }); f.queryClient.setQueryData(userKeys.me, { ...ME, totalContribScore: 500 });
  });
  await waitFor(() => assert.equal(latest.effect?.effect, null)); assert.equal(f.buys()[0].signal?.aborted, true);
  await act(async () => finish(ok({ userItemId: 991, item: { id: 801, name: '서버 커피', payType: 'CONTRIBUTION' }, price: 70, expiredAt: EFFECT.expiredAt, myContribScore: 10, myCreditBalance: 1200 }, 201)));
  assert.equal(game().contribution, 500); assert.equal(latest.receipt, null); assert.equal(f.queryClient.getQueryData(itemKeys.active(10, 0)), undefined);
  fireEvent.click(screen.getByRole('button', { name: '클릭' })); assert.equal(game().contribution, 507);
});

test('확인 창도 갱신된 서버 가격을 표시하며 목록에서 빠진 상품의 선택을 보존하지 않는다', async () => {
  const f = mount(); await ready(); confirm();
  await act(async () => f.queryClient.setQueryData(itemKeys.catalog, [{ ...COFFEE, price: 75 }, CREDIT]));
  await within(screen.getByRole('dialog')).findByRole('button', { name: '75점 구매 및 사용' });
  await act(async () => f.queryClient.setQueryData(itemKeys.catalog, [CREDIT])); await waitFor(() => assertAbsent(screen.queryByRole('dialog')));
  await act(async () => f.queryClient.setQueryData(itemKeys.catalog, [COFFEE, CREDIT])); await waitFor(() => assert.ok(screen.getByRole('button', { name: '서버 커피 구매 선택' })));
  assertAbsent(screen.queryByRole('dialog'));
  assert.equal(f.buys().length, 0);
});

test('Java 계약 센서: 목록·구매·활성 효과 DTO와 enum·201·null 계약이 바뀌면 어댑터를 재검토한다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/item/', import.meta.url);
  const fields = (file: string) => readFileSync(new URL(`dto/${file}.java`, root), 'utf8').match(/record \w+\(([^)]*)\)/)?.[1]
    .replace(/\/\/[^\n]*/g, '').split(',').map((part) => part.trim().split(/\s+/).at(-1));
  const fix = 'api/items.ts 및 useItemShop의 구매·null·효과 시간·크레딧 정책을 Java 계약과 맞추세요';
  assert.deepEqual(fields('ItemResponse'), Object.keys(COFFEE), fix);
  assert.deepEqual(fields('ActiveEffectResponse'), Object.keys(EFFECT), fix);
  assert.deepEqual(fields('ItemPurchaseResponse'), ['userItemId', 'item', 'price', 'expiredAt', 'myContribScore', 'myCreditBalance'], fix);
  const controller = readFileSync(new URL('controller/ItemController.java', root), 'utf8');
  assert.match(controller, /@ResponseStatus\(HttpStatus.CREATED\)/, fix); assert.match(controller, /@PostMapping\("\/\{itemId\}\/purchase"\)/, fix);
  assert.match(controller, /@GetMapping\("\/me\/active"\)/, fix); assert.match(controller, /\.orElse\(null\)/, fix);
  assert.match(readFileSync(new URL('entity/PayType.java', root), 'utf8'), /CONTRIBUTION[\s\S]*CREDIT/, fix);
  assert.match(readFileSync(new URL('entity/ItemType.java', root), 'utf8'), /MULTIPLIER[\s\S]*LOGO_UNIVERSITY[\s\S]*LOGO_DEPARTMENT[\s\S]*LOGO_PROFILE/, fix);
});
