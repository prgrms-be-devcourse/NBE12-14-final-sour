// The server identifies our current alliance. Lookup, sent application numbers, and match numbers are separate workflows.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, afterEach, test, type TestContext } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { installDialogStub } from './helpers/dialogStub';

const assets = registerHooks({ load(url, context, nextLoad) {
  if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) return { format: 'module', source: 'export default "/asset";', shortCircuit: true };
  return nextLoad(url, context);
} });
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node,
  requestAnimationFrame: dom.window.requestAnimationFrame.bind(dom.window),
  cancelAnimationFrame: dom.window.cancelAnimationFrame.bind(dom.window),
  IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });

installDialogStub(dom.window);

const { render, screen, fireEvent, cleanup, waitFor, within, act } = await import('@testing-library/react');
const { timeoutManager } = await import('@tanstack/react-query');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { createMatchMemory, createSentApplicationMemory, getMyAlliance, parseAllianceId, parseApplicationId, parseMatchId,
  validateAllianceName } = await import('../src/api/alliances');
const { ApiAllianceScreenPhone, remainingTime, timeLeft } = await import('../src/components/ApiAllianceScreenPhone');
const { ApiSessionWatcher } = await import('../src/components/onboarding/ApiLoginPhone');
const { SCHOOL_ID } = await import('../src/data/schoolIds');
const { assertAbsent, assertSameNode } = await import('./helpers/domAssert');
type MyInfo = import('../src/api/users').MyInfo;
type Alliance = import('../src/api/alliances').Alliance;
type AllianceApplication = import('../src/api/alliances').AllianceApplication;
type AllianceMatch = import('../src/api/alliances').AllianceMatch;

const queryClients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(async () => {
  // A fetch still in flight schedules its query's 5-minute gc timer when it settles, even after clear(); let every fetch
  // settle (including its finally step, one macrotask later) before clearing.
  await act(async () => {
    for (const client of queryClients) {
      do await new Promise((resolve) => setTimeout(resolve, 0)); while (client.isFetching() > 0);
    }
  });
  cleanup();
  for (const client of queryClients.splice(0)) {
    // MutationCache.clear() keeps each mutation's 5-minute gc timer alive, so node would not exit; destroy() cancels it.
    for (const mutation of client.getMutationCache().getAll()) mutation.destroy();
    client.clear();
  }
});
after(() => { assets.deregister(); dom.window.close(); });

function mockQueryPolling(t: TestContext) {
  // Mock only query intervals: replacing global interval functions can strand JSDOM's native rendering timer.
  let nextId = 0;
  const intervals = new Map<number, { callback: () => void; delay: number; remaining: number }>();
  t.mock.method(timeoutManager, 'setInterval', (callback: () => void, delay: number) => {
    const id = ++nextId;
    intervals.set(id, { callback, delay, remaining: delay });
    return id;
  });
  t.mock.method(timeoutManager, 'clearInterval', (id: number | undefined) => {
    if (id !== undefined) intervals.delete(id);
  });
  return (elapsed: number) => {
    for (const [id, interval] of [...intervals]) {
      interval.remaining -= elapsed;
      while (interval.remaining <= 0 && intervals.has(id)) {
        interval.remaining += interval.delay;
        interval.callback();
      }
    }
  };
}

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const ok = (data: unknown, status = 200) => json(status, { code: 'SUCCESS', message: '요청이 성공했습니다.', data });
const fail = (status: number, code: string, message = '서버 연합 오류', data: unknown = null) => json(status, { code, message, data });

const ME: MyInfo = {
  id: 5, nickname: '연합장', email: 'leader@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: SCHOOL_ID.hanyang, name: '한양대학교', logoUrl: null }, department: { id: 11, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 }, totalContribScore: 0, totalAttackScore: 0,
};
/** 2026-11-19 12:00 KST. */
const NOW = Date.parse('2026-11-19T03:00:00Z');
const alliance = (id: number, overrides: Partial<Alliance> = {}): Alliance => ({
  id, name: `연합 ${id}`, totalScore: 1234, status: 'ACTIVE', durationHours: 24, expiredAt: '2026-11-20T12:00:00+09:00', ...overrides,
});

const YONSEI = 2;
const application = (id: number, allianceId: number, status: AllianceApplication['status'], universityId = YONSEI): AllianceApplication =>
  ({ id, allianceId, universityId, status });

const matchOf = (id: number, overrides: Partial<AllianceMatch> = {}): AllianceMatch => ({
  id, allianceAId: 7, allianceBId: 8, winnerAllianceId: null, status: 'REQUESTED', durationHours: 3,
  requestExpiredAt: '2026-11-20T12:00:00+09:00', scheduledStartAt: null, startedAt: null, endedAt: null, aScore: 0, bScore: 0, ...overrides,
});

function fakeStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    data,
    storage: { getItem: (key: string) => data.get(key) ?? null, setItem: (key: string, value: string) => { data.set(key, value); }, removeItem: (key: string) => { data.delete(key); } },
  };
}

interface Call { route: string; body: unknown }
function mount({ create, detail, mine, apply, decide, matchRequest, match, matchDecision, list, signedIn = true, store = fakeStorage(), now = () => NOW, profile = () => ME, watchSession = false, invitationsEnabled = false, decideInvitation }: {
  create?: (body: unknown) => Response; detail?: (id: number) => Response; signedIn?: boolean;
  mine?: () => Response | Promise<Response>; now?: () => number; profile?: () => MyInfo | Promise<MyInfo>; watchSession?: boolean;
  apply?: (allianceId: number) => Response | Promise<Response>; decide?: (applicationId: number, decision: string) => Response;
  matchRequest?: (allianceId: number, body: unknown) => Response; match?: (matchId: number) => Response;
  matchDecision?: (matchId: number, decision: string) => Response;
  list?: (url: URL) => Response | undefined;
  invitationsEnabled?: boolean; decideInvitation?: (id: number, decision: string) => Response | Promise<Response>;
  store?: ReturnType<typeof fakeStorage>;
} = {}) {
  const calls: Call[] = [];
  const created = new Map<number, Alliance>();
  const matches = new Map<number, AllianceMatch>();
  const client = createApiClient({ fetch: (async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const call = { route: `${init?.method ?? 'GET'} ${url.pathname}`, body: init?.body ? JSON.parse(String(init.body)) : undefined };
    calls.push(call);
    if (call.route === 'POST /api/v1/auth/reissue') return ok({ accessToken: 'access-1', expiresInSeconds: 3600 });
    if (call.route === 'GET /api/v1/users/me') return ok(await profile());
    if (call.route === 'GET /api/v1/alliances/me') return mine ? mine() : ok([...created.values()].at(-1) ?? null);
    if (call.route === 'POST /api/v1/alliances') {
      if (create) return create(call.body);
      const body = call.body as { name: string; durationHours: number };
      const made = alliance(7, { name: body.name, durationHours: body.durationHours, totalScore: 0 });
      created.set(made.id, made);
      return ok(made, 201);
    }
    const requested = /^POST \/api\/v1\/alliances\/(\d+)\/matches$/.exec(call.route);
    if (requested) {
      if (matchRequest) return matchRequest(Number(requested[1]), call.body);
      const body = call.body as { opponentAllianceId: number; durationHours: number; scheduledStartAt?: string };
      const made = matchOf(50, { allianceAId: Number(requested[1]), allianceBId: body.opponentAllianceId,
        durationHours: body.durationHours, scheduledStartAt: body.scheduledStartAt ?? null });
      matches.set(made.id, made);
      return ok(made, 201);
    }
    const matchRead = /^GET \/api\/v1\/alliances\/matches\/(\d+)$/.exec(call.route);
    if (matchRead) return match ? match(Number(matchRead[1])) : ok(matches.get(Number(matchRead[1])) ?? matchOf(Number(matchRead[1])));
    const matchDecided = /^PATCH \/api\/v1\/alliances\/matches\/(\d+)\/(accept|cancel)$/.exec(call.route);
    if (matchDecided) {
      if (matchDecision) return matchDecision(Number(matchDecided[1]), matchDecided[2]);
      const before = matches.get(Number(matchDecided[1])) ?? matchOf(Number(matchDecided[1]));
      const after = matchDecided[2] === 'accept'
        ? { ...before, status: 'IN_PROGRESS' as const, startedAt: '2026-11-19T12:00:00+09:00', endedAt: '2026-11-19T15:00:00+09:00' }
        : { ...before, status: 'CANCELED' as const };
      matches.set(after.id, after);
      return ok(after);
    }
    const applied = /^POST \/api\/v1\/alliances\/(\d+)\/applications$/.exec(call.route);
    if (applied) return apply ? apply(Number(applied[1])) : ok(application(31, Number(applied[1]), 'PENDING'), 201);
    const decided = /^PATCH \/api\/v1\/alliances\/applications\/(\d+)\/(approve|reject)$/.exec(call.route);
    if (decided) return decide ? decide(Number(decided[1]), decided[2])
      : ok(application(Number(decided[1]), 7, decided[2] === 'approve' ? 'ACCEPTED' : 'REJECTED'));
    const invitation = /^PATCH \/api\/v1\/alliances\/invitations\/(\d+)\/(approve|reject)$/.exec(call.route);
    if (invitation && decideInvitation) return decideInvitation(Number(invitation[1]), invitation[2]);
    const school = /^GET \/api\/v1\/universities\/(\d+)$/.exec(call.route);
    if (school) return Number(school[1]) === YONSEI ? ok({ id: YONSEI, name: '연세대학교' }) : fail(404, 'UNIVERSITY_NOT_FOUND', '학교 없음');
    if (/^GET \/api\/v1\/alliances(?:\/(?:(?:applications|invitations)\/me|\d+\/(?:applications|invitations|matches|universities)))?$/.test(call.route)) {
      return list?.(url) ?? (url.pathname.endsWith('/universities') ? ok([]) : ok({ items: [], nextCursor: null }));
    }
    const found = /^GET \/api\/v1\/alliances\/(\d+)$/.exec(call.route);
    if (found) return detail ? detail(Number(found[1])) : ok(created.get(Number(found[1])) ?? alliance(Number(found[1])));
    throw new Error(`Unexpected ${call.route}`);
  }) as typeof fetch });
  if (signedIn) client.session.signIn('access-1');
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  const navigation: string[] = [];
  const ui = render(React.createElement(ApiProvider, { client, queryClient, children: React.createElement(React.Fragment, null,
    watchSession && React.createElement(ApiSessionWatcher, { onEnded: () => {} }),
    React.createElement(ApiAllianceScreenPhone, {
      onBack: () => navigation.push('back'), onGoToLogin: () => navigation.push('login'), invitationsEnabled,
      applications: createSentApplicationMemory(() => store.storage), matches: createMatchMemory(() => store.storage), now,
    }),
  ) }));
  const posts = () => calls.filter(({ route }) => route === 'POST /api/v1/alliances').map(({ body }) => body);
  const details = () => calls.filter(({ route }) => /^GET \/api\/v1\/alliances\/\d+$/.test(route)).map(({ route }) => route.slice('GET /api/v1/alliances/'.length));
  const routes = (prefix: string) => calls.filter(({ route }) => route.startsWith(prefix)).map(({ route }) => route);
  return { calls, posts, details, routes, store, navigation, ui, client, queryClient, membershipReads: () => routes('GET /api/v1/alliances/me').length };
}

const openAllianceTab = async (name: string) => {
  const tab = await within(await screen.findByRole('tablist', { name: '연합 메뉴' })).findByRole('tab', { name });
  if (tab.getAttribute('aria-selected') !== 'true') fireEvent.click(tab);
  return screen.getByRole('tabpanel', { name });
};
const openApplicationDecision = async () => within(await openAllianceTab('가입 신청')).findByRole('form', { name: '가입 신청 처리' });
const openMatchRequest = async () => within(await openAllianceTab('대결')).findByRole('form', { name: '대결 신청' });
const createForm = () => screen.getByRole('form', { name: '연합 만들기' });
const fillName = (value: string) => fireEvent.change(within(createForm()).getByLabelText('연합 이름'), { target: { value } });
const openCreateConfirmation = () => fireEvent.click(within(createForm()).getByRole('button', { name: '연합 만들기' }));
const confirmation = (label: string) => within(screen.getByRole('dialog', { name: `${label} 확인` }));
const confirmAction = (label: string) => fireEvent.click(confirmation(label).getByRole('button', { name: label }));
const submitCreate = () => { openCreateConfirmation(); confirmAction('연합 만들기'); };
const field = (detail: HTMLElement, term: string) => {
  const terms = [...detail.querySelectorAll('dt')];
  return terms.find((dt) => dt.textContent === term)?.nextElementSibling?.textContent;
};

test('깨끗한 저장소에서도 서버가 반환한 내 연합을 상세 추가 요청 없이 표시한다', async () => {
  const f = mount({ mine: () => ok(alliance(12, { status: 'IN_MATCH', totalScore: 98765 })) });
  const detail = await screen.findByRole('article', { name: '연합 #12' });
  assert.equal(field(detail, '총점'), '98,765점');
  assert.deepEqual(f.routes('GET /api/v1/alliances/me'), ['GET /api/v1/alliances/me']);
  assert.deepEqual(f.details(), []);
  assertAbsent(screen.queryByRole('form', { name: '가입 신청 처리' }));
  await openApplicationDecision();
});

test('연합 상단 탭은 한 패널만 표시하고, 키보드로 탭에 포커스만 옮길 때 목록을 조회하지 않는다', async () => {
  const f = mount({ mine: () => ok(alliance(7)) });
  const menu = await screen.findByRole('tablist', { name: '연합 메뉴' });
  const tabs = within(menu).getAllByRole('tab');
  assert.deepEqual(tabs.map((tab) => tab.textContent), ['우리 연합', '가입 신청', '대결', '연합 찾기', '대결 이력']);
  const icons = tabs.map((tab) => tab.querySelector('svg[aria-hidden="true"] path')?.getAttribute('d'));
  assert.ok(icons.every(Boolean) && new Set(icons).size === tabs.length, '각 탭에 이름과 분리된 고유 아이콘을 표시한다');
  const overview = within(menu).getByRole('tab', { name: '우리 연합', selected: true });
  const requests = within(menu).getByRole('tab', { name: '가입 신청' });
  await screen.findByRole('article', { name: '연합 #7' });
  act(() => overview.focus());
  fireEvent.keyDown(overview, { key: 'ArrowRight' });
  assertSameNode(document.activeElement, requests);
  screen.getByRole('tabpanel', { name: '우리 연합' });
  assert.equal(screen.getAllByRole('tabpanel').length, 1);
  assert.deepEqual(f.routes('GET /api/v1/alliances/').sort(), ['GET /api/v1/alliances/7/universities', 'GET /api/v1/alliances/me']);
  assert.deepEqual(f.routes('GET /api/v1/alliances').filter((route) => route.endsWith('/alliances')), []);

  const form = await openApplicationDecision();
  assertAbsent(screen.queryByRole('article', { name: '연합 #7' }));
  assert.equal(screen.getAllByRole('tabpanel').length, 1);
  fireEvent.change(within(form).getByLabelText('신청 번호'), { target: { value: '31' } });
  await openAllianceTab('대결');
  assert.deepEqual(f.routes('POST /api/v1/alliances/'), [], '대결 탭을 여는 동작은 대결 신청을 보내지 않는다');
  assertAbsent(screen.queryByRole('form', { name: '가입 신청 처리' }));
  assert.equal((within(await openApplicationDecision()).getByLabelText('신청 번호') as HTMLInputElement).value, '31');
});

test('가입 신청과 대결은 선택된 탭만 폴링하고, 다시 열면 상태를 유지한 채 즉시 조회한다', async (t) => {
  const advance = mockQueryPolling(t);
  let current = matchOf(50);
  const f = mount({ mine: () => ok(alliance(7)), match: () => ok(current), store: fakeStorage({ 'sour.alliance.match.v1:5': '50' }) });
  const reads = () => ['/7/applications', '/applications/me', '/7/matches', '/matches/50']
    .map((path) => f.routes(`GET /api/v1/alliances${path}`).length);
  const tick = () => act(async () => { advance(15_000); });

  const applications = await openAllianceTab('가입 신청');
  await within(applications).findAllByText('가입 신청이 없어요.');
  await waitFor(() => assert.deepEqual(reads(), [1, 1, 0, 0]));
  await tick();
  assert.deepEqual(reads(), [2, 2, 0, 0], '열린 가입 신청 탭은 받은 신청과 보낸 신청을 폴링한다');

  const matches = await openAllianceTab('대결');
  await within(matches).findByText('표시할 대결이 없어요.');
  const card = await within(matches).findByRole('article', { name: '대결 #50' });
  const status = await within(card).findByTestId('match-status');
  assert.equal(status.textContent, '수락 대기');
  await openAllianceTab('우리 연합');
  current = { ...current, status: 'IN_PROGRESS' };
  await tick();
  assert.deepEqual(reads(), [2, 2, 1, 1], '숨겨진 탭에서는 신청 목록, 대결 목록, 대결 상세를 폴링하지 않는다');

  const reopened = await openAllianceTab('대결');
  assertSameNode(within(reopened).getByRole('article', { name: '대결 #50' }), card);
  await waitFor(() => assert.equal(status.textContent, '진행 중'));
  assert.deepEqual(reads(), [2, 2, 2, 2], '대결 탭을 다시 열면 목록과 상세를 즉시 갱신한다');
  await tick();
  assert.deepEqual(reads(), [2, 2, 3, 3], '대결 탭이 열려 있는 동안에는 목록과 상세 폴링을 재개한다');

  await openAllianceTab('가입 신청');
  await waitFor(() => assert.deepEqual(reads(), [3, 3, 3, 3], '가입 신청을 다시 열면 두 목록을 즉시 갱신한다'));
});

test('현재 소속이 없어도 이력 탭에서 과거 소속의 종료 대결만 조회하고 탭 전환 후 상세를 유지한다', async () => {
  const historyReads: string[] = [];
  const previous = alliance(7, { name: '이전 연합', status: 'EXPIRED' });
  const ended = matchOf(50, { status: 'FINISHED', endedAt: '2026-11-18T12:00:00+09:00', winnerAllianceId: 7, aScore: 10, bScore: 2 });
  mount({ match: () => ok(ended), list(url) {
    if (url.searchParams.get('scope') === 'MEMBERSHIP_HISTORY') {
      historyReads.push(url.pathname);
      return ok({ items: [{ ...previous, memberCount: 0 }], nextCursor: null });
    }
    if (url.pathname === '/api/v1/alliances/7/matches' && url.searchParams.get('status') === 'FINISHED') {
      historyReads.push(url.pathname);
      return ok({ items: [{ ...ended, opponent: { id: 8, name: '상대 연합' }, direction: 'OUTGOING', result: 'WIN', myScore: 10, opponentScore: 2 }], nextCursor: null });
    }
  } });
  await screen.findByRole('form', { name: '연합 만들기' });
  assert.deepEqual(historyReads, []);
  const panel = await openAllianceTab('대결 이력');
  fireEvent.click(await within(panel).findByRole('button', { name: '이전 연합 종료 대결 보기' }));
  fireEvent.click(await within(panel).findByRole('button', { name: '상대 연합 대결 #50 상세 보기' }));
  const card = await within(panel).findByRole('article');
  await waitFor(() => assert.equal(field(card, '점수'), '10 : 2'));
  assert.deepEqual(historyReads, ['/api/v1/alliances', '/api/v1/alliances/7/matches']);
  await openAllianceTab('우리 연합');
  assertAbsent(screen.queryByRole('article'));
  assertSameNode(within(await openAllianceTab('대결 이력')).getByRole('article'), card);
});

test('연합을 만들면 다듬은 이름과 기간을 보내고 서버 소속을 다시 조회한다', async () => {
  const f = mount();
  await screen.findByRole('form', { name: '연합 만들기' });
  screen.getByText(/한양대학교이\(가\) 창립 학교가 되는 연합/);
  fillName('  한양 연합  ');
  screen.getByText('5/50자 · 앞뒤 공백은 빼고 저장돼요');
  fireEvent.click(within(screen.getByRole('group', { name: '연합 유지 기간' })).getByRole('button', { name: '48h' }));
  screen.getByText('만들면 48시간 (2일) 뒤에 만료돼요.');
  openCreateConfirmation();
  confirmation('연합 만들기').getByText('연합 이름: 한양 연합');
  confirmation('연합 만들기').getByText('유지 기간: 48시간 (2일)');
  fireEvent.click(confirmation('연합 만들기').getByRole('button', { name: '취소' }));
  assert.deepEqual(f.posts(), [], '취소한 생성은 보내지 않는다');
  assert.equal((within(createForm()).getByLabelText('연합 이름') as HTMLInputElement).value, '  한양 연합  ');
  submitCreate();

  const detail = await screen.findByTestId('alliance-detail');
  assert.deepEqual(f.posts(), [{ name: '한양 연합', durationHours: 48 }]);
  await waitFor(() => assert.equal(within(detail).getByRole('heading', { level: 3 }).textContent, '한양 연합'));
  assert.equal(screen.getByTestId('alliance-status').textContent, '활동 중');
  assert.equal(field(detail, '연합 ID'), '#7');
  assert.equal(field(detail, '유지 기간'), '48시간 (2일)');
  assert.match(field(detail, '만료 시각') ?? '', /2026.*11.*20.*\(KST\)$/);
  assert.equal(field(detail, '남은 시간'), '만료까지 24시간 0분');
  screen.getByText('연합을 만들었어요.');
  assert.equal(f.membershipReads(), 2);
  assert.equal(f.store.data.has('sour.alliance.created.v1:5'), false);
  assertAbsent(screen.queryByRole('form', { name: '연합 만들기' }), '소속된 뒤에는 만들기 폼을 숨긴다');
  await openAllianceTab('연합 찾기');
  screen.getByRole('heading', { name: '연합 목록' });
  await openApplicationDecision();
  screen.getByRole('region', { name: '받은 가입 신청' });
});

test('낡은 로컬 값은 서버 소속과 무관하며 로딩·오류를 미가입으로 표시하지 않는다', async () => {
  let finish!: (value: Response) => void;
  const pending = new Promise<Response>((resolve) => { finish = resolve; });
  let reply: () => Response | Promise<Response> = () => pending;
  const f = mount({ store: fakeStorage({ 'sour.alliance.created.v1:5': '12' }), mine: () => reply() });
  try {
    await screen.findByText('우리 연합을 확인하는 중이에요.');
    assertAbsent(screen.queryByRole('form', { name: '연합 만들기' }));
    assertAbsent(screen.queryByRole('form', { name: '가입 신청 처리' }));
  } finally {
    finish(fail(400, 'COMMON_INVALID_INPUT', '소속 조회 실패'));
  }
  await screen.findByText(/우리 연합을 확인하지 못했어요/);
  assertAbsent(screen.queryByRole('form', { name: '연합 만들기' }));
  assertAbsent(screen.queryByRole('form', { name: '가입 신청 처리' }));
  reply = () => ok(alliance(12));
  fireEvent.click(screen.getByRole('button', { name: '우리 연합 다시 확인' }));
  await screen.findByRole('article', { name: '연합 #12' });
  reply = () => fail(400, 'COMMON_INVALID_INPUT', '재조회 실패');
  fireEvent.click(screen.getByRole('button', { name: '우리 연합 다시 확인' }));
  await screen.findByText(/우리 연합을 확인하지 못했어요\. 재조회 실패/);
  assertAbsent(screen.queryByRole('form', { name: '연합 만들기' }), '재조회 오류를 미가입으로 숨기지 않는다');
  assertAbsent(screen.queryByRole('form', { name: '가입 신청 처리' }));
  reply = () => ok(null);
  fireEvent.click(screen.getByRole('button', { name: '우리 연합 다시 확인' }));
  await screen.findByRole('form', { name: '연합 만들기' });
  assert.deepEqual(f.details(), [], '낡은 번호로 상세 요청을 보내지 않는다');
  const detail = await lookup(12);
  assertAbsent(within(detail).queryByRole('button', { name: '우리 연합으로 기억' }));
  assertAbsent(screen.queryByRole('form', { name: '가입 신청 처리' }));
  assertAbsent(screen.queryByRole('form', { name: '대결 신청' }));
});

test('우리 학교가 이미 연합에 소속돼 있으면(409) 번호 없이 서버 소속을 다시 확인할 수 있다', async () => {
  const f = mount({ create: () => fail(409, 'ALLY_UNIVERSITY_ALREADY_JOINED', '이미 연합에 소속된 학교입니다.') });
  await screen.findByRole('form', { name: '연합 만들기' });
  fillName('두 번째 연합');
  submitCreate();
  const error = await screen.findByTestId('alliance-create-error');
  assert.equal(error.getAttribute('role'), 'alert');
  assert.match(error.textContent ?? '', /^우리 학교는 이미 연합에 소속돼 있어요\.한 학교는 연합 하나에만 소속될 수 있어요/);
  assert.equal(f.store.data.size, 0, '실패한 생성은 기억하지 않는다');
  fireEvent.click(within(error).getByRole('button', { name: '우리 연합 다시 확인' }));
  await waitFor(() => assert.equal(f.membershipReads(), 2));
  assert.equal((within(createForm()).getByLabelText('연합 이름') as HTMLInputElement).value, '두 번째 연합', '입력은 그대로 둔다');
});

test('활동 중이 아닌 회원(403), 입력 오류(400)는 각각 알리고, 다시 누르면 다시 보낸다', async () => {
  let reply = () => fail(403, 'ALLY_USER_NOT_ACTIVE', '활성 회원만 연합을 생성할 수 있습니다.');
  const f = mount({ create: () => reply() });
  await screen.findByRole('form', { name: '연합 만들기' });
  fillName('연합');
  submitCreate();
  await waitFor(() => assert.match(screen.getByTestId('alliance-create-error').textContent ?? '', /^활동 중인 회원만 연합을 만들 수 있어요\./));
  reply = () => fail(400, 'COMMON_INVALID_INPUT', '입력값이 올바르지 않습니다.', [{ field: 'name', reason: '서버 이름 규칙' }]);
  submitCreate();
  await waitFor(() => assert.equal(screen.getByTestId('alliance-create-error').textContent, '연합을 만들지 못했어요. 서버 이름 규칙'));
  assert.equal(within(createForm()).getByLabelText('연합 이름').getAttribute('aria-invalid'), 'true');
  reply = () => { throw new TypeError('network down'); };
  submitCreate();
  await waitFor(() => assert.match(screen.getByTestId('alliance-create-error').textContent ?? '', /응답을 받지 못했다면 같은 학교 연합이 이미 생겼을 수 있어요/));
  assert.equal(f.posts().length, 3, '자동 재시도 없이 누를 때마다 한 번');
});

test('이름이 비었거나 50자를 넘으면 보내지 않는다. 앞뒤 공백을 뺀 50자는 보낸다', async () => {
  const f = mount();
  await screen.findByRole('form', { name: '연합 만들기' });
  const input = within(createForm()).getByLabelText('연합 이름');
  for (const [value, message] of [['   ', '연합 이름을 입력해 주세요.'], ['가'.repeat(51), '연합 이름은 50자 이하로 입력해 주세요.']]) {
    fillName(value);
    openCreateConfirmation();
    assert.equal(screen.getByRole('alert').textContent, message);
    assert.equal(input.getAttribute('aria-invalid'), 'true');
  }
  assert.deepEqual(f.posts(), []);
  fillName(`  ${'가'.repeat(50)}  `);
  assert.equal(input.getAttribute('aria-invalid'), 'false', '고치면 오류 표시를 지운다');
  submitCreate();
  await screen.findByTestId('alliance-detail');
  assert.deepEqual(f.posts(), [{ name: '가'.repeat(50), durationHours: 24 }]);
});

test('만료 시각에 15초 주기를 기다리지 않고 서버 소속을 다시 읽는다', async () => {
  const expires = NOW + 300;
  let clock = NOW;
  const f = mount({ now: () => clock, mine: () => ok(clock < expires ? alliance(4, { expiredAt: new Date(expires).toISOString() }) : null) });
  await screen.findByRole('article', { name: '연합 #4' });
  clock = expires;
  await screen.findByRole('form', { name: '연합 만들기' });
  assert.equal(f.membershipReads(), 2);
  assert.deepEqual(f.details(), []);
  assertAbsent(screen.queryByRole('form', { name: '가입 신청 처리' }));
});

test('연합 ID로 찾으면 그 연합을 보이고 기억하지는 않는다. 잘못된 ID는 보내지 않는다', async () => {
  const f = mount({ detail: (id) => id === 404 ? fail(404, 'ALLY_NOT_FOUND', '연합을 찾을 수 없습니다.') : ok(alliance(id)) });
  await screen.findByRole('form', { name: '연합 만들기' });
  const lookup = within(await openAllianceTab('연합 찾기')).getByRole('form', { name: '연합 ID로 찾기' });
  const input = within(lookup).getByLabelText('연합 ID');
  fireEvent.change(input, { target: { value: '0' } });
  fireEvent.click(within(lookup).getByRole('button', { name: '찾기' }));
  assert.equal(within(lookup).getByRole('alert').textContent, '연합 ID는 1 이상의 정수로 입력해 주세요.');
  fireEvent.change(input, { target: { value: '9' } });
  fireEvent.click(within(lookup).getByRole('button', { name: '찾기' }));
  const detail = await screen.findByRole('article', { name: '연합 #9' });
  await waitFor(() => assert.equal(within(detail).getByRole('heading', { level: 3 }).textContent, '연합 9'));
  assertAbsent(within(detail).queryByRole('button', { name: '이 연합 잊기' }), '찾아본 연합은 내 연합이 아니다');
  assert.equal(f.store.data.size, 0);
  fireEvent.change(input, { target: { value: '404' } });
  fireEvent.click(within(lookup).getByRole('button', { name: '찾기' }));
  await screen.findByText('연합 #404을(를) 찾을 수 없어요.');
  within(await openAllianceTab('우리 연합')).getByRole('form', { name: '연합 만들기' });
});

test('브라우저 저장소를 쓸 수 없어도 만든 연합을 보인다', async () => {
  const store = fakeStorage();
  store.storage.getItem = () => { throw new Error('blocked'); };
  store.storage.setItem = () => { throw new Error('blocked'); };
  store.storage.removeItem = () => { throw new Error('blocked'); };
  mount({ store });
  await screen.findByRole('form', { name: '연합 만들기' });
  fillName('저장소 없는 연합');
  submitCreate();
  const detail = await screen.findByTestId('alliance-detail');
  await waitFor(() => assert.equal(field(detail, '연합 ID'), '#7'));
  assertAbsent(screen.queryByRole('form', { name: '연합 만들기' }));
});

test('배포 플래그가 꺼져 있으면 초대 탭을 노출하거나 조회하지 않는다', async () => {
  const f = mount();
  await screen.findByRole('form', { name: '연합 만들기' });
  assertAbsent(screen.queryByRole('tab', { name: '연합 초대' }));
  assert.deepEqual(f.calls.filter(({ route }) => route.includes('/invitations')), []);
});

test('초대 내역을 구분하고, 받은 초대를 확인한 뒤 한 번만 처리해 목록과 소속을 갱신한다', async () => {
  const incoming = { id: 61, alliance: { id: 7, name: '보낸 연합' }, university: ME.university,
    status: 'PENDING', createdAt: new Date(NOW).toISOString(), approvalBlockReason: null };
  let membership: Alliance | null = null;
  let state = 'PENDING';
  const f = mount({ invitationsEnabled: true, mine: () => ok(membership), list: (url) => {
    if (url.pathname.endsWith('/invitations/me')) return ok({ items: [{ ...incoming, status: state }], nextCursor: null });
    if (url.pathname.endsWith('/7/invitations')) return ok({ items: [{ ...incoming, id: 62, university: { id: YONSEI, name: '연세대학교' },
      approvalBlockReason: 'UNIVERSITY_ALREADY_JOINED' }], nextCursor: null });
  }, decideInvitation: (id) => {
    membership = alliance(7);
    state = 'ACCEPTED';
    return ok(application(id, 7, 'ACCEPTED', ME.university.id));
  } });
  assert.deepEqual(f.routes('GET /api/v1/alliances/invitations'), []);
  const panel = within(await openAllianceTab('연합 초대'));
  const received = within(panel.getByRole('region', { name: '받은 연합 초대' }));
  assertAbsent(panel.queryByRole('region', { name: '보낸 연합 초대' }));
  const approve = await received.findByRole('button', { name: '보낸 연합 초대 승인' });
  fireEvent.click(approve);
  confirmation('초대 승인').getByText('보낸 연합에서 보낸 연합 초대');
  fireEvent.click(confirmation('초대 승인').getByRole('button', { name: '취소' }));
  assert.deepEqual(f.routes('PATCH'), []);
  fireEvent.click(approve);
  const execute = confirmation('초대 승인').getByRole('button', { name: '초대 승인' });
  fireEvent.click(execute);
  fireEvent.click(execute);
  await panel.findByText('연합에 가입했어요.');
  await received.findByText('보낸 연합 · 승인');
  const sent = within(await panel.findByRole('region', { name: '보낸 연합 초대' }));
  await sent.findByText('초대받은 학교가 이미 다른 연합에 속해 있어요.');
  assertAbsent(sent.queryByRole('button', { name: '연세대학교 초대 승인' }));
  assert.deepEqual(f.routes('PATCH'), ['PATCH /api/v1/alliances/invitations/61/approve']);
});

test('승인 불가 사유를 표시하고 거절 응답이 끊기면 성공으로 표시하지 않는다', async () => {
  const f = mount({ invitationsEnabled: true, list: (url) => url.pathname.endsWith('/invitations/me') ? ok({
    items: [{ id: 62, alliance: { id: 7, name: '대상 연합' }, university: ME.university, status: 'PENDING',
      createdAt: new Date(NOW).toISOString(), approvalBlockReason: 'UNIVERSITY_ALREADY_JOINED' }], nextCursor: null,
  }) : undefined, decideInvitation: () => { throw new TypeError('network down'); } });
  const panel = within(await openAllianceTab('연합 초대'));
  const approve = await panel.findByRole('button', { name: '대상 연합 초대 승인' });
  assert.equal((approve as HTMLButtonElement).disabled, true);
  fireEvent.click(panel.getByRole('button', { name: '대상 연합 초대 거절' }));
  confirmAction('초대 거절');
  await panel.findByText(/서버 응답을 확인하지 못했어요/);
  assertAbsent(panel.queryByText('연합 초대를 거절했어요.'));
  assert.deepEqual(f.routes('PATCH'), ['PATCH /api/v1/alliances/invitations/62/reject']);
});

test('로그인하지 않았으면 로그인 안내만 보이고 서버를 부르지 않는다', async () => {
  const f = mount({ signedIn: false });
  fireEvent.click(await screen.findByRole('button', { name: '로그인' }));
  assert.deepEqual(f.navigation, ['login']);
  assert.deepEqual(f.calls, []);
  fireEvent.click(screen.getByRole('button', { name: '이전 화면으로 돌아가기' }));
  assert.deepEqual(f.navigation, ['login', 'back']);
});

test('브라우저 탭이 숨겨지면 폴링하지 않고 복귀할 때 최신 소속을 조회한다', async (t) => {
  const advance = mockQueryPolling(t);
  t.mock.getter(document, 'visibilityState', () => visibility);
  let visibility: DocumentVisibilityState = 'visible';
  let current: Alliance | null = alliance(7);
  const f = mount({ mine: () => ok(current) });
  await screen.findByRole('article', { name: '연합 #7' });
  current = null;
  visibility = 'hidden';
  await act(async () => { fireEvent(document, new dom.window.Event('visibilitychange', { bubbles: true })); advance(30_000); });
  assert.equal(f.membershipReads(), 1);
  visibility = 'visible';
  await act(async () => { fireEvent(document, new dom.window.Event('visibilitychange', { bubbles: true })); });
  await screen.findByText('우리 학교가 속한 연합이 없어요.');
  assert.equal(f.membershipReads(), 2);
  assertAbsent(screen.queryByRole('form', { name: '가입 신청 처리' }));
});

test('같은 프레임에서 계정을 바꿔도 이전 계정의 소속과 관리 화면을 남기지 않는다', async () => {
  let profile: MyInfo | Promise<MyInfo> = ME;
  let current: Alliance | null = alliance(7);
  const f = mount({ watchSession: true, profile: () => profile, mine: () => ok(current) });
  await screen.findByRole('article', { name: '연합 #7' });
  await openApplicationDecision();
  let finish!: (value: MyInfo) => void;
  profile = new Promise((resolve) => { finish = resolve; });
  current = alliance(11);
  await act(async () => { f.client.session.signOut(); f.client.session.signIn('access-2'); });
  try {
    assertAbsent(screen.queryByRole('article', { name: '연합 #7' }));
    assertAbsent(screen.queryByRole('form', { name: '가입 신청 처리' }));
  } finally {
    finish({ ...ME, id: 6 });
  }
  await screen.findByRole('article', { name: '연합 #11' });
  assert.equal(f.membershipReads(), 2);
  act(() => f.client.session.signOut());
  await screen.findByRole('button', { name: '로그인' });
  assertAbsent(screen.queryByRole('article', { name: '연합 #11' }));
});

const lookup = async (id: number) => {
  const form = within(await openAllianceTab('연합 찾기')).getByRole('form', { name: '연합 ID로 찾기' });
  fireEvent.change(within(form).getByLabelText('연합 ID'), { target: { value: String(id) } });
  fireEvent.click(within(form).getByRole('button', { name: '찾기' }));
  const detail = await screen.findByRole('article', { name: `연합 #${id}` });
  await within(detail).findByRole('heading', { level: 3 });
  return detail;
};

test('가입 신청 번호는 보조로 기억하고, 외부 승인 뒤 서버 소속 갱신으로만 관리 화면을 연다', async (t) => {
  const advance = mockQueryPolling(t);
  let current: Alliance | null = null;
  const f = mount({ mine: () => ok(current) });
  await screen.findByRole('form', { name: '연합 만들기' });
  const found = await lookup(9);
  fireEvent.click(within(found).getByRole('button', { name: '이 연합에 가입 신청' }));
  await within(found).findByText('가입 신청 #31을(를) 보냈어요.');
  assert.deepEqual(f.routes('POST /api/v1/alliances/'), ['POST /api/v1/alliances/9/applications']);
  assert.deepEqual(JSON.parse(f.store.data.get('sour.alliance.application.v1:5') ?? 'null'), { applicationId: 31, allianceId: 9 });
  const card = within(await openAllianceTab('우리 연합')).getByTestId('sent-application');
  within(card).getByRole('heading', { name: '보낸 가입 신청 #31' });
  await within(card).findByRole('heading', { name: '연합 9' });

  assertAbsent(within(card).queryByRole('button', { name: '우리 연합으로 기억' }));
  assertAbsent(screen.queryByRole('form', { name: '가입 신청 처리' }));
  current = alliance(9);
  await act(async () => { advance(15_000); });
  await screen.findByRole('article', { name: '연합 #9' });
  assert.equal(f.membershipReads(), 2);
  assertAbsent(screen.queryByTestId('sent-application'));
  assert.equal(f.store.data.has('sour.alliance.application.v1:5'), false, '외부 승인 뒤 낡은 신청 기억은 지운다');
  await openApplicationDecision();
  await openAllianceTab('연합 찾기');
  within(found).getByText('우리 연합이에요.');
  assertAbsent(within(found).queryByRole('button', { name: '이 연합에 가입 신청' }), '우리 연합에는 신청 버튼이 없다');
});

test('가입 신청 응답을 받기 전에 탭을 바꿔도 결과와 보낸 신청을 남긴다', async () => {
  let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { finish = resolve; });
  const f = mount({ apply: () => pending });
  const found = await lookup(9);
  try {
    fireEvent.click(within(found).getByRole('button', { name: '이 연합에 가입 신청' }));
    await within(found).findByRole('button', { name: '신청하는 중…' });
    await openAllianceTab('우리 연합');
    assertAbsent(screen.queryByRole('article', { name: '연합 #9' }));
  } finally {
    finish(ok(application(31, 9, 'PENDING'), 201));
  }
  await screen.findByRole('heading', { name: '보낸 가입 신청 #31' });
  await openAllianceTab('연합 찾기');
  within(found).getByText('가입 신청 #31을(를) 보냈어요.');
  assert.deepEqual(f.routes('POST /api/v1/alliances/'), ['POST /api/v1/alliances/9/applications']);
});

test('가입 신청 오류는 코드마다 알리고 기억하지 않는다', async () => {
  const cases: Array<[number, string, RegExp]> = [
    [409, 'ALLY_UNIVERSITY_ALREADY_JOINED', /^우리 학교는 이미 연합에 소속돼 있어요/],
    [409, 'ALLY_APPLICATION_PENDING', /이미 처리를 기다리고 있어요/],
    [409, 'ALLY_FULL', /학교 2곳이 모두 찼어요/],
    [409, 'ALLY_NOT_ACTIVE', /지금 가입 신청을 받을 수 없어요/],
    [403, 'ALLY_USER_NOT_ACTIVE', /활동 중인 회원만 가입 신청/],
  ];
  let index = 0;
  const f = mount({ apply: () => fail(cases[index][0], cases[index][1]) });
  const found = await lookup(9);
  for (; index < cases.length; index++) {
    fireEvent.click(within(found).getByRole('button', { name: '이 연합에 가입 신청' }));
    await waitFor(() => assert.match(within(found).getByTestId('alliance-apply-error').textContent ?? '', cases[index][2]));
  }
  assert.equal(f.routes('POST /api/v1/alliances/').length, cases.length, '누를 때마다 한 번, 자동 재시도 없음');
  assert.equal(f.store.data.size, 0);
});

test('우리 연합에서 신청 번호를 승인하면 확인을 거쳐 보내고, 들어온 학교와 2/2를 보인다', async () => {
  const f = mount({ mine: () => ok(alliance(7)) });
  const form = await openApplicationDecision();
  const input = within(form).getByLabelText('신청 번호');
  fireEvent.click(within(form).getByRole('button', { name: '승인' }));
  assert.equal(within(form).getByRole('alert').textContent, '신청 번호를 입력해 주세요.');
  fireEvent.change(input, { target: { value: '31' } });
  fireEvent.click(within(form).getByRole('button', { name: '승인' }));
  const confirm = confirmation('가입 승인');
  confirm.getByText('가입 신청 #31');
  confirm.getByText('처리하면 되돌릴 수 없어요.');
  assert.deepEqual(f.routes('PATCH'), [], '확인 전에는 보내지 않는다');
  fireEvent.click(confirm.getByRole('button', { name: '취소' }));
  assert.deepEqual(f.routes('PATCH'), []);
  fireEvent.click(within(form).getByRole('button', { name: '승인' }));
  confirmAction('가입 승인');
  const result = await within(form).findByTestId('application-decision-result');
  await waitFor(() => assert.equal(result.textContent, '연세대학교이(가) 연합에 들어왔어요.연합 #7 학교 2/2 · 정원이 찼어요. 남은 대기 신청은 서버가 자동으로 거절해요.'));
  assert.deepEqual(f.routes('PATCH'), ['PATCH /api/v1/alliances/applications/31/approve']);
  assert.equal((input as HTMLInputElement).value, '', '처리한 번호는 지운다');
  await waitFor(() => assert.equal(f.membershipReads(), 2, '승인 뒤 현재 소속을 다시 읽는다'));
  assert.deepEqual(f.details(), []);
});

test('거절은 확인 뒤 보내고, 학교 이름을 못 읽으면 번호로 보인다. 다른 연합의 신청이면 알린다', async () => {
  const f = mount({ mine: () => ok(alliance(7)), decide: (id) => ok(application(id, 8, 'REJECTED', 99)) });
  const form = await openApplicationDecision();
  fireEvent.change(within(form).getByLabelText('신청 번호'), { target: { value: ' 40 ' } });
  fireEvent.click(within(form).getByRole('button', { name: '거절' }));
  confirmAction('가입 거절');
  const result = await within(form).findByTestId('application-decision-result');
  await waitFor(() => assert.match(result.textContent ?? '', /^신청 #40\(학교 #99\)을\(를\) 거절했어요\./));
  assert.match(result.textContent ?? '', /우리 연합 #7이 아니라 연합 #8에 온 신청이었어요/);
  assert.deepEqual(f.routes('PATCH'), ['PATCH /api/v1/alliances/applications/40/reject']);
});

test('신청 처리 오류는 코드마다 알린다', async () => {
  const cases: Array<[number, string, RegExp]> = [
    [404, 'ALLY_APPLICATION_NOT_FOUND', /^신청 #31을\(를\) 찾을 수 없어요/],
    [409, 'ALLY_APPLICATION_NOT_PENDING', /^신청 #31은\(는\) 이미 처리됐어요/],
    [403, 'ALLY_NOT_MEMBER', /소속이 아니에요/],
    [409, 'ALLY_FULL', /학교 2곳이 이미 찼어요/],
    [409, 'ALLY_UNIVERSITY_ALREADY_JOINED', /다른 연합에 들어갔어요/],
    [409, 'ALLY_NOT_ACTIVE', /처리할 수 없어요/],
  ];
  let index = 0;
  mount({ mine: () => ok(alliance(7)), decide: () => fail(cases[index][0], cases[index][1]) });
  const form = await openApplicationDecision();
  for (; index < cases.length; index++) {
    fireEvent.change(within(form).getByLabelText('신청 번호'), { target: { value: '31' } });
    fireEvent.click(within(form).getByRole('button', { name: '승인' }));
    confirmAction('가입 승인');
    await waitFor(() => assert.match(within(form).getByTestId('application-decision-error').textContent ?? '', cases[index][2]));
    assert.equal((within(form).getByLabelText('신청 번호') as HTMLInputElement).value, '31', '실패하면 번호를 남긴다');
  }
});

test('보낸 신청의 연합이 없어지면(404) 신청 기억을 지운다. 기억이 깨져 있으면 무시한다', async () => {
  const gone = mount({ store: fakeStorage({ 'sour.alliance.application.v1:5': JSON.stringify({ applicationId: 3, allianceId: 4 }) }),
    detail: () => fail(404, 'ALLY_NOT_FOUND', '연합을 찾을 수 없습니다.') });
  await screen.findByText('가입 신청한 연합 #4을(를) 찾을 수 없어 신청 기억을 지웠어요.');
  assert.equal(gone.store.data.size, 0);
  cleanup();
  const broken = fakeStorage({ 'sour.alliance.application.v1:5': '{"applicationId":"3"}' });
  assert.equal(createSentApplicationMemory(() => broken.storage).get(5), null);
  assert.equal(createSentApplicationMemory(() => fakeStorage({ 'sour.alliance.application.v1:5': 'not json' }).storage).get(5), null);
});

const matchCard = async () => within(await openAllianceTab('대결')).findByTestId('match-card');
const lookupMatch = async (id: number) => {
  const form = await within(await openAllianceTab('대결')).findByRole('form', { name: '대결 번호로 찾기' });
  fireEvent.change(within(form).getByLabelText('대결 번호로 찾기'), { target: { value: String(id) } });
  fireEvent.click(within(form).getByRole('button', { name: '찾기' }));
};
const confirmMatch = (card: HTMLElement, label: string) => {
  fireEvent.click(within(card).getByRole('button', { name: label }));
  fireEvent.click(within(within(card).getByRole('group', { name: '대결 처리 확인' })).getByRole('button', { name: `${label}하기` }));
};

test('우리 연합에서 상대 연합에 대결을 신청하면 본문을 보내고, 대결 번호를 알리며 수락 대기 카드를 보인다', async () => {
  const f = mount({ mine: () => ok(alliance(7)) });
  const form = await openMatchRequest();
  await screen.findByText('표시할 대결이 없어요.');
  const submit = () => fireEvent.click(within(form).getByRole('button', { name: '대결 신청하기' }));
  submit();
  assert.equal(within(form).getByRole('alert').textContent, '상대 연합 ID를 입력해 주세요.');
  fireEvent.change(within(form).getByLabelText('상대 연합 ID'), { target: { value: '7' } });
  submit();
  assert.equal(within(form).getByRole('alert').textContent, '우리 연합과는 대결할 수 없어요.');
  assert.deepEqual(f.routes('POST /api/v1/alliances/'), []);
  fireEvent.change(within(form).getByLabelText('상대 연합 ID'), { target: { value: '8' } });
  fireEvent.click(within(within(form).getByRole('group', { name: '대결 시간' })).getByRole('button', { name: '3h' }));
  submit();

  const card = await matchCard();
  screen.getByText('대결 신청 #50을(를) 보냈어요.');
  assert.deepEqual(f.calls.filter(({ route }) => route === 'POST /api/v1/alliances/7/matches').map(({ body }) => body), [{ opponentAllianceId: 8, durationHours: 3 }]);
  assert.equal(f.store.data.get('sour.alliance.match.v1:5'), '50');
  assert.equal((within(form).getByLabelText('상대 연합 ID') as HTMLInputElement).value, '', '보낸 뒤 상대 ID를 지운다');
  assert.equal(within(card).getByTestId('match-status').textContent, '수락 대기');
  await waitFor(() => assert.equal(field(card, '신청한 연합'), '연합 7 #7 (우리)'));
  await waitFor(() => assert.equal(field(card, '받은 연합'), '연합 8 #8'));
  assert.equal(field(card, '대결 시간'), '3시간');
  assert.match(field(card, '수락 마감') ?? '', /2026.*11.*20.*\(KST\)$/);
  assert.equal(field(card, '남은 시간'), '24시간 0분');
  within(card).getByRole('button', { name: '신청 취소' });
  assertAbsent(within(card).queryByRole('button', { name: '수락' }), '신청한 쪽은 수락할 수 없다');
});

test('예약 모드만으로 전송하지 않고, KST 예약을 offset 포함 본문으로 보낸다', async () => {
  const f = mount({ mine: () => ok(alliance(7)) });
  const form = await openMatchRequest();
  fireEvent.change(within(form).getByLabelText('상대 연합 ID'), { target: { value: '8' } });
  fireEvent.click(within(form).getByRole('radio', { name: '시작 시각 예약' }));
  assert.deepEqual(f.routes('POST'), []);
  const input = within(form).getByLabelText('예약 시작 시각 (한국 시각 KST)');
  assert.equal(input.getAttribute('min'), '2026-11-19T12:01');
  assert.equal(input.getAttribute('max'), '2026-11-20T06:00');
  fireEvent.click(within(form).getByRole('button', { name: '대결 신청하기' }));
  assert.equal(input.getAttribute('aria-invalid'), 'true');
  assertSameNode(document.activeElement, input);
  assert.deepEqual(f.routes('POST'), []);
  fireEvent.change(input, { target: { value: '2026-11-19T13:30' } });
  assert.equal(input.getAttribute('aria-invalid'), 'false');
  fireEvent.click(within(form).getByRole('button', { name: '대결 신청하기' }));
  const card = await matchCard();
  assert.deepEqual(f.calls.filter(({ route }) => route === 'POST /api/v1/alliances/7/matches').map(({ body }) => body),
    [{ opponentAllianceId: 8, durationHours: 6, scheduledStartAt: '2026-11-19T13:30:00+09:00' }]);
  assert.match(field(card, '예약 시작') ?? '', /2026.*11.*19.*1:30.*KST/);
});

test('예약 시각·우리 연합 만료를 검증하고 즉시 시작으로 바꾸면 예약 필드를 보내지 않는다', async () => {
  const f = mount({ mine: () => ok(alliance(7)) });
  const form = await openMatchRequest();
  fireEvent.change(within(form).getByLabelText('상대 연합 ID'), { target: { value: '8' } });
  fireEvent.click(within(form).getByRole('radio', { name: '시작 시각 예약' }));
  const input = within(form).getByLabelText('예약 시작 시각 (한국 시각 KST)');
  for (const [value, message] of [['2026-11-19T12:00', /현재보다 이후/], ['2026-11-20T06:01', /우리 연합 만료/]] as const) {
    fireEvent.change(input, { target: { value } });
    fireEvent.click(within(form).getByRole('button', { name: '대결 신청하기' }));
    assert.match(within(form).getByRole('alert').textContent ?? '', message);
    assert.equal(input.getAttribute('aria-invalid'), 'true');
    assertSameNode(document.activeElement, input);
  }
  assert.deepEqual(f.routes('POST'), []);
  fireEvent.click(within(form).getByRole('radio', { name: '수락하면 즉시 시작' }));
  assertAbsent(within(form).queryByLabelText('예약 시작 시각 (한국 시각 KST)'));
  fireEvent.click(within(form).getByRole('button', { name: '대결 신청하기' }));
  await matchCard();
  assert.deepEqual(f.calls.filter(({ route }) => route === 'POST /api/v1/alliances/7/matches').map(({ body }) => body),
    [{ opponentAllianceId: 8, durationHours: 6 }]);
});

test('예약 서버 오류에도 입력을 보존하고 자동 재전송 없이 수정·재시도할 수 있다', async () => {
  let response = () => fail(400, 'MATCH_INVALID_SCHEDULE');
  const f = mount({ mine: () => ok(alliance(7)), matchRequest: () => response() });
  const form = await openMatchRequest();
  fireEvent.change(within(form).getByLabelText('상대 연합 ID'), { target: { value: '8' } });
  fireEvent.click(within(form).getByRole('radio', { name: '시작 시각 예약' }));
  const input = within(form).getByLabelText('예약 시작 시각 (한국 시각 KST)') as HTMLInputElement;
  fireEvent.change(input, { target: { value: '2026-11-19T13:30' } });
  fireEvent.click(within(form).getByRole('button', { name: '대결 신청하기' }));
  await within(form).findByTestId('match-request-error');
  assert.match(within(form).getByRole('alert').textContent ?? '', /예약 시작 시각이 지났거나/);
  assert.equal(input.value, '2026-11-19T13:30');
  assert.equal(input.getAttribute('aria-invalid'), 'true');
  assertSameNode(document.activeElement, input);
  assert.equal(f.routes('POST').length, 1);
  response = () => fail(400, 'COMMON_INVALID_INPUT', '검증 실패', [{ field: 'scheduledStartAt', reason: '서버 예약 규칙' }]);
  fireEvent.click(within(form).getByRole('button', { name: '대결 신청하기' }));
  await waitFor(() => assert.match(within(form).getByRole('alert').textContent ?? '', /서버 예약 규칙/));
  fireEvent.change(input, { target: { value: '2026-11-19T14:30' } });
  assert.equal(input.getAttribute('aria-invalid'), 'false');
  assertAbsent(within(form).queryByRole('alert'));
});

test('받은 연합은 대결 번호로 찾아 확인 뒤 수락하고, 진행 중 현황과 연합 상태 갱신을 본다', async () => {
  const f = mount({ mine: () => ok(alliance(8)) });
  await lookupMatch(50);
  const card = await matchCard();
  await waitFor(() => assert.equal(field(card, '받은 연합'), '연합 8 #8 (우리)'));
  assert.equal(f.store.data.get('sour.alliance.match.v1:5'), '50', '찾은 대결을 기억한다');
  within(card).getByRole('button', { name: '거절' });
  fireEvent.click(within(card).getByRole('button', { name: '수락' }));
  within(card).getByText('대결 #50을(를) 수락할까요? 수락하면 바로 시작되고 되돌릴 수 없어요.');
  fireEvent.click(within(card).getByRole('button', { name: '돌아가기' }));
  assert.deepEqual(f.routes('PATCH'), [], '확인 전에는 보내지 않는다');
  const before = f.details().length;
  confirmMatch(card, '수락');
  await waitFor(() => assert.equal(within(card).getByTestId('match-status').textContent, '진행 중'));
  assert.deepEqual(f.routes('PATCH'), ['PATCH /api/v1/alliances/matches/50/accept']);
  assert.match(field(card, '시작') ?? '', /\(KST\)$/);
  assert.match(field(card, '종료 예정') ?? '', /\(KST\)$/);
  assert.equal(field(card, '남은 시간'), '3시간 0분');
  within(card).getByText('진행 중 점수는 대결이 끝나면 정산돼 표시돼요.');
  assertAbsent(within(card).queryByRole('button', { name: '수락' }));
  await waitFor(() => assert.ok(f.details().length > before, '수락 뒤 연합 상세(대결 중)를 다시 읽는다'));
});

test('예약 신청 수락은 즉시 시작한다고 안내하지 않고, 수락 뒤 예약 카드에는 행동 버튼이 없다', async () => {
  let current = matchOf(50, { scheduledStartAt: '2026-11-19T13:30:00+09:00' });
  const f = mount({ mine: () => ok(alliance(8)), store: fakeStorage({ 'sour.alliance.match.v1:5': '50' }), match: () => ok(current),
    matchDecision: () => { current = { ...current, status: 'SCHEDULED' }; return ok(current); } });
  const card = await matchCard();
  await within(card).findByRole('button', { name: '수락' });
  fireEvent.click(within(card).getByRole('button', { name: '수락' }));
  const confirmation = within(card).getByRole('group', { name: '대결 처리 확인' });
  assert.match(confirmation.textContent ?? '', /KST.*시작하도록 예약돼요/);
  assert.doesNotMatch(confirmation.textContent ?? '', /바로 시작/);
  fireEvent.click(within(confirmation).getByRole('button', { name: '수락하기' }));
  await waitFor(() => assert.equal(within(card).getByTestId('match-status').textContent, '예약됨'));
  assert.equal(field(card, '시작까지'), '1시간 30분');
  assert.match(card.textContent ?? '', /수락한 예약은 취소할 수 없어요/);
  for (const label of ['수락', '거절', '신청 취소']) assertAbsent(within(card).queryByRole('button', { name: label }));
  assert.deepEqual(f.routes('PATCH'), ['PATCH /api/v1/alliances/matches/50/accept']);
});

test('예약은 15초마다 서버를 읽어 진행 중으로 바뀌고 연합 상세도 갱신하며, 종료되면 폴링을 멈춘다', async (t) => {
  const advance = mockQueryPolling(t);
  let current = matchOf(50, { status: 'SCHEDULED', scheduledStartAt: '2026-11-19T13:30:00+09:00' });
  const f = mount({ mine: () => ok(alliance(7)), store: fakeStorage({ 'sour.alliance.match.v1:5': '50' }), match: () => ok(current) });
  const card = await matchCard();
  await waitFor(() => assert.equal(within(card).getByTestId('match-status').textContent, '예약됨'));
  const before = f.details().length;
  current = { ...current, status: 'IN_PROGRESS', startedAt: '2026-11-19T13:30:05+09:00', endedAt: '2026-11-19T16:30:05+09:00' };
  await act(async () => { advance(15_000); });
  await waitFor(() => assert.equal(within(card).getByTestId('match-status').textContent, '진행 중'));
  await waitFor(() => assert.ok(f.details().length > before, '서버 예약 시작 후 연합 상세도 다시 읽는다'));
  current = { ...current, status: 'FINISHED', aScore: 12, bScore: 10, winnerAllianceId: 7 };
  await act(async () => { advance(15_000); });
  await waitFor(() => assert.equal(within(card).getByTestId('match-status').textContent, '종료'));
  const count = f.routes('GET /api/v1/alliances/matches/').length;
  await act(async () => { advance(30_000); });
  assert.equal(f.routes('GET /api/v1/alliances/matches/').length, count);
});

test('예약 시각이 지나도 서버가 예약됨이면 시작 상태를 만들지 않고, 서버 취소도 표시한다', async () => {
  let current = matchOf(50, { status: 'SCHEDULED', scheduledStartAt: '2026-11-19T11:00:00+09:00' });
  mount({ mine: () => ok(alliance(7)), store: fakeStorage({ 'sour.alliance.match.v1:5': '50' }), match: () => ok(current) });
  const card = await matchCard();
  await waitFor(() => assert.equal(field(card, '시작까지'), '시작 시각이 지났어요. 서버 상태를 확인하는 중이에요.'));
  assert.equal(within(card).getByTestId('match-status').textContent, '예약됨');
  current = { ...current, status: 'CANCELED' };
  fireEvent.click(within(card).getByRole('button', { name: '새로고침' }));
  await waitFor(() => assert.equal(within(card).getByTestId('match-status').textContent, '취소됨'));
  assert.match(card.textContent ?? '', /예약 시작 조건 불충족/);
});

test('신청한 연합은 취소, 받은 연합은 거절할 수 있고 둘 다 취소됨으로 보인다', async () => {
  for (const [mine, label] of [[7, '신청 취소'], [8, '거절']] as const) {
    const f = mount({ mine: () => ok(alliance(mine)), store: fakeStorage({ 'sour.alliance.match.v1:5': '50' }) });
    const card = await matchCard();
    await within(card).findByRole('button', { name: label });
    confirmMatch(card, label);
    await waitFor(() => assert.equal(within(card).getByTestId('match-status').textContent, '취소됨'));
    within(card).getByText('신청 취소, 거절, 수락 마감 경과 또는 예약 시작 조건 불충족으로 끝난 대결이에요.');
    assert.deepEqual(f.routes('PATCH'), ['PATCH /api/v1/alliances/matches/50/cancel']);
    cleanup();
  }
});

test('대결 신청 오류는 코드마다 알리고 기억하지 않는다', async () => {
  const cases: Array<[number, string, RegExp]> = [
    [409, 'MATCH_MEMBERS_INCOMPLETE', /두 연합 모두 학교 2곳이 가입해야/],
    [409, 'MATCH_ALREADY_REQUESTED', /수락을 기다리는 대결 신청이 이미 있어요/],
    [409, 'MATCH_INSUFFICIENT_TIME', /대결 시간을 줄여 보세요/],
    [409, 'ALLY_NOT_ACTIVE', /이미 대결 중이거나 만료된 연합/],
    [404, 'ALLY_NOT_FOUND', /상대 연합을 찾을 수 없어요/],
    [403, 'ALLY_NOT_MEMBER', /소속이 아니에요/],
    [400, 'COMMON_INVALID_INPUT', /^대결을 신청하지 못했어요\. 서버 연합 오류$/],
  ];
  let index = 0;
  const f = mount({ mine: () => ok(alliance(7)), matchRequest: () => fail(cases[index][0], cases[index][1]) });
  const form = await openMatchRequest();
  for (; index < cases.length; index++) {
    fireEvent.change(within(form).getByLabelText('상대 연합 ID'), { target: { value: '8' } });
    fireEvent.click(within(form).getByRole('button', { name: '대결 신청하기' }));
    await waitFor(() => assert.match(within(form).getByTestId('match-request-error').textContent ?? '', cases[index][2]));
  }
  assert.equal(f.routes('POST /api/v1/alliances/7/matches').length, cases.length, '누를 때마다 한 번');
  assert.equal(f.store.data.has('sour.alliance.match.v1:5'), false);
});

test('대결 처리 오류는 코드마다 알리고 카드는 그대로 둔다', async () => {
  const cases: Array<[number, string, RegExp]> = [
    [409, 'MATCH_NOT_PENDING', /^대결 #50은\(는\) 이미 수락·취소됐거나 마감됐어요/],
    [409, 'MATCH_REQUEST_EXPIRED', /수락 마감 시각이 지났어요/],
    [403, 'ALLY_NOT_MEMBER', /수락은 신청받은 연합의 회원만/],
    [409, 'MATCH_SCHEDULE_CONFLICT', /예약된 대결이 있어요/],
    [400, 'MATCH_INVALID_SCHEDULE', /시작 시각과 대결 시간을 다시 확인/],
  ];
  let index = 0;
  mount({ mine: () => ok(alliance(8)), store: fakeStorage({ 'sour.alliance.match.v1:5': '50' }), matchDecision: () => fail(cases[index][0], cases[index][1]) });
  const card = await matchCard();
  await within(card).findByRole('button', { name: '수락' });
  for (; index < cases.length; index++) {
    confirmMatch(card, '수락');
    await waitFor(() => assert.match(within(card).getByTestId('match-decision-error').textContent ?? '', cases[index][2]));
    assert.equal(within(card).getByTestId('match-status').textContent, '수락 대기');
  }
});

test('종료된 대결은 점수와 승패·무승부를, 남의 대결은 보기만, 없어진 대결은 기억을 지운다', async () => {
  const finished = (winner: number | null) => matchOf(50, { status: 'FINISHED', winnerAllianceId: winner, aScore: 1200, bScore: 980,
    startedAt: '2026-11-18T09:00:00+09:00', endedAt: '2026-11-18T12:00:00+09:00' });
  for (const [mine, winner, label] of [[7, 7, '우리 연합 승리'], [8, 7, '우리 연합 패배'], [7, null, '무승부']] as const) {
    mount({ mine: () => ok(alliance(mine)), store: fakeStorage({ 'sour.alliance.match.v1:5': '50' }), match: () => ok(finished(winner)) });
    const card = await matchCard();
    await waitFor(() => assert.equal(within(card).getByTestId('match-result').textContent, label));
    assert.equal(field(card, '점수'), '1,200 : 980');
    assert.match(field(card, '종료') ?? '', /\(KST\)$/);
    cleanup();
  }

  mount({ mine: () => ok(alliance(9)), store: fakeStorage({ 'sour.alliance.match.v1:5': '50' }) });
  const other = await matchCard();
  await within(other).findByText('우리 연합(#9)의 대결이 아니에요. 보기만 할 수 있어요.');
  assertAbsent(within(other).queryByRole('button', { name: '신청 취소' }));
  assertAbsent(within(other).queryByRole('button', { name: '수락' }));
  cleanup();

  const gone = mount({ mine: () => ok(alliance(7)), store: fakeStorage({ 'sour.alliance.match.v1:5': '51' }), match: () => fail(404, 'MATCH_NOT_FOUND', '대결을 찾을 수 없습니다.') });
  await openAllianceTab('대결');
  await screen.findByText('기억해 둔 대결 #51을(를) 찾을 수 없어 지웠어요.');
  assert.equal(gone.store.data.has('sour.alliance.match.v1:5'), false);
  assertAbsent(screen.queryByTestId('match-card'));
});

test('우리 연합이 없으면 대결 영역을 보이지 않는다', async () => {
  mount();
  await screen.findByRole('form', { name: '연합 만들기' });
  const panel = await openAllianceTab('대결');
  assertAbsent(within(panel).queryByRole('form', { name: '대결 신청' }));
  fireEvent.click(within(panel).getByRole('button', { name: '연합 찾기로 이동' }));
  assertSameNode(document.activeElement, screen.getByRole('tab', { name: '연합 찾기', selected: true }));
  screen.getByRole('tabpanel', { name: '연합 찾기' });
});

test('입력 검사와 남은 시간 계산', () => {
  assert.deepEqual(validateAllianceName(' a '), { name: 'a' });
  assert.deepEqual(parseAllianceId(' 12 '), { allianceId: 12 });
  assert.deepEqual(parseApplicationId(' 31 '), { applicationId: 31 });
  assert.deepEqual(parseMatchId('50'), { matchId: 50 });
  assert.ok('problem' in parseMatchId('x'));
  assert.equal(timeLeft('2026-11-19T13:05:00+09:00', NOW), '1시간 5분');
  assert.equal(timeLeft('2026-11-19T11:00:00+09:00', NOW), null);
  assert.ok('problem' in parseApplicationId('0'));
  for (const value of ['', '-1', '1.5', '1e3', '9007199254740993']) assert.ok('problem' in parseAllianceId(value), value);
  assert.equal(remainingTime('2026-11-19T12:30:00+09:00', NOW), '만료까지 0시간 30분');
  assert.equal(remainingTime('2026-11-19T11:00:00+09:00', NOW), '만료 시각이 지났어요');
});

test('계약 센서: 연합 생성·상세 계약이 바뀌면 화면을 다시 확인한다', () => {
  const main = new URL('../../src/main/java/com/back/nbe1214finalsour/', import.meta.url);
  const read = (path: string) => readFileSync(new URL(path, main), 'utf8');
  const fix = 'src/api/alliances.ts와 ApiAllianceScreenPhone을 백엔드 계약에 맞춰 고친 뒤 이 센서를 갱신하세요.';
  const controller = read('domain/alliance/controller/AllianceController.java');
  assert.match(controller, /@RequestMapping\("\/api\/v1\/alliances"\)/, fix);
  assert.match(controller, /@GetMapping\("\/\{allianceId\}"\)/, fix);
  assert.match(controller, /@PostMapping\s*\n\s*@ResponseStatus\(HttpStatus\.CREATED\)\s*\n\s*public ApiResponse<AllianceResponse> createAlliance/, fix);
  assert.match(read('domain/alliance/dto/AllianceCreateRequest.java'),
    /@NotBlank @Size\(max = 50\) String name,\s*@NotNull @Min\(6\) @Max\(72\) Integer durationHours/, fix);
  assert.match(read('domain/alliance/dto/AllianceResponse.java'),
    /Long id,\s*String name,\s*Long totalScore,\s*AllianceStatus status,\s*Integer durationHours,\s*OffsetDateTime expiredAt/, fix);
  assert.match(read('domain/alliance/entity/AllianceStatus.java'), /ACTIVE,[\s\S]*IN_MATCH,[\s\S]*EXPIRED\s*\n\s*\}/, fix);
  assert.match(read('domain/alliance/entity/Alliance.java'), /name\.trim\(\)\.length\(\) > 50/, fix);
  assert.match(controller, /@PostMapping\("\/\{allianceId\}\/applications"\)\s*\n\s*@ResponseStatus\(HttpStatus\.CREATED\)/, fix);
  assert.match(controller, /@PatchMapping\("\/applications\/\{applicationId\}\/approve"\)/, fix);
  assert.match(controller, /@PatchMapping\("\/applications\/\{applicationId\}\/reject"\)/, fix);
  assert.match(read('domain/alliance/dto/AllianceApplicationResponse.java'),
    /Long id,\s*Long allianceId,\s*Long universityId,\s*AllianceUniversityStatus status/, fix);
  assert.match(read('domain/alliance/entity/Alliance.java'), /MAX_SCHOOLS = 2;/,
    '정원이 2가 아니면 승인 결과의 "학교 2/2"와 ALLIANCE_MAX_SCHOOLS를 고치세요.');
  const errors = read('global/exception/ErrorCode.java');
  assert.match(errors, /ALLY_UNIVERSITY_ALREADY_JOINED\(HttpStatus\.CONFLICT, "ALLY_UNIVERSITY_ALREADY_JOINED"/, fix);
  assert.match(errors, /ALLY_USER_NOT_ACTIVE\(HttpStatus\.FORBIDDEN, "ALLY_USER_NOT_ACTIVE"/, fix);
  assert.match(errors, /ALLY_NOT_FOUND\(HttpStatus\.NOT_FOUND, "ALLY_NOT_FOUND"/, fix);
  for (const [code, status] of [['ALLY_NOT_ACTIVE', 'CONFLICT'], ['ALLY_FULL', 'CONFLICT'], ['ALLY_APPLICATION_PENDING', 'CONFLICT'],
    ['ALLY_NOT_MEMBER', 'FORBIDDEN'], ['ALLY_APPLICATION_NOT_FOUND', 'NOT_FOUND'], ['ALLY_APPLICATION_NOT_PENDING', 'CONFLICT']]) {
    assert.match(errors, new RegExp(`${code}\\(HttpStatus\\.${status}, "${code}"`), fix);
  }
  assert.match(controller, /@GetMapping\("\/me"\)/, fix);
  const lists = read('domain/alliance/controller/AllianceListController.java');
  assert.match(lists, /@GetMapping\s*\n\s*public ApiResponse<Page<Summary>> directory/, fix);
  for (const path of ['/{allianceId}/universities', '/{allianceId}/applications', '/applications/me', '/{allianceId}/matches', '/matches/{matchId}']) {
    assert.ok(lists.includes(`@GetMapping("${path}")`), `${fix} Missing ${path}`);
  }

  const flow = readFileSync(new URL('../src/components/GameAllianceFlow.tsx', import.meta.url), 'utf8');
  assert.match(flow, /if \(API_MODE === 'api'\) return <ApiAllianceScreenPhone[^;]+;\s*switch \(screen\)/,
    'API 모드의 연합·대결 화면은 ApiAllianceScreenPhone이다. 목업 연합·대결 화면([시연] 버튼·가상 시계)은 목업 모드 전용이다.');
  for (const file of ['App.tsx', 'components/GameCompetitionPanel.tsx']) {
    const source = readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8');
    assert.match(source, /<GameAllianceFlow\b/, '단일 화면과 3열 패널 모두 공통 연합 진입점을 사용해야 합니다.');
  }

  const matches = read('domain/alliance/controller/AllianceMatchController.java');
  assert.match(matches, /@RequestMapping\("\/api\/v1\/alliances"\)/, fix);
  assert.match(matches, /@PostMapping\("\/\{allianceId\}\/matches"\)\s*\n\s*@ResponseStatus\(HttpStatus\.CREATED\)/, fix);
  for (const path of ['accept', 'cancel']) assert.match(matches, new RegExp(`@PatchMapping\\("/matches/\\{matchId\\}/${path}"\\)`), fix);
  assert.doesNotMatch(matches, /@GetMapping/, '대결 조회는 목록과 같은 접근 정책을 적용하는 AllianceListController에 있어야 합니다.');
  assert.match(read('domain/alliance/dto/AllianceMatchCreateRequest.java'),
    /@NotNull Long opponentAllianceId,\s*@Min\(1\) @Max\(24\) int durationHours,\s*OffsetDateTime scheduledStartAt/, fix);
  assert.match(read('domain/alliance/dto/AllianceMatchResponse.java'), new RegExp([
    'Long id, Long allianceAId, Long allianceBId, Long winnerAllianceId,', 'AllianceMatchStatus status, Integer durationHours,',
    'OffsetDateTime requestExpiredAt, OffsetDateTime scheduledStartAt,', 'OffsetDateTime startedAt, OffsetDateTime endedAt,',
    'Long aScore, Long bScore'].join('\\s*')), fix);
  assert.match(read('domain/alliance/entity/AllianceMatchStatus.java'), /REQUESTED,\s*SCHEDULED,\s*IN_PROGRESS,\s*FINISHED,\s*CANCELED\s*\}/, fix);
  for (const [code, status] of [['MATCH_ALREADY_REQUESTED', 'CONFLICT'], ['MATCH_NOT_FOUND', 'NOT_FOUND'], ['MATCH_NOT_PENDING', 'CONFLICT'],
    ['MATCH_REQUEST_EXPIRED', 'CONFLICT'], ['MATCH_MEMBERS_INCOMPLETE', 'CONFLICT'], ['MATCH_SCHEDULE_CONFLICT', 'CONFLICT'],
    ['MATCH_INSUFFICIENT_TIME', 'CONFLICT']]) {
    assert.match(errors, new RegExp(`${code}\\(HttpStatus\\.${status}, "${code}"`), fix);
  }
});

test('연합 목록에서 추가 페이지를 불러오고 번호 입력 없이 가입 신청한다', async () => {
  const summary = (id: number, name: string) => ({ ...alliance(id, { name }), memberCount: 1 });
  const h = mount({ list(url) {
    if (url.pathname !== '/api/v1/alliances') return undefined;
    return url.searchParams.has('cursor') ? ok({ items: [summary(7, '다음 연합')], nextCursor: null })
      : ok({ items: [summary(8, '첫 연합')], nextCursor: 8 });
  } });
  await openAllianceTab('연합 찾기');
  await screen.findByRole('button', { name: '첫 연합 상세 보기' });
  fireEvent.click(screen.getByRole('button', { name: '연합 목록 더 보기' }));
  fireEvent.click(await screen.findByRole('button', { name: '다음 연합 상세 보기' }));
  assertSameNode(document.activeElement, screen.getByRole('heading', { name: '연합 #7 상세' }));
  fireEvent.click(await screen.findByRole('button', { name: '이 연합에 가입 신청' }));
  await waitFor(() => assert.deepEqual(h.routes('POST /api/v1/alliances/7/applications'), ['POST /api/v1/alliances/7/applications']));
});

test('받은 신청 목록은 번호 없이 승인되고 권한 오류가 나면 이전 신청을 숨긴다', async () => {
  let approved = false;
  mount({ mine: () => ok(alliance(7)), decide: () => {
    approved = true;
    return ok(application(31, 7, 'ACCEPTED'));
  }, list(url) {
    if (!url.pathname.endsWith('/7/applications')) return undefined;
    return approved ? fail(403, 'ALLY_NOT_MEMBER', '소속 변경') : ok({ items: [{ id: 31,
      alliance: { id: 7, name: '우리 연합' }, university: { id: YONSEI, name: '연세대학교', logoUrl: null },
      status: 'PENDING', createdAt: '2026-11-19T10:00:00+09:00' }], nextCursor: null });
  } });
  await openApplicationDecision();
  fireEvent.click(await screen.findByRole('button', { name: '연세대학교 가입 신청 승인' }));
  confirmAction('가입 승인');
  const inbox = screen.getByRole('region', { name: '받은 가입 신청' });
  await within(inbox).findByRole('alert');
  assertAbsent(within(inbox).queryByRole('button', { name: '연세대학교 가입 신청 승인' }));
});

test('내 학교의 현재 연합은 /alliances/me로 읽고, 없으면 null, 형식이 틀리면 거절한다', async () => {
  const calls: string[] = [];
  const reply = (data: unknown) => createApiClient({ fetch: async (url) => { calls.push(String(url)); return ok(data); } });
  assert.deepEqual(await getMyAlliance(reply(alliance(9))), alliance(9));
  assert.equal(calls[0], '/api/v1/alliances/me');
  assert.equal(await getMyAlliance(reply(null)), null);
  for (const data of [{}, alliance(0), { ...alliance(9), id: '9' }, alliance(9, { expiredAt: 'bad' }),
    alliance(9, { status: 'EXPIRED' }), alliance(9, { totalScore: undefined }), alliance(9, { durationHours: 0 }), 'alliance']) {
    await assert.rejects(getMyAlliance(reply(data)), /서버 응답을 읽지/);
  }
});
