// SCRUM-161: ranking pages from the API, joined by scrolling, with the viewer's own row.
// SCRUM-184: the server's rankChange (against 00:00 KST) becomes the ▲▼ badge, and the main HUD reads myUniversity.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import type { MyInfo } from '../src/api/users';
import type { DepartmentRankingPage, UniversityRankingPage, UserRankingPage } from '../src/api/rankings';
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

const { render, screen, act, cleanup, waitFor, fireEvent } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { userKeys } = await import('../src/api/users');
const { RANKING_PAGE_SIZE, formatRankChange } = await import('../src/api/rankings');
const { useApiMySchoolRank } = await import('../src/hooks/useMySchoolRank');
const { MockGameProvider } = await import('../src/hooks/useMockGame');
const { useApiRankingBoard, toApiRows, toApiMine } = await import('../src/hooks/useRankingBoard');
const { RANK_TREND_TONE, RankingStatusNotice, SIGNED_OUT_RANKING_NOTICE, rankTrend, rankingCaption } = await import('../src/components/RankingParts');
const { RankingScreenPhone } = await import('../src/components/RankingScreenPhone');
const { RankingScreen1Phone } = await import('../src/components/RankingScreen1Phone');
const { RankingScreen2Phone } = await import('../src/components/RankingScreen2Phone');
const { INITIAL_GAME_STATE } = await import('../src/utils/gameModel');
const { DEFAULT_PLAYER } = await import('../src/data/gameRules');
const { getSchoolRankings } = await import('../src/data/mockRankings');
const { TopHUD } = await import('../src/components/TopHUD');
const clients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => { cleanup(); for (const client of clients.splice(0)) client.clear(); });
after(() => { assets.deregister(); dom.window.close(); });

const ME: MyInfo = {
  id: 10, nickname: '순위학생', email: 'rank@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: 1, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 }, totalContribScore: 30, totalAttackScore: 0,
};
const school = (rank: number, universityId: number, name: string, totalScore: number, rankChange: number | null = null) => ({ rank, universityId, name, logoUrl: null, totalScore, rankChange });
const SCHOOL_PAGES: UniversityRankingPage[] = [
  { items: [school(1, 4, '서버1등대', 900, 2), school(2, 9, '서버2등대', 800, -1), school(2, 12, '공동2등대', 800, 0)], page: 0, size: RANKING_PAGE_SIZE,
    totalCount: 5, hasNext: true, myUniversity: { rank: 5, universityId: 1, totalScore: 100, rankChange: -3 } },
  { items: [school(4, 30, '서버4등대', 300), school(5, 1, '한양대학교', 100, -3)], page: 1, size: RANKING_PAGE_SIZE,
    totalCount: 5, hasNext: false, myUniversity: { rank: 5, universityId: 1, totalScore: 100, rankChange: -3 } },
];
const DEPT_PAGE: DepartmentRankingPage = {
  items: [{ rank: 1, departmentId: 7, name: '컴퓨터소프트웨어학부', logoUrl: null, totalScore: 50, rankChange: 1 }, { rank: 2, departmentId: 8, name: '서버학과', logoUrl: null, totalScore: 20, rankChange: -1 }],
  page: 0, size: RANKING_PAGE_SIZE, totalCount: 2, hasNext: false, myDepartment: { rank: 1, departmentId: 7, totalScore: 50, rankChange: 1 },
};
const USER_PAGE: UserRankingPage = {
  items: [{ rank: 1, userId: 99, nickname: '다른학생', profileImageUrl: null, department: { id: 8, name: '서버학과' }, contribScore: 70, rankChange: null }],
  page: 0, size: RANKING_PAGE_SIZE, totalCount: 2, hasNext: true, me: { rank: 2, contribScore: 30, rankChange: 4 },
};
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status: 200 });
const fail = (status: number, code: string, message: string) => new Response(JSON.stringify({ code, message, data: null }), { status });

let board: ReturnType<typeof useApiRankingBoard>;
function Probe({ tab }: { tab: 'school' | 'dept' | 'personal' }) {
  board = useApiRankingBoard(tab, 0);
  return React.createElement(React.Fragment, null,
    React.createElement(RankingStatusNotice, { board }),
    React.createElement('ol', { 'aria-label': '순위' }, board.rows.map((row) => React.createElement('li', { key: row.key }, `${row.rank} ${row.name}${row.isMine ? ' (내 소속)' : ''}`))),
    board.mine && React.createElement('output', { 'aria-label': '내 순위' }, `${board.mine.rank} ${board.mine.name} ${board.mine.score}`),
  );
}
interface Call { path: string; auth: string | null }
function mount(tab: 'school' | 'dept' | 'personal', { signedIn = true, respond }: { signedIn?: boolean; respond?: (url: URL) => Response | undefined } = {}) {
  const calls: Call[] = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    calls.push({ path: `${url.pathname}${url.search}`, auth: new Headers(init?.headers).get('Authorization') });
    const custom = respond?.(url);
    if (custom) return custom;
    const page = Number(url.searchParams.get('page'));
    if (url.pathname.endsWith('/rankings/universities')) return ok(SCHOOL_PAGES[page]);
    if (url.pathname.endsWith('/rankings/universities/1/departments')) return ok(DEPT_PAGE);
    if (url.pathname.endsWith('/rankings/universities/1/users')) return ok(USER_PAGE);
    if (url.pathname.endsWith('/users/me')) return ok(ME);
    if (url.pathname.endsWith('/auth/reissue')) return fail(401, 'AUTH_INVALID_REFRESH_TOKEN', '다시 로그인해 주세요.');
    throw new Error(`Unexpected path: ${url.pathname}`);
  }) as typeof fetch });
  const queryClient = createQueryClient(); clients.push(queryClient);
  if (signedIn) { client.session.signIn('member-a'); queryClient.setQueryData(userKeys.me, ME); }
  render(React.createElement(ApiProvider, { client, queryClient, children: React.createElement(Probe, { tab }) }));
  return { calls, rankingCalls: () => calls.filter((call) => call.path.includes('/rankings/')) };
}
const listed = () => screen.getAllByRole('listitem').map((item) => item.textContent);

test('학교 순위는 20개 페이지를 스크롤로 이어 붙이고, 내 학교는 그 페이지 전에도 고정 행으로 보인다', async () => {
  const f = mount('school');
  await waitFor(() => assert.equal(board.status, 'ready'));
  // Ties share the server's rank; my school's page is not loaded yet, so its row comes from myUniversity.
  assert.deepEqual(listed(), ['1 서버1등대', '2 서버2등대', '2 공동2등대']);
  assert.equal(screen.getByRole('status', { name: '내 순위' }).textContent, '5 한양대학교 100');
  assert.equal(board.hasMore, true);
  assert.deepEqual(f.rankingCalls(), [{ path: `/api/v1/rankings/universities?page=0&size=${RANKING_PAGE_SIZE}`, auth: 'Bearer member-a' }]);

  await act(async () => { board.loadMore(); board.loadMore(); });
  await waitFor(() => assert.equal(board.rows.length, 5));
  assert.deepEqual(listed().slice(3), ['4 서버4등대', '5 한양대학교 (내 소속)']);
  assert.equal(board.hasMore, false);
  // The second loadMore while the first was in flight sent nothing.
  assert.deepEqual(f.rankingCalls().map((call) => call.path), [
    `/api/v1/rankings/universities?page=0&size=${RANKING_PAGE_SIZE}`, `/api/v1/rankings/universities?page=1&size=${RANKING_PAGE_SIZE}`]);
  // ▲ up, ▼ down, - unchanged, null (nothing to compare) hides the badge. My pinned row uses myUniversity.rankChange.
  assert.deepEqual(board.rows.map((row) => row.change), ['▲2', '▼1', '-', null, '▼3']);
  assert.equal(board.mine?.change, '▼3');
});

test('학과·개인 순위는 내 학교 ID로 조회하고 내 학과·본인 순위를 표시한다', async () => {
  const dept = mount('dept');
  await waitFor(() => assert.equal(board.status, 'ready'));
  assert.deepEqual(listed(), ['1 컴퓨터소프트웨어학부 (내 소속)', '2 서버학과']);
  assert.equal(screen.getByRole('status', { name: '내 순위' }).textContent, '1 컴퓨터소프트웨어학부 50');
  assert.match(dept.rankingCalls()[0].path, /^\/api\/v1\/rankings\/universities\/1\/departments\?page=0/);
  assert.deepEqual([board.rows.map((row) => row.change), board.mine?.change], [['▲1', '▼1'], '▲1']);
  cleanup();

  const personal = mount('personal');
  await waitFor(() => assert.equal(board.status, 'ready'));
  assert.deepEqual(listed(), ['1 다른학생']);
  // Not on the loaded page: built from `me` and the profile.
  assert.equal(screen.getByRole('status', { name: '내 순위' }).textContent, '2 순위학생 30');
  assert.equal(board.mine?.subtitle, '한양대학교 · 컴퓨터소프트웨어학부');
  assert.deepEqual([board.rows[0].change, board.mine?.change], [null, '▲4']);
  assert.match(personal.rankingCalls()[0].path, /^\/api\/v1\/rankings\/universities\/1\/users\?page=0/);
});

test('로그인 전에는 학교 순위만 익명으로 조회하고 학과·개인 탭은 안내만 보인다', async () => {
  const guest = mount('school', { signedIn: false });
  await waitFor(() => assert.equal(board.status, 'ready'));
  assert.equal(guest.rankingCalls()[0].auth, null);
  assertAbsent(screen.queryByRole('status', { name: '내 순위' }));
  assert.ok(board.rows.every((row) => !row.isMine));
  cleanup();

  const scoped = mount('personal', { signedIn: false });
  assert.equal(board.status, 'signed-out');
  assert.ok(screen.getByText(SIGNED_OUT_RANKING_NOTICE));
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.deepEqual(scoped.rankingCalls(), []);
});

test('조회 실패는 서버 문구로 안내하고 다시 시도하면 목록을 보인다', async () => {
  let broken = true;
  const f = mount('school', { respond: (url) => (broken && url.pathname.endsWith('/rankings/universities')
    ? fail(400, 'COMMON_INVALID_INPUT', '잘못된 순위 요청입니다.') : undefined) });
  await waitFor(() => assert.equal(board.status, 'failed'));
  assert.ok(screen.getByText('잘못된 순위 요청입니다.'));
  broken = false;
  fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
  await waitFor(() => assert.equal(board.status, 'ready'));
  assert.equal(listed()[0], '1 서버1등대');
  assert.equal(f.rankingCalls().length, 2);
});

test('다시 조회로 순위가 페이지 경계를 넘어가도 같은 행은 한 번만 보인다', () => {
  // A refetch moved 공동2등대 from page 0 to the top of page 1.
  const moved: UniversityRankingPage[] = [
    { ...SCHOOL_PAGES[0], items: SCHOOL_PAGES[0].items.slice(0, 2).concat(school(3, 30, '서버4등대', 850)) },
    { ...SCHOOL_PAGES[1], items: [school(3, 30, '서버4등대', 850), school(4, 12, '공동2등대', 800)] },
  ];
  const rows = toApiRows('school', moved, ME);
  assert.deepEqual(rows.map((row) => row.key), ['school:4', 'school:9', 'school:30', 'school:12']);
  assert.equal(toApiMine('school', moved, rows, undefined), null, '로그인 전에는 내 행이 없다');
  assert.equal(toApiMine('personal', [{ ...USER_PAGE, me: null }], [], ME), null, '비공개 회원은 서버가 me를 비운다');
});

test('목업 모드 순위 화면은 기존 표본 문구와 10개 단위 더보기를 유지한다', async () => {
  render(React.createElement(MockGameProvider, null, React.createElement(RankingScreenPhone, { currentSchoolScore: 0, onNavigateTab: () => undefined })));
  assert.ok(screen.getByText(/전국 학교 · 목업 샘플/));
  assert.ok(screen.getByText('15 / 76개교'));
  fireEvent.click(screen.getByRole('button', { name: /아래로 스크롤하여 10개교 더 보기/ }));
  assert.ok(screen.getByText(/순위 데이터를 더 불러오는 중… \(15 \/ 76\)/));
  await waitFor(() => assert.ok(screen.getByText('25 / 76개교')), { timeout: 2000 });
  fireEvent.click(screen.getByRole('button', { name: '교내 학과' }));
  assertAbsent(screen.queryByRole('button', { name: /아래로 스크롤하여 더 보기/ }));
  assert.deepEqual(rankingCaption('dept', { isSample: false }, '한양대'), ['한양대 교내 학과', '점수 내림차순 · 동점은 같은 순위']);
});

test('Java 계약 센서: 순위 DTO 필드와 경로가 바뀌면 api/rankings.ts와 useRankingBoard를 재검토한다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/university/', import.meta.url);
  const records = (file: string) => Object.fromEntries([...readFileSync(new URL(`dto/${file}.java`, root), 'utf8')
    .matchAll(/record (\w+)\(([^)]*)\)/g)].map(([, name, body]) => [name, body.split(',').map((part) => part.trim().split(/\s+/).at(-1))]));
  const fix = 'api/rankings.ts의 DTO 타입과 useRankingBoard의 행 변환을 Java 계약과 맞추세요';
  const page = ['items', 'page', 'size', 'totalCount', 'hasNext'];
  const university = records('UniversityRankingResponse');
  assert.deepEqual(university.UniversityRankingResponse, Object.keys(SCHOOL_PAGES[0]), fix);
  assert.deepEqual(university.Item, Object.keys(SCHOOL_PAGES[0].items[0]), fix);
  assert.deepEqual(university.MyUniversity, Object.keys(SCHOOL_PAGES[0].myUniversity!), fix);
  const department = records('DepartmentRankingResponse');
  assert.deepEqual(department.DepartmentRankingResponse, [...page, 'myDepartment'], fix);
  assert.deepEqual(department.Item, Object.keys(DEPT_PAGE.items[0]), fix);
  assert.deepEqual(department.MyDepartment, Object.keys(DEPT_PAGE.myDepartment!), fix);
  const user = records('UserRankingResponse');
  assert.deepEqual(user.UserRankingResponse, [...page, 'me'], fix);
  assert.deepEqual(user.Item, Object.keys(USER_PAGE.items[0]), fix);
  assert.deepEqual(user.DepartmentInfo, ['id', 'name'], fix);
  assert.deepEqual(user.Me, Object.keys(USER_PAGE.me!), fix);
  const controller = readFileSync(new URL('controller/RankingController.java', root), 'utf8');
  assert.match(controller, /@RequestMapping\("\/api\/v1\/rankings"\)/, fix);
  for (const path of ['/universities', '/universities/{universityId}/departments', '/universities/{universityId}/users']) {
    assert.ok(controller.includes(`@GetMapping("${path}")`), `${fix}: ${path}`);
  }
  assert.match(controller, /defaultValue = "20"\) @Min\(1\) @Max\(100\) int size/, fix);
});

test('연결 센서: 두 순위 화면은 목업 순위를 직접 읽지 않고 useRankingBoard를 쓴다', () => {
  for (const file of ['RankingScreenPhone.tsx', 'RankingsView.tsx']) {
    const source = readFileSync(new URL(`../src/components/${file}`, import.meta.url), 'utf8');
    assert.match(source, /useRankingBoard\(/, `${file}: 순위 데이터는 useRankingBoard에서 받으세요`);
    assert.doesNotMatch(source, /getMockRankings/, `${file}: 목업 순위는 useRankingBoard의 목업 구현만 읽습니다`);
  }
});

test('순위 변동 표기: 양수 ▲, 음수 ▼, 0은 -, 비교할 순위가 없으면 배지를 숨긴다', () => {
  assert.deepEqual([3, -2, 0, null, undefined].map(formatRankChange), ['▲3', '▼2', '-', null, null]);
});

let hud: ReturnType<typeof useApiMySchoolRank>;
function HudProbe() {
  hud = useApiMySchoolRank(123_456, 1);
  return React.createElement('output', { 'aria-label': '상단 순위' }, `${hud.rank ?? '순위 없음'} ${hud.change ?? ''}`.trim());
}
function mountHud(signedIn: boolean) {
  const calls: string[] = [];
  const client = createApiClient({ fetch: (async (input) => {
    const url = new URL(String(input), 'http://localhost');
    calls.push(`${url.pathname}${url.search}`);
    if (url.pathname.endsWith('/rankings/universities')) return ok({ ...SCHOOL_PAGES[0], items: SCHOOL_PAGES[0].items.slice(0, 1), size: 1 });
    if (url.pathname.endsWith('/users/me')) return ok(ME);
    throw new Error(`Unexpected path: ${url.pathname}`);
  }) as typeof fetch });
  const queryClient = createQueryClient(); clients.push(queryClient);
  if (signedIn) { client.session.signIn('member-a'); queryClient.setQueryData(userKeys.me, ME); }
  render(React.createElement(ApiProvider, { client, queryClient, children: React.createElement(HudProbe) }));
  return calls;
}

test('메인 상단 순위는 API 모드에서 게임 점수가 아니라 서버 myUniversity의 순위와 변동을 쓴다', async () => {
  const calls = mountHud(true);
  await waitFor(() => assert.equal(screen.getByRole('status', { name: '상단 순위' }).textContent, '5 ▼3'));
  // One row is enough: the server adds myUniversity to any page.
  assert.deepEqual(calls, ['/api/v1/rankings/universities?page=0&size=1']);
  cleanup();

  const guestCalls = mountHud(false);
  assert.equal(screen.getByRole('status', { name: '상단 순위' }).textContent, '순위 없음');
  assert.deepEqual([hud.rank, hud.change, guestCalls], [null, null, []]);
});

test('연결 센서: 메인 상단 순위는 useMySchoolRank에서 받고 App이 목업 순위를 직접 계산하지 않는다', () => {
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(app, /useMySchoolRank\(/, 'App.tsx: 상단 순위·변동은 useMySchoolRank에서 받으세요 (API 모드는 서버 값)');
  assert.doesNotMatch(app, /getSchoolRankings/, 'App.tsx: 목업 순위 계산은 useMySchoolRank의 목업 구현에만 둡니다');
});

test('메인 상단 변동 배지는 하락을 상승과 다른 색으로 보인다 (서버 값은 ▼도 온다)', () => {
  const hudFor = (rankDelta: string | null) => {
    cleanup();
    render(React.createElement(MockGameProvider, null, React.createElement(TopHUD, { schoolName: '한양대학교', schoolId: 1, schoolBadgeColor: '#000',
      schoolRegion: '서울 · 수도권', schoolScore: 0, rank: 2, rankDelta, campusLevel: 1, soundEnabled: false, onToggleSound: () => undefined })));
    return screen.queryByTestId('rank-change');
  };
  const trends = ['▲2', '▼1', '-'].map((delta) => {
    const badge = hudFor(delta)!;
    return [badge.textContent, badge.dataset.trend, badge.className.match(/bg-\[#\w+\]/)?.[0]];
  });
  assert.deepEqual(trends, [['▲2', 'up', 'bg-[#22c55e]'], ['▼1', 'down', 'bg-[#fb7185]'], ['-', 'same', 'bg-[#faecc7]']]);
  assert.equal(hudFor(null), null);
});

test('색 센서: 순위 변동 배지는 고정 초록 대신 rankTrend 색을 쓴다 (▼가 상승처럼 보이지 않게)', () => {
  const MOCK_SCREENS = ['RankingScreen1Phone.tsx', 'RankingScreen2Phone.tsx'];
  for (const file of ['TopHUD.tsx', 'RankingsView.tsx', 'RankingScreenPhone.tsx', ...MOCK_SCREENS]) {
    const source = readFileSync(new URL(`../src/components/${file}`, import.meta.url), 'utf8');
    // Every element that prints a change value must pick its tone from the trend.
    for (const [, block] of source.matchAll(/<span([^>]*)>\s*\{(?:rankDelta|myRow\.change|myItem\.change|mySchool\.change)\}/g)) {
      assert.doesNotMatch(block, /bg-\[#22c55e\]/, `${file}: 변동 배지에 RANK_TREND_TONE[rankTrend(...)]을 쓰세요`);
      assert.match(block, /RANK_TREND_TONE\[rankTrend\(/, `${file}: 변동 배지에 RANK_TREND_TONE[rankTrend(...)]을 쓰세요`);
    }
    // SCRUM-190: the comparison mocks once hard-coded a green own-row badge and a "▼1" pink cut-off row, and list rows
    // repeated the colors in ternaries. The trend colors live only in RANK_TREND_TONE.
    assert.doesNotMatch(source, /bg-\[#(?:22c55e|fb7185)\]/, `${file}: 변동 배지 색을 직접 쓰지 말고 RANK_TREND_TONE[rankTrend(...)]을 쓰세요`);
  }
});

test('목업 비교 순위 화면의 변동 배지는 실제 변동 방향 색을 쓴다 (SCRUM-190)', () => {
  const schoolId = DEFAULT_PLAYER.schoolId;
  const badgeOf = (element: HTMLElement) => element.lastElementChild!.lastElementChild as HTMLElement;
  const renderMock = (Screen: React.FC, schoolScore: number) =>
    render(React.createElement(MockGameProvider, { initialState: { ...INITIAL_GAME_STATE, schoolScore }, children: React.createElement(Screen) }));

  // Screen 2 pins the player's school: ▼ when its score falls, ▲ when it rises.
  for (const [score, trend] of [[0, 'down'], [Number.MAX_SAFE_INTEGER, 'up']] as const) {
    const change = getSchoolRankings(score, schoolId).find((row) => row.isMySchool)!.change;
    assert.equal(rankTrend(change), trend, `점수 ${score}에서 내 학교 변동이 ${trend}여야 한다`);
    renderMock(RankingScreen2Phone, score);
    assert.equal(screen.getByText(change).className.includes(RANK_TREND_TONE[trend]), true, `${change} 배지 색`);
    cleanup();
  }

  // Screen 1's cut-off 8th row follows its own change instead of a fixed "▼1".
  for (const score of [INITIAL_GAME_STATE.schoolScore, 0]) {
    const change = getSchoolRankings(score, schoolId)[7].change;
    renderMock(RankingScreen1Phone, score);
    const badge = badgeOf(screen.getByTitle('아래로 스크롤하여 더 많은 순위 보기'));
    assert.equal(badge.textContent, change.replace(/[▲▼]/g, '') || '-');
    assert.equal(badge.className.includes(RANK_TREND_TONE[rankTrend(change)]), true, `8위 ${change} 배지 색`);
    cleanup();
  }
});
