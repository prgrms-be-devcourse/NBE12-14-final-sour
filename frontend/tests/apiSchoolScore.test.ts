// SCRUM-188: in API mode the main HUD's school score follows the server's myUniversity.totalScore (the ranking query
// that already feeds the HUD rank) plus unconfirmed clicks, without an older ranking answer replacing a click answer.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import type { ClickResponse } from '../src/api/clicks';
import type { UniversityRankingPage } from '../src/api/rankings';
import type { MyInfo } from '../src/api/users';
import { installDialogStub } from './helpers/dialogStub';

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
const { rankingKeys } = await import('../src/api/rankings');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { useApiCampusClicks } = await import('../src/hooks/useCampusClicks');
const { ApiPlayerSync } = await import('../src/components/ApiPlayerSync');
const { ApiSchoolScoreSync } = await import('../src/components/ApiSchoolScoreSync');
const { initialSchoolScore } = await import('../src/data/gameRules');
const clients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => { cleanup(); for (const client of clients.splice(0)) client.clear(); });
after(() => { assets.deregister(); dom.window.close(); });

const ME: MyInfo = {
  id: 10, nickname: '점수학생', email: 'test@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: 1, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 2, nextLevelRequiredScore: 100 }, totalContribScore: 10, totalAttackScore: 0,
};
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status: 200 });
const clickAnswer = (schoolTotal: number, clickCount: number): ClickResponse => ({
  acceptedClicks: clickCount, earnedScore: clickCount * 2, multiplier: 1, myContribScore: 10 + clickCount * 2,
  university: { id: 1, totalScore: schoolTotal, rank: 3, logoUrl: 'tempUrl' }, department: { id: 7, totalScore: 50 },
  schoolClicksLastSecond: clickCount, libraryBonus: false, criticalIndex: [], damageRateIndex: [],
});
const rankingPage = (totalScore: number | null): UniversityRankingPage => ({
  items: [], page: 0, size: 1, totalCount: 76, hasNext: true,
  myUniversity: totalScore === null ? null : { rank: 3, universityId: 1, totalScore, rankChange: 0 },
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  return { promise: new Promise<T>((done) => { resolve = done; }), resolve };
}

function Probe() {
  const clicks = useApiCampusClicks();
  const game = useMockGame();
  return React.createElement(React.Fragment, null,
    React.createElement(ApiPlayerSync, { getPendingScore: clicks.getPendingScore }),
    React.createElement(ApiSchoolScoreSync, { getPendingScore: clicks.getPendingScore, isSending: clicks.isSending }),
    React.createElement('output', { 'data-testid': 'game' }, JSON.stringify(game.state)),
    React.createElement('button', { onClick: () => clicks.tap(false) }, '클릭'));
}

/** `ranking` answers GET /rankings/universities; `clicks` answers POST /clicks. Both can be held back with a deferred. */
function mount(handlers: {
  ranking: () => Promise<Response> | Response;
  clicks?: (clickCount: number) => Promise<Response> | Response;
  /** #144: the signed-in member and GET /universities/{id} for a school outside the static list. */
  me?: MyInfo;
  detail?: () => Promise<Response> | Response;
}) {
  const sent: number[] = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    const path = String(input);
    if (path.endsWith('/users/me')) return ok(handlers.me ?? ME);
    if (/\/universities\/\d+$/.test(path) && handlers.detail) return handlers.detail();
    if (path.includes('/rankings/universities')) return handlers.ranking();
    if (path.endsWith('/clicks')) {
      const { clickCount } = JSON.parse(String(init?.body)) as { clickCount: number };
      sent.push(clickCount);
      if (!handlers.clicks) throw new Error('Unexpected click');
      return handlers.clicks(clickCount);
    }
    if (path.endsWith('/auth/reissue')) return ok({ accessToken: 'member-a', expiresInSeconds: 3600 });
    throw new Error(`Unexpected request: ${path}`);
  }) as typeof fetch });
  client.session.signIn('member-a');
  const queryClient = createQueryClient();
  clients.push(queryClient);
  render(React.createElement(React.StrictMode, null, React.createElement(ApiProvider, {
    client, queryClient, children: React.createElement(MockGameProvider, { children: React.createElement(Probe) }),
  })));
  // TanStack notifies observers on a later timer tick, so wait past it before reading the game state.
  const refetchRanking = () => act(async () => {
    await queryClient.refetchQueries({ queryKey: rankingKeys.mySchool(ME.id) });
    await settle();
  });
  return { sent, refetchRanking };
}
const schoolScore = () => (JSON.parse(screen.getByTestId('game').textContent ?? '{}') as { schoolScore: number }).schoolScore;
const click = () => fireEvent.click(screen.getByRole('button', { name: '클릭' }));
const settle = () => new Promise((done) => setTimeout(done, 20));

test('로그인 뒤 HUD 학교 점수는 목업 초기값이 아니라 서버 myUniversity.totalScore다', async () => {
  assert.notEqual(initialSchoolScore(1), 5_000, '목업 초기값과 서버 값을 구분할 수 있어야 한다');
  mount({ ranking: () => ok(rankingPage(5_000)) });
  await waitFor(() => assert.equal(schoolScore(), 5_000));
});

test('순위에 내 학교 점수가 아직 없으면(myUniversity null) 목업 초기값 대신 0이다', async () => {
  mount({ ranking: () => ok(rankingPage(null)) });
  await waitFor(() => assert.equal(schoolScore(), 0));
});

test('주기 조회 값에는 아직 보내지 않은 내 클릭 점수를 더하고, 이후 클릭 응답이 다시 보정한다', async () => {
  let server = 5_000;
  const { sent, refetchRanking } = mount({ ranking: () => ok(rankingPage(server)), clicks: (count) => ok(clickAnswer(6_104, count)) });
  await waitFor(() => assert.equal(schoolScore(), 5_000));
  click(); click();
  assert.equal(schoolScore(), 5_004, '낙관 반영');
  server = 6_100; // Other members clicked meanwhile; this member's 4 points are still queued.
  await refetchRanking();
  assert.equal(sent.length, 0, '아직 배치 전송 전이어야 이 경우를 검사한다');
  assert.equal(schoolScore(), 6_104);
  await waitFor(() => assert.equal(sent.length, 1), { timeout: 2500 });
  await waitFor(() => assert.equal(schoolScore(), 6_104));
});

test('클릭 배치를 보내는 동안 받은 순위 값은 반영하지 않는다 (서버가 그 배치를 셌는지 알 수 없다)', async () => {
  let server = 5_000;
  const answer = deferred<Response>();
  const { sent, refetchRanking } = mount({ ranking: () => ok(rankingPage(server)), clicks: () => answer.promise });
  await waitFor(() => assert.equal(schoolScore(), 5_000));
  click();
  await waitFor(() => assert.equal(sent.length, 1), { timeout: 2500 });
  server = 5_002; // Already counts the batch on its way; adding the pending 2 again would show 5,004.
  await refetchRanking();
  assert.equal(schoolScore(), 5_002, '낙관 값 유지, 이중 합산 없음');
  await act(async () => { answer.resolve(ok(clickAnswer(5_002, 1))); });
  await waitFor(() => assert.equal(schoolScore(), 5_002));
});

test('클릭 응답보다 먼저 요청한 순위 조회가 늦게 도착해도 더 오래된 점수로 되돌리지 않는다', async () => {
  let rankingAnswer: Promise<Response> | Response = ok(rankingPage(5_000));
  const { sent } = mount({ ranking: () => rankingAnswer, clicks: (count) => ok(clickAnswer(7_000, count)) });
  await waitFor(() => assert.equal(schoolScore(), 5_000));
  const late = deferred<Response>();
  rankingAnswer = late.promise;
  // Requested before the click answer, holding the total from before it.
  void clients[0].refetchQueries({ queryKey: rankingKeys.mySchool(ME.id) });
  click();
  await waitFor(() => assert.equal(sent.length, 1), { timeout: 2500 });
  await waitFor(() => assert.equal(schoolScore(), 7_000));
  await act(async () => { late.resolve(ok(rankingPage(5_000))); await settle(); });
  assert.equal(schoolScore(), 7_000);
});

// #144: the hidden operator school is outside the static list and the rankings, so myUniversity is null for it.
const OFF_LIST_ID = 9001;
const OPERATOR: MyInfo = { ...ME, university: { id: OFF_LIST_ID, name: '와플대학교', logoUrl: null }, department: { id: 6697, name: '운영팀' } };
const detail = (totalScore: number) => ok({
  id: OFF_LIST_ID, name: '와플대학교', logoUrl: null, totalScore, rank: null, memberCount: 1,
  topDepartments: [], topContributors: [], isMyUniversity: true, isMyAlly: false, rankChange: null,
});

test('#144: 정적 목록에 없는 내 학교(숨김 학교)는 순위 대신 학교 상세 점수를 쓴다 (0으로 되돌리지 않는다)', async () => {
  mount({ me: OPERATOR, ranking: () => ok(rankingPage(null)), detail: () => detail(24) });
  await waitFor(() => assert.equal(schoolScore(), 24));
  await act(async () => { await clients[0].refetchQueries({ queryKey: rankingKeys.mySchool(ME.id) }); await settle(); });
  assert.equal(schoolScore(), 24, '순위의 myUniversity null로 0이 되지 않는다');
});

test('#144: 클릭 응답보다 먼저 요청한 숨김 학교 점수 조회가 늦게 도착해도 더 오래된 점수로 되돌리지 않는다', async () => {
  let detailAnswer: Promise<Response> | Response = detail(24);
  const { sent } = mount({ me: OPERATOR, ranking: () => ok(rankingPage(null)), detail: () => detailAnswer,
    clicks: (count) => ok({ ...clickAnswer(30, count), university: { id: OFF_LIST_ID, totalScore: 30, rank: 1, logoUrl: null } }) });
  await waitFor(() => assert.equal(schoolScore(), 24));
  const late = deferred<Response>();
  detailAnswer = late.promise;
  // Requested before the click answer; the click answer cancels it like the ranking (same key prefix).
  void clients[0].refetchQueries({ queryKey: rankingKeys.mySchool(ME.id) });
  click();
  await waitFor(() => assert.equal(sent.length, 1), { timeout: 2500 });
  await waitFor(() => assert.equal(schoolScore(), 30));
  await act(async () => { late.resolve(detail(24)); await settle(); });
  assert.equal(schoolScore(), 30);
});

test('연결 센서: API 모드 App은 ApiSchoolScoreSync에 미확정 점수와 전송 상태를 넘긴다', () => {
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(app, /API_MODE === 'api' && <ApiSchoolScoreSync getPendingScore=\{campusClicks\.getPendingScore\} isSending=\{campusClicks\.isSending\} \/>/,
    'App이 ApiSchoolScoreSync를 API 모드에서 마운트해야 메인 상단 점수가 서버 값을 따른다');
});
