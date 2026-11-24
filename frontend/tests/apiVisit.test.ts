// SCRUM-163: the visit screen reads the school's detail from the API (score, rank, members, top departments and
// contributors). SCRUM-181 removes fictional guestbook previews in the API detail; the full sheet reads GET/POST.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { SCHOOL_ID, type SchoolId } from '../src/data/schoolIds';
import type { MyInfo } from '../src/api/users';
import type { UniversityDetail } from '../src/api/universities';
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

const { render, screen, act, cleanup, waitFor, fireEvent, within } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { userKeys } = await import('../src/api/users');
const { MockGameProvider } = await import('../src/hooks/useMockGame');
const { useApiVisitSchool, toVisitSchoolView } = await import('../src/hooks/useVisitSchool');
const { VisitSchoolInfoSheet, SpectatorBar } = await import('../src/components/VisitingParts');
const { TopHUD, schoolHudProps } = await import('../src/components/TopHUD');
const { LoadNotice } = await import('../src/components/LoadNotice');
const clients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => { cleanup(); for (const client of clients.splice(0)) client.clear(); });
after(() => { assets.deregister(); dom.window.close(); });

const ME: MyInfo = {
  id: 10, nickname: '방문학생', email: 'visit@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: SCHOOL_ID.hanyang, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 }, totalContribScore: 30, totalAttackScore: 0,
};
const PUSAN: UniversityDetail = {
  id: SCHOOL_ID.pusan, name: '부산대학교', logoUrl: null, totalScore: 12_345, rank: 2, memberCount: 42,
  topDepartments: [{ rank: 1, id: 31, name: '기계공학부', totalScore: 9000 }, { rank: 2, id: 32, name: '경영학과', totalScore: 3000 }],
  topContributors: [{ rank: 1, userId: 501, nickname: '부산1등', contribScore: 800 }, { rank: 1, userId: 502, nickname: '부산공동1등', contribScore: 800 }],
  isMyUniversity: false,
  isMyAlly: false,
  rankChange: 2,
};
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status: 200 });
const fail = (status: number, code: string, message: string) => new Response(JSON.stringify({ code, message, data: null }), { status });

let visit: ReturnType<typeof useApiVisitSchool>;
function Probe({ schoolId }: { schoolId: SchoolId | null }) {
  visit = useApiVisitSchool(schoolId);
  if (visit.status === 'not-found') return React.createElement('p', { role: 'alert' }, '학교를 찾을 수 없습니다');
  if (visit.status !== 'ready' || !visit.school) {
    return React.createElement(LoadNotice, { status: visit.status === 'failed' ? 'failed' : 'loading', name: '방문 학교', failureDetail: visit.failureDetail, onRetry: visit.retry });
  }
  const school = visit.school;
  return React.createElement(React.Fragment, null,
    React.createElement(TopHUD, { mode: 'visit', ...schoolHudProps(school), schoolScore: school.score, rank: school.rank, rankDelta: school.rankChange,
      campusLevel: school.campusLevel, soundEnabled: false, onToggleSound: () => {} }),
    React.createElement(SpectatorBar, { mySchoolShortName: visit.mySchoolShortName, onBackToMySchool: () => {} }),
    React.createElement(VisitSchoolInfoSheet, { school, extras: visit.extras, onOpenGuestbook: () => {} }),
  );
}
interface Call { path: string; auth: string | null }
function mount(schoolId: SchoolId | null, { signedIn = true, cachedMe = true, respond }: { signedIn?: boolean; cachedMe?: boolean; respond?: (url: URL, n: number) => Response | undefined } = {}) {
  const calls: Call[] = [];
  let detailCalls = 0;
  const client = createApiClient({ fetch: (async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    calls.push({ path: url.pathname, auth: new Headers(init?.headers).get('Authorization') });
    if (/\/universities\/\d+$/.test(url.pathname)) detailCalls += 1;
    const custom = respond?.(url, detailCalls);
    if (custom) return custom;
    if (url.pathname === `/api/v1/universities/${SCHOOL_ID.pusan}`) return ok(PUSAN);
    if (url.pathname.endsWith('/users/me')) return ok(ME);
    throw new Error(`Unexpected path: ${url.pathname}`);
  }) as typeof fetch });
  const queryClient = createQueryClient(); clients.push(queryClient);
  if (signedIn) { client.session.signIn('member-a'); if (cachedMe) queryClient.setQueryData(userKeys.me, ME); }
  render(React.createElement(MockGameProvider, null,
    React.createElement(ApiProvider, { client, queryClient, children: React.createElement(Probe, { schoolId }) })));
  return { calls, detailCalls: () => calls.filter((call) => /\/universities\/\d+$/.test(call.path)) };
}
const info = () => screen.getByRole('region', { name: '학교 점수' });

test('방문 화면은 서버의 점수·순위·순위 변동·회원 수·상위 학과·기여 랭커를 보인다', async () => {
  const f = mount(SCHOOL_ID.pusan);
  await waitFor(() => assert.equal(visit.status, 'ready'));
  assert.deepEqual(f.detailCalls(), [{ path: `/api/v1/universities/${SCHOOL_ID.pusan}`, auth: 'Bearer member-a' }]);
  assert.equal(screen.getByTestId('visit-score').textContent, '12,345점');
  assert.equal(screen.getByTestId('visit-rank').textContent, '2위');
  assert.equal(screen.getByTestId('rank-change').textContent, '▲2', '상단 배지는 서버 rankChange (SCRUM-189)');
  assert.equal(screen.getByTestId('rank-change').dataset.trend, 'up');
  assert.equal(screen.getByTestId('visit-info-rank').textContent, '🥈 2위 (▲2)');
  assert.equal(within(info()).getByTestId('visit-info-members').textContent, '42명');
  const contributors = within(screen.getByRole('list', { name: '기여 랭커 목록' })).getAllByRole('listitem').map((li) => li.textContent);
  assert.deepEqual(contributors, ['🥇 1위 · 부산1등800점', '🥇 1위 · 부산공동1등800점'], '동점은 같은 순위');
  const departments = within(screen.getByRole('list', { name: '상위 학과 목록' })).getAllByRole('listitem').map((li) => li.textContent);
  assert.deepEqual(departments, ['🥇 1위 · 기계공학부9,000점', '🥈 2위 · 경영학과3,000점']);
  assertAbsent(screen.queryByText('기여 랭커 정보 없음 (목업)'));
  assertAbsent(screen.queryByTestId('guestbook-preview-note'), 'API 방문 정보에 가짜 방명록을 표시하지 않는다');
  screen.getByText('전체보기에서 실제 글을 확인하세요. 인기순은 준비 중입니다.');
  screen.getByRole('button', { name: '방명록 전체보기' });
  assert.match(screen.getByTestId('spectator-bar').querySelector('p')!.getAttribute('title') ?? '', /내 학교\(한양대\)/);
  assert.equal(visit.isMyUniversity, false);
});

test('로그인했으면 내 정보를 받은 뒤 한 번만 조회하고, 로그인 전에는 토큰 없이 조회한다', async () => {
  const signedIn = mount(SCHOOL_ID.pusan, { cachedMe: false });
  await waitFor(() => assert.equal(visit.status, 'ready'));
  assert.deepEqual(signedIn.calls.map((call) => call.path), ['/api/v1/users/me', `/api/v1/universities/${SCHOOL_ID.pusan}`]);
  assert.equal(signedIn.detailCalls()[0].auth, 'Bearer member-a');
  cleanup();

  const guest = mount(SCHOOL_ID.pusan, { signedIn: false });
  await waitFor(() => assert.equal(visit.status, 'ready'));
  assert.deepEqual(guest.detailCalls(), [{ path: `/api/v1/universities/${SCHOOL_ID.pusan}`, auth: null }]);
  assert.equal(visit.mySchoolShortName, null);
  assert.match(screen.getByTestId('spectator-bar').querySelector('p')!.getAttribute('title') ?? '', /로그인하면/);
});

test('하락은 ▼n 분홍 배지, 오늘 기준 복사본이 없어 변동이 null이면 순위만 보인다 (SCRUM-189)', async () => {
  let change: number | null = -3;
  mount(SCHOOL_ID.pusan, { respond: (url) => (url.pathname.endsWith(`/universities/${SCHOOL_ID.pusan}`)
    ? ok({ ...PUSAN, rankChange: change }) : undefined) });
  await waitFor(() => assert.equal(screen.getByTestId('rank-change').textContent, '▼3'));
  assert.equal(screen.getByTestId('rank-change').dataset.trend, 'down');
  assert.equal(screen.getByTestId('visit-info-rank').textContent, '🥈 2위 (▼3)');
  change = null;
  await act(async () => { await clients[0].refetchQueries(); });
  await waitFor(() => assertAbsent(screen.queryByTestId('rank-change')));
  assert.equal(screen.getByTestId('visit-rank').textContent, '2위');
  assert.equal(screen.getByTestId('visit-info-rank').textContent, '🥈 2위');
});

test('서버 순위가 없으면 "순위 없음", 학과·기여자가 없으면 빈 상태 문구를 보인다', async () => {
  mount(SCHOOL_ID.pusan, { respond: (url) => (url.pathname.endsWith(`/universities/${SCHOOL_ID.pusan}`)
    ? ok({ ...PUSAN, totalScore: 0, rank: null, rankChange: null, topDepartments: [], topContributors: [] }) : undefined) });
  await waitFor(() => assert.equal(visit.status, 'ready'));
  assert.equal(screen.getByTestId('visit-rank').textContent, '순위 없음');
  assert.equal(screen.getByTestId('visit-info-rank').textContent, '🏫 순위 없음');
  assertAbsent(screen.queryByTestId('rank-change'), '순위가 없으면 변동 배지도 없다');
  assert.ok(screen.getByText('아직 기여한 학생이 없어요'));
  assert.ok(screen.getByText('아직 점수를 올린 학과가 없어요'));
});

test('없는 학교(404)는 학교 없음으로, 그 밖의 실패는 다시 시도로 회복한다', async () => {
  const missing = mount(SCHOOL_ID.snu, { respond: (url) => (url.pathname.endsWith(`/universities/${SCHOOL_ID.snu}`) ? fail(404, 'UNIV_NOT_FOUND', '학교를 찾을 수 없습니다.') : undefined) });
  await waitFor(() => assert.equal(visit.status, 'not-found'));
  assert.equal(missing.detailCalls().length, 1, '404는 다시 시도하지 않는다');
  cleanup();

  // #144: a school outside the directory may still exist on the server, so the server decides.
  const unknown = mount(9999 as SchoolId, { respond: (url) => (url.pathname.endsWith('/universities/9999') ? fail(404, 'UNIV_NOT_FOUND', '학교를 찾을 수 없습니다.') : undefined) });
  await waitFor(() => assert.equal(visit.status, 'not-found'));
  assert.equal(unknown.detailCalls().length, 1, '디렉터리에 없는 ID도 서버에 묻고, 404면 학교 없음');
  cleanup();

  mount(SCHOOL_ID.pusan, { respond: (url, n) => (url.pathname.endsWith(`/universities/${SCHOOL_ID.pusan}`) && n === 1 ? fail(400, 'INVALID_INPUT', '방문 조회 조건이 올바르지 않습니다.') : undefined) });
  await waitFor(() => assert.equal(visit.status, 'failed'));
  assert.match(document.body.textContent ?? '', /방문 조회 조건이 올바르지 않습니다/);
  fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
  await waitFor(() => assert.equal(visit.status, 'ready'));
});

test('#144: 디렉터리에 없는 학교(숨김 학교)도 서버 이름·점수로 보이고, 순위는 "순위 없음"이다', async () => {
  const WAFFLE_ID = 9001;
  mount(WAFFLE_ID as SchoolId, { respond: (url) => (url.pathname.endsWith(`/universities/${WAFFLE_ID}`)
    ? ok({ ...PUSAN, id: WAFFLE_ID, name: '와플대학교', totalScore: 24, rank: null, rankChange: null }) : undefined) });
  await waitFor(() => assert.equal(visit.status, 'ready'));
  assert.equal(visit.school?.name, '와플대학교');
  assert.equal(visit.school?.region, '기타', '지역이 없는 학교');
  assert.equal(screen.getByTestId('visit-score').textContent, '24점');
  assert.equal(screen.getByTestId('visit-rank').textContent, '순위 없음');
  assertAbsent(screen.queryByTestId('rank-change'));
});

test('서버가 내 학교라고 하면 isMyUniversity로 알린다 (화면은 내 캠퍼스로 보낸다)', async () => {
  mount(SCHOOL_ID.pusan, { respond: (url) => (url.pathname.endsWith(`/universities/${SCHOOL_ID.pusan}`) ? ok({ ...PUSAN, isMyUniversity: true }) : undefined) });
  await waitFor(() => assert.equal(visit.status, 'ready'));
  assert.equal(visit.isMyUniversity, true);
  assert.equal(visit.school?.isMySchool, true);
  const offList = toVisitSchoolView({ ...PUSAN, id: 9999, name: '와플대학교' });
  assert.deepEqual([offList.name, offList.region, offList.isMySchool], ['와플대학교', '기타', false],
    '디렉터리에 없는 서버 학교(#144)는 서버 이름과 지역 없음(기타)으로 행을 만든다');
});

test('계약 센서: 학교 상세 DTO·경로가 백엔드와 같고, 방문 화면은 모드별 훅과 내 학교 이동을 쓴다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/university/', import.meta.url);
  const dto = readFileSync(new URL('dto/UniversityDetailResponse.java', root), 'utf8');
  const components = (body: string) => [...body.matchAll(/^\s+(?:Long|long|String|boolean|List<[\w.]+>)\s+(\w+)/gm)].map((match) => match[1]);
  const [outer, departments, contributors] = dto.split(/public record \w+\(/).slice(1).map((part) => components(part.split(')')[0]));
  assert.deepEqual(outer, ['id', 'name', 'logoUrl', 'totalScore', 'rank', 'memberCount', 'topDepartments', 'topContributors', 'isMyUniversity', 'isMyAlly', 'rankChange']);
  assert.deepEqual(departments, ['rank', 'id', 'name', 'totalScore']);
  assert.deepEqual(contributors, ['rank', 'userId', 'nickname', 'contribScore']);
  const controller = readFileSync(new URL('controller/UniversityController.java', root), 'utf8');
  assert.match(controller, /@RequestMapping\("\/api\/v1\/universities"\)/);
  assert.match(controller, /@GetMapping\("\/\{universityId\}"\)/);
  const phone = readFileSync(new URL('../src/components/VisitingScreen1Phone.tsx', import.meta.url), 'utf8');
  assert.match(phone, /useVisitSchool\(schoolId\)/);
  assert.doesNotMatch(phone, /useVisitedSchool\(/);
  assert.match(phone, /if \(isMyUniversity\) onBackToMySchool\(\)/);
});
