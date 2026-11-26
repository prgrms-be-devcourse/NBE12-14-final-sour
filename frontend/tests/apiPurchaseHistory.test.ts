import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { readJava } from './helpers/javaSource';
import type { ApiFaults } from '../src/api/faults';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import type { MyInfo } from '../src/api/users';
import type { PurchaseHistoryItem, PurchaseHistoryResponse, ActiveItemEffect } from '../src/api/items';
import { assertAbsent } from './helpers/domAssert';

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
const { createApiFaults } = await import('../src/api/faults');
const { getPurchaseHistory, itemKeys } = await import('../src/api/items');
const { userKeys } = await import('../src/api/users');
const { ApiSessionWatcher } = await import('../src/components/onboarding/ApiLoginPhone');
const { PurchaseHistoryScreenPhone } = await import('../src/components/PurchaseHistoryScreenPhone');
const { MyInfoScreenPhone } = await import('../src/components/MyInfoScreenPhone');
const { useApiItemShopController } = await import('../src/hooks/useItemShop');
const clients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => { cleanup(); for (const client of clients.splice(0)) client.clear(); });
after(() => { assets.deregister(); dom.window.close(); });

const ME: MyInfo = { id: 30, nickname: '내역학생', email: 'test@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: 1, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 }, totalContribScore: 100, totalAttackScore: 0 };
const ROW: PurchaseHistoryItem = { userItemId: 991, item: { id: 801, name: '기록 커피', payType: 'CONTRIBUTION' },
  price: 70, createdAt: '2030-01-01T23:59:00Z', expiredAt: '2030-01-02T01:00:00Z' };
const pageOf = (items: PurchaseHistoryItem[], page = 0, hasNext = false): PurchaseHistoryResponse => ({ items, page, size: 20, totalCount: hasNext ? 21 : items.length, hasNext });
const ok = (data: unknown, status = 200) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status });
const fail = (status = 400, code = 'COMMON_INVALID_INPUT') => new Response(JSON.stringify({ code, message: '내역 조회 실패', data: null }), { status });
const rowText = () => screen.queryAllByTestId('purchase-history-row').map((row) => row.textContent ?? '');
let shop: ReturnType<typeof useApiItemShopController>;
const gate = { upgradeBlocked: false, beginUpgrade: () => () => undefined };
function Controller() { shop = useApiItemShopController(gate); return null; }
interface Call { path: string; auth: string | null; signal?: AbortSignal | null; method?: string }
function mount(options: { signedIn?: boolean; history?: (page: number, call: Call) => Promise<Response>;
  buy?: () => Promise<Response>; profileScreen?: boolean; strict?: boolean; faults?: ApiFaults } = {}) {
  let profile = ME;
  let rows = [ROW];
  let effect: ActiveItemEffect | null = null;
  const calls: Call[] = [], navigation: string[] = [];
  const client = createApiClient({ faults: options.faults, fetch: (async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const call = { path: url.pathname + url.search, auth: new Headers(init?.headers).get('Authorization'), signal: init?.signal, method: init?.method };
    calls.push(call);
    if (url.pathname.endsWith('/history')) return options.history ? options.history(Number(url.searchParams.get('page')), call) : ok(pageOf(rows));
    if (url.pathname.endsWith('/users/me')) return ok(profile);
    if (url.pathname.endsWith('/auth/reissue')) return options.signedIn === false ? fail(401, 'AUTH_INVALID_REFRESH_TOKEN') : ok({ accessToken: 'member-a', expiresInSeconds: 3600 });
    if (url.pathname.endsWith('/items')) return ok([{ id: 801, name: '기록 커피', content: '커피', type: 'MULTIPLIER', payType: 'CONTRIBUTION', price: 70, durationSeconds: 60 }]);
    if (url.pathname.endsWith('/items/me/active')) return ok(effect);
    if (url.pathname.endsWith('/purchase')) return options.buy ? options.buy() : ok({ userItemId: 992, item: ROW.item, price: 70, expiredAt: ROW.expiredAt, myContribScore: 30, myCreditBalance: 0 }, 201);
    throw new Error(`Unexpected path: ${url.pathname}`);
  }) as typeof fetch });
  const queryClient = createQueryClient(); clients.push(queryClient);
  if (options.signedIn !== false) { client.session.signIn('member-a'); queryClient.setQueryData(userKeys.me, profile); }
  const ui = options.profileScreen ? React.createElement(MyInfoScreenPhone, { onBack: () => navigation.push('back'), onGoToLogin: () => navigation.push('login'), onOpenPurchaseHistory: () => navigation.push('history') })
    : React.createElement(PurchaseHistoryScreenPhone, { onBack: () => navigation.push('back'), onGoToLogin: () => navigation.push('login') });
  render(React.createElement(options.strict ? React.StrictMode : React.Fragment, null,
    React.createElement(ApiProvider, { client, queryClient, children: React.createElement(React.Fragment, null,
      React.createElement(ApiSessionWatcher, { onEnded: () => navigation.push('ended') }),
      React.createElement(Controller), ui,
    ) })));

  return { client, queryClient, calls, navigation, historyCalls: () => calls.filter((call) => call.path.includes('/history')),
    setRows: (next: PurchaseHistoryItem[]) => { rows = next; },
    setProfile: (next: MyInfo) => { profile = next; }, setEffect: (next: ActiveItemEffect | null) => { effect = next; } };
}
const ready = async () => waitFor(() => { assert.equal(shop.catalogNotice, null); assert.equal(shop.effectNotice, null); });
const next = () => fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));

test('GET 페이지 계약·인증·기본 크기 20·AbortSignal을 공통 클라이언트로 보낸다', async () => {
  let called: { url: string; init?: RequestInit } | undefined;
  const client = createApiClient({ fetch: (async (input, init) => { called = { url: String(input), init }; return ok(pageOf([ROW], 2)); }) as typeof fetch });
  client.session.signIn('member'); const abort = new AbortController();
  assert.deepEqual(await getPurchaseHistory(client, 2, abort.signal), pageOf([ROW], 2));
  assert.equal(called?.url, '/api/v1/items/me/history?page=2&size=20');
  assert.equal(called?.init?.method, 'GET'); assert.equal(called?.init?.body, undefined);
  assert.equal(called?.init?.signal, abort.signal); assert.equal(new Headers(called?.init?.headers).get('Authorization'), 'Bearer member');
});

test('서버 순서·실결제 금액·결제 단위·KST 시각·nullable 상품/만료일·0원을 표시한다', async () => {
  const unknown = { ...ROW, userItemId: 993, item: null, price: 3900, expiredAt: null };
  const credit = { ...ROW, userItemId: 992, item: { id: 802, name: '로고 변경권', payType: 'CREDIT' as const }, price: 0, expiredAt: null };
  mount({ strict: true, history: async () => ok(pageOf([unknown, credit, ROW])) });
  await waitFor(() => assert.equal(rowText().length, 3));
  assert.match(rowText()[0], /상품 정보 없음.*3,900.*결제 수단 확인 불가.*만료일 없음/);
  assert.match(rowText()[1], /로고 변경권.*0 크레딧/);
  assert.match(rowText()[2], /기록 커피.*70 점 \(기여도\).*2030.*1.*2.*오전 8:59/);
  assert.equal(screen.getAllByRole('listitem').length, 3);
  assert.equal(document.querySelector('time')?.getAttribute('datetime'), ROW.createdAt);
});

test('처음에는 로딩, 빈 응답은 구매 없음이며 첫/마지막 페이지 경계를 막는다', async () => {
  let finish!: (value: Response) => void;
  mount({ history: () => new Promise((resolve) => { finish = resolve; }) });
  await screen.findByText('구매 내역을 불러오는 중…'); assert.equal(screen.getByRole('button', { name: '다음 페이지' }).hasAttribute('disabled'), true);
  await act(async () => finish(ok(pageOf([]))));
  await screen.findByText('아직 구매한 아이템이 없습니다.');
  for (const name of ['이전 페이지', '다음 페이지']) assert.equal(screen.getByRole('button', { name }).hasAttribute('disabled'), true);
  assertAbsent(screen.queryByRole('list'));
});

test('페이지 이동은 0 기반 GET이고 요청 중 이중 이동을 막으며 최신 내역은 0페이지로 돌아간다', async () => {
  let finish!: (value: Response) => void;
  let hold = true;
  const f = mount({ history: async (page) => page === 0 ? ok(pageOf([ROW], 0, true))
    : hold ? new Promise((resolve) => { finish = resolve; }) : ok(pageOf([], 1)) });
  await screen.findByText('기록 커피'); next();
  await screen.findByText('구매 내역을 불러오는 중…');
  assertAbsent(screen.queryByText('기록 커피'), '이전 페이지를 새 페이지인 것처럼 보여주지 않는다'); next();
  assert.equal(f.historyCalls().filter((call) => call.path.includes('page=1')).length, 1);
  await act(async () => finish(ok(pageOf([{ ...ROW, item: null }], 1))));
  await screen.findByText('상품 정보 없음');
  assert.equal(screen.getByRole('button', { name: '다음 페이지' }).hasAttribute('disabled'), true);
  fireEvent.click(screen.getByRole('button', { name: '최신 내역' })); await screen.findByText('기록 커피');
  assert.match(screen.getByRole('status').textContent ?? '', /1페이지/);
  assert.equal(f.historyCalls().filter((call) => call.path.includes('page=0')).length, 2);
  hold = false;
  await waitFor(() => assert.equal(screen.getByRole('button', { name: '다음 페이지' }).hasAttribute('disabled'), false));
  next(); await screen.findByText(/이 페이지에 구매 내역이 없습니다/);
  fireEvent.click(screen.getByRole('button', { name: '이전 페이지' })); await screen.findByText('기록 커피');
});

test('조회 실패는 재조회 GET으로 회복하고 구매 POST나 목업 대체가 없다', async () => {
  let healthy = false;
  const f = mount({ history: async () => healthy ? ok(pageOf([ROW])) : fail() });
  await screen.findByRole('alert'); assertAbsent(screen.queryByRole('list')); healthy = true;
  fireEvent.click(screen.getByRole('button', { name: '구매 내역 다시 조회' })); await screen.findByText('기록 커피');
  assert.equal(f.historyCalls().length, 2); assert.equal(f.calls.filter((call) => call.method === 'POST' && call.path.endsWith('/purchase')).length, 0);
});

test('비회원은 내역 조회 없이 로그인 안내, 뒤로가기로 내 정보에 복귀한다', async () => {
  const f = mount({ signedIn: false }); await screen.findByText('구매 내역을 보려면 로그인해 주세요.');
  fireEvent.click(screen.getByRole('button', { name: '로그인' })); fireEvent.click(screen.getByRole('button', { name: '내 정보로 돌아가기' }));
  assert.deepEqual(f.navigation, ['login', 'back']); assert.equal(f.historyCalls().length, 0);
});

test('내 정보에 실제 구매 내역 진입 버튼을 노출한다', async () => {
  const f = mount({ profileScreen: true }); fireEvent.click(await screen.findByRole('button', { name: '아이템 구매 내역' }));
  assert.deepEqual(f.navigation, ['history']); assert.equal(f.historyCalls().length, 0, '화면 진입 전에는 구매 내역을 미리 요청하지 않는다');
});

test('확정 구매는 모든 페이지를 무효화하고 최신 내역을 서버에서 받으며 GET 실패여도 성공과 잔액을 유지한다', async () => {
  let reject = false;
  const f = mount({ history: async () => reject ? fail() : ok(pageOf([ROW])) }); await ready(); await screen.findByText('기록 커피');
  f.queryClient.setQueryData(itemKeys.historyPage(ME.id, 1), pageOf([], 1));
  reject = true; await act(async () => shop.purchase(801));
  await screen.findByRole('alert');
  assert.equal(shop.receipt?.userItemId, 992); assert.equal(shop.balance, 30); assert.equal(shop.reconcile, null);
  assert.equal(f.queryClient.getQueryState(itemKeys.historyPage(ME.id, 1))?.isInvalidated, true);
  reject = false;
  // Retrying the failed GET must not resend the confirmed purchase.
  fireEvent.click(screen.getByRole('button', { name: '구매 내역 다시 조회' })); await screen.findByText('기록 커피');
  assert.equal(f.calls.filter((call) => call.path.endsWith('/purchase')).length, 1);
});

test('성공 구매 후 열린 내역에 새 구매가 맨 앞에 나타나며 수동 새로고침도 새 0페이지를 조회한다', async () => {
  const f = mount(); await ready(); await screen.findByText('기록 커피');
  const fresh = { ...ROW, userItemId: 992, item: { ...ROW.item!, name: '새 구매' } };
  f.setRows([fresh, ROW]); await act(async () => shop.purchase(801)); await screen.findByText('새 구매');
  assert.match(rowText()[0], /새 구매/); assert.equal(rowText().length, 2);
  f.setRows([fresh]); fireEvent.click(screen.getByRole('button', { name: '최신 내역' }));
  await waitFor(() => assert.equal(rowText().length, 1));
});

test('불명확 구매는 서버 확인 성공 후에만 내역을 갱신하고 POST를 다시 보내지 않는다', async () => {
  const f = mount({ buy: async () => { throw new TypeError('response lost'); } }); await ready(); await screen.findByText('기록 커피');
  await act(async () => shop.purchase(801)); await waitFor(() => assert.ok(shop.reconcile));
  const before = f.historyCalls().length;
  await act(async () => shop.reconcile!()); await waitFor(() => assert.ok(shop.reconcile)); assert.equal(f.historyCalls().length, before);
  f.setEffect({ itemId: 801, name: '기록 커피', multiplier: 1.5, remainingSeconds: 60, expiredAt: ROW.expiredAt! });
  f.setProfile({ ...ME, totalContribScore: 30 }); f.setRows([{ ...ROW, item: { ...ROW.item!, name: '서버 확인 구매' } }]);
  await act(async () => shop.reconcile!()); await screen.findByText('서버 확인 구매');
  assert.equal(f.calls.filter((call) => call.path.endsWith('/purchase')).length, 1);
});

test('동일 프레임 로그아웃·다른 계정 로그인은 이전 회원 페이지 요청을 취소하고 늦은 응답을 표시하지 않는다', async () => {
  let finish!: (value: Response) => void;
  const f = mount({ history: async (_page, call) => call.auth === 'Bearer member-a'
    ? new Promise((resolve) => { finish = resolve; }) : ok(pageOf([{ ...ROW, item: { ...ROW.item!, name: '다른 회원 구매' } }])) });
  await waitFor(() => assert.equal(f.historyCalls().length, 1));
  const oldCall = f.historyCalls()[0];
  await act(async () => {
    f.client.session.signOut(); f.client.session.signIn('member-b');
    const next = { ...ME, id: 31 }; f.setProfile(next); f.queryClient.setQueryData(userKeys.me, next);
  });
  assert.equal(oldCall.signal?.aborted, true); await screen.findByText('다른 회원 구매');
  await act(async () => finish(ok(pageOf([ROW]))));
  assertAbsent(screen.queryByText('기록 커피')); assert.equal(f.queryClient.getQueryData(itemKeys.historyPage(ME.id, 0)), undefined);
});

test('HTTP 전 지연 중 세션이 끝나면 이전 내역 GET이 새 계정 토큰으로 전송되지 않는다', async () => {
  let signal: AbortSignal | undefined, release!: () => void;
  const faults = createApiFaults(undefined, async (_ms, nextSignal) => {
    signal = nextSignal;
    await new Promise<void>((resolve, reject) => {
      release = resolve;
      nextSignal?.addEventListener('abort', () => reject(nextSignal.reason), { once: true });
    });
  });
  const f = mount({ faults }); await ready(); await screen.findByText('기록 커피');
  faults.setMode('delay'); fireEvent.click(screen.getByRole('button', { name: '최신 내역' }));
  await waitFor(() => assert.ok(signal));
  assert.equal(f.historyCalls().length, 1, '지연 중에는 아직 HTTP를 보내지 않는다');
  faults.setMode('normal');
  await act(async () => {
    f.client.session.signOut(); f.client.session.signIn('member-b');
    const next = { ...ME, id: 31 }; f.setProfile(next); f.queryClient.setQueryData(userKeys.me, next);
    f.setRows([{ ...ROW, item: { ...ROW.item!, name: '새 계정 내역' } }]);
  });
  assert.equal(signal?.aborted, true); await screen.findByText('새 계정 내역');
  await act(async () => release());
  assert.equal(f.historyCalls().length, 2);
  assert.deepEqual(f.historyCalls().map((call) => call.auth), ['Bearer member-a', 'Bearer member-b']);
});

test('계약 센서: 내역 DTO·페이지 범위·서버 최신순과 API 전용 진입 경로를 유지한다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/', import.meta.url);
  const dto = readJava(new URL('item/dto/PurchaseHistoryResponse.java', root));
  for (const name of ['items', 'page', 'size', 'totalCount', 'hasNext', 'userItemId', 'item', 'price', 'createdAt', 'expiredAt']) assert.match(dto, new RegExp(`\\b${name}\\b`));
  const controller = readJava(new URL('item/controller/ItemController.java', root));
  assert.match(controller, /@GetMapping\("\/me\/history"\)/);
  assert.match(controller, /@RequestParam\(defaultValue = "20"\)\s+int size/);
  assert.match(controller, /implements ItemApiDocs/);
  assert.match(readJava(new URL('item/controller/docs/ItemApiDocs.java', root)), /getItemHistory\(AuthUser user,\s*@Min\(0\) int page,\s*@Min\(1\) @Max\(100\) int size\)/);
  const service = readJava(new URL('item/service/PurchaseHistoryService.java', root));
  assert.match(service, /findAllByUserIdOrderByCreatedAtDescIdDesc\(userId, PageRequest.of\(page, size\)\)/);
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(app, /onOpenPurchaseHistory=\{\(\) => setViewMode\('purchase_history'\)\}/);
});

test('캐시 없는 최초 내역 GET 중 구매 성공도 이전 요청을 취소하고 새 내역을 다시 가져온다', async () => {
  let finish!: (value: Response) => void;
  let initial = true;
  const f = mount({ history: async () => initial ? new Promise((resolve) => { finish = resolve; })
    : ok(pageOf([{ ...ROW, item: { ...ROW.item!, name: '최초 조회 중 새 구매' } }])) });
  await ready(); await waitFor(() => assert.equal(f.historyCalls().length, 1));
  const old = f.historyCalls()[0]; initial = false;
  await act(async () => shop.purchase(801));
  try {
    await waitFor(() => assert.equal(f.historyCalls().length, 2));
    assert.equal(old.signal?.aborted, true); await screen.findByText('최초 조회 중 새 구매');
    await act(async () => finish(ok(pageOf([ROW]))));
    assertAbsent(screen.queryByText('기록 커피'));
    assert.equal(shop.receipt?.userItemId, 992);
  } finally {
    // A regression failure must not leave an unresolved fake response and keep Node alive.
    await act(async () => finish(ok(pageOf([ROW]))));
  }
});

test('구매 전에 시작한 오래된 내역 GET은 구매 성공 무효화가 취소하여 새 내역을 덮지 못한다', async () => {
  let hold = false, finish!: (value: Response) => void;
  const f = mount({ history: async () => hold ? new Promise((resolve) => { finish = resolve; }) : ok(pageOf([{ ...ROW, item: { ...ROW.item!, name: '확정 내역' } }])) });
  await ready(); await screen.findByText('확정 내역'); hold = true;
  fireEvent.click(screen.getByRole('button', { name: '최신 내역' }));
  await waitFor(() => assert.equal(f.historyCalls().length, 2)); const old = f.historyCalls()[1];
  hold = false; await act(async () => shop.purchase(801)); await waitFor(() => assert.equal(f.historyCalls().length, 3));
  assert.equal(old.signal?.aborted, true);
  await act(async () => finish(ok(pageOf([ROW])))); assertAbsent(screen.queryByText('기록 커피')); assert.ok(screen.getByText('확정 내역'));
});
