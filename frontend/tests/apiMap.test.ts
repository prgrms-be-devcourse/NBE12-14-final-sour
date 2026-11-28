// SCRUM-162: the school map shows the server's ranks and scores on the frontend's marker layout, and a marker opens
// that school's visit screen.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { readJava } from './helpers/javaSource';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { SCHOOL_ID, type SchoolId } from '../src/data/schoolIds';
import type { MyInfo } from '../src/api/users';
import type { MapUniversity } from '../src/api/map';
import { installDialogStub } from './helpers/dialogStub';
import { assertAbsent, assertSameNode } from './helpers/domAssert';

const assets = registerHooks({ load(url, context, nextLoad) {
  if (/\/api\/mode\.ts$/.test(url)) return {
    format: 'module', source: 'export const API_MODE = "api"; export function selectByMode(impl) { return impl.api; }', shortCircuit: true,
  };
  if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) return { format: 'module', source: 'export default "/asset";', shortCircuit: true };
  return nextLoad(url, context);
} });
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
installDialogStub(dom.window);
for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
dom.window.HTMLElement.prototype.scrollIntoView = function () {};

const { render, screen, cleanup, waitFor, fireEvent, within, act } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { userKeys } = await import('../src/api/users');
const { MAP_UNIVERSITY_LIMIT } = await import('../src/api/map');
const { MockGameProvider } = await import('../src/hooks/useMockGame');
const { useApiMapSchools, toMapSchoolViews } = await import('../src/hooks/useMapSchools');
const { MapScreenPhone } = await import('../src/components/MapScreenPhone');
const { LoadNotice } = await import('../src/components/LoadNotice');
const { REAL_UNIVERSITIES } = await import('../src/data/universitiesData');
const clients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => { cleanup(); for (const client of clients.splice(0)) client.clear(); });
after(() => { assets.deregister(); dom.window.close(); });

const ME: MyInfo = {
  id: 10, nickname: '지도학생', email: 'map@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: SCHOOL_ID.hanyang, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 }, totalContribScore: 30, totalAttackScore: 0,
};
const entry = (rank: number, universityId: number, totalScore: number, logoUrl: string | null = null): MapUniversity => ({
  rank, universityId, name: REAL_UNIVERSITIES.find((u) => u.id === universityId)?.name ?? `서버학교${universityId}`,
  logoUrl, totalScore, latitude: null, longitude: null,
});
// Ties share rank 2; the server's 1st place is not the mock's; 9999 has no directory row.
const SERVER: MapUniversity[] = [
  entry(1, SCHOOL_ID.chonnam, 900, 'https://cdn.example/chonnam.png'),
  entry(2, SCHOOL_ID.hanyang, 500),
  entry(2, SCHOOL_ID.dgist, 500),
  entry(4, 9999, 100),
];
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status: 200 });
const fail = (status: number, code: string, message: string) => new Response(JSON.stringify({ code, message, data: null }), { status });

let map: ReturnType<typeof useApiMapSchools>;
function Probe({ onSelect, embedded }: { onSelect: (id: SchoolId) => void; embedded?: boolean }) {
  map = useApiMapSchools();
  if (map.status !== 'ready') {
    return React.createElement(LoadNotice, { status: map.status, name: '학교 지도', failureDetail: map.failureDetail, onRetry: map.retry });
  }
  return React.createElement(MapScreenPhone, { onSelectSchool: onSelect, panel: embedded ? { onVisit: onSelect } : undefined });
}
function mount({ signedIn = true, embedded, respond }: { signedIn?: boolean; embedded?: boolean; respond?: (url: URL, n: number) => Response | undefined } = {}) {
  const calls: Array<{ path: string; auth: string | null }> = [];
  const selected: SchoolId[] = [];
  let mapCalls = 0;
  const client = createApiClient({ fetch: (async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    calls.push({ path: `${url.pathname}${url.search}`, auth: new Headers(init?.headers).get('Authorization') });
    if (url.pathname.endsWith('/map/universities')) mapCalls += 1;
    const custom = respond?.(url, mapCalls);
    if (custom) return custom;
    if (url.pathname.endsWith('/map/universities')) return ok(SERVER);
    if (url.pathname.endsWith('/users/me')) return ok(ME);
    throw new Error(`Unexpected path: ${url.pathname}`);
  }) as typeof fetch });
  const queryClient = createQueryClient(); clients.push(queryClient);
  if (signedIn) { client.session.signIn('member-a'); queryClient.setQueryData(userKeys.me, ME); }
  render(React.createElement(MockGameProvider, null,
    React.createElement(ApiProvider, { client, queryClient, children: React.createElement(Probe, { embedded, onSelect: (id: SchoolId) => selected.push(id) }) })));
  return { calls, selected };
}
const mapArea = () => screen.getByTestId('map-area');

test('지도는 5초마다 재조회하여 변경된 순위와 점수를 표시한다', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] });
  const f = mount({ respond: (url, n) => url.pathname.endsWith('/map/universities') && n > 1
    ? ok([entry(1, SCHOOL_ID.hanyang, 9876), entry(2, SCHOOL_ID.chonnam, 900)]) : undefined });
  for (let i = 0; i < 12; i++) await act(async () => { await new Promise<void>((resolve) => setImmediate(resolve)); t.mock.timers.tick(10); });
  assert.equal(f.calls.filter((call) => call.path.includes('/map/universities')).length, 1);
  assert.equal(screen.getByTestId('my-school-tag').textContent, '내 학교 한양대 · 2위');
  await act(async () => { t.mock.timers.tick(5000); await new Promise<void>((resolve) => setImmediate(resolve)); });
  for (let i = 0; i < 12; i++) await act(async () => { await new Promise<void>((resolve) => setImmediate(resolve)); t.mock.timers.tick(10); });
  assert.equal(f.calls.filter((call) => call.path.includes('/map/universities')).length, 2);
  assert.equal(screen.getByTestId('my-school-tag').textContent, '내 학교 한양대 · 1위');
  assert.ok(within(mapArea()).getAllByText('9,876').length > 0);
});

test('서버 순위·점수를 학교 디렉터리에 덮어쓰고, 응답에 없는 학교는 0점 공동 최하위로 둔다', () => {
  const views = toMapSchoolViews(SERVER, SCHOOL_ID.hanyang);
  assert.equal(views.length, REAL_UNIVERSITIES.length, '76개교 모두 지도에 남는다');
  assert.ok(!views.some((school) => school.id === 9999), '배치 좌표가 없는 서버 학교는 빠진다');
  assert.deepEqual(views.slice(0, 3).map((s) => [s.id, s.rank, s.score, s.isTop3]), [
    [SCHOOL_ID.chonnam, 1, 900, true],
    [SCHOOL_ID.hanyang, 2, 500, true],
    [SCHOOL_ID.dgist, 2, 500, true],
  ]);
  assert.equal(views[0].logoUrl, 'https://cdn.example/chonnam.png');
  const unranked = views.find((school) => school.id === SCHOOL_ID.snu)!;
  assert.deepEqual([unranked.rank, unranked.score, unranked.isTop3, unranked.logoUrl], [SERVER.length + 1, 0, false, null]);
  assert.deepEqual(views.filter((school) => school.isMySchool).map((school) => school.id), [SCHOOL_ID.hanyang]);
  assert.ok(toMapSchoolViews(SERVER, null).every((school) => !school.isMySchool), '로그인 전에는 내 학교가 없다');
  assert.ok(toMapSchoolViews([], null).every((school) => !school.isTop3 && school.rank === 1 && school.score === 0), '점수가 하나도 없으면 TOP 3도 없다');
});

test('지도는 300개까지 익명 조회하고, 서버 순위로 TOP 3·내 학교를 그리며, 마커 선택이 그 학교를 연다', async () => {
  const f = mount();
  await waitFor(() => assert.equal(map.status, 'ready'));
  assert.deepEqual(f.calls, [{ path: `/api/v1/map/universities?limit=${MAP_UNIVERSITY_LIMIT}`, auth: null }]);
  assert.equal(screen.getByTestId('my-school-tag').textContent, '내 학교 한양대 · 2위');
  const ribbons = Array.from(mapArea().querySelectorAll('[data-top-rank]')).map((node) => node.getAttribute('data-top-rank'));
  assert.deepEqual(ribbons, ['1', '2', '2']);
  const list = screen.getByRole('list', { name: '학교 목록' });
  assert.equal(within(list).getByText('전남대학교').closest('li')?.getAttribute('data-school-id'), String(SCHOOL_ID.chonnam));
  fireEvent.click(screen.getByRole('button', { name: /^전남대학교 전국 1위$/ }));
  assert.deepEqual(f.selected, [SCHOOL_ID.chonnam]);
});

test('서비스 권역 선택은 접힌 채 시작하고 검색·초점을 유지하며 충청을 왕복한다', async () => {
  mount();
  const toggle = await screen.findByRole('button', { name: '지방 선택하기' });
  const input = screen.getByRole<HTMLInputElement>('textbox', { name: '학교 검색' });
  const group = () => screen.getByRole('group', { name: '권역 선택' });
  assert.equal(toggle.getAttribute('aria-expanded'), 'false');
  assertAbsent(screen.queryByRole('group', { name: '권역 선택' }));
  const bucket = screen.getByRole('button', { name: /^충북 대학 .*충청 확대$/ });
  assert.ok(bucket.querySelector('.game-map-cluster-label'));
  bucket.focus();
  fireEvent.click(bucket);
  assertSameNode(document.activeElement, toggle);
  assert.equal(toggle.getAttribute('aria-expanded'), 'false');
  assert.equal(mapArea().getAttribute('data-region'), '충청');

  fireEvent.change(input, { target: { value: 'KAIST' } });
  fireEvent.click(toggle);
  assert.equal(toggle.getAttribute('aria-controls'), group().id);
  assert.equal(toggle.getAttribute('aria-expanded'), 'true');
  for (const name of ['전체', '수도권', '충청']) {
    const chip = within(group()).getByRole('button', { name });
    chip.focus();
    fireEvent.click(chip);
    assertSameNode(document.activeElement, chip);
    assert.equal(chip.getAttribute('aria-pressed'), 'true');
    assert.equal(toggle.getAttribute('aria-expanded'), 'true');
    assert.equal(input.value, 'KAIST');
    assert.equal(mapArea().getAttribute('data-region'), name);
  }
  fireEvent.click(screen.getByRole('button', { name: '지방 숨기기' }));
  assertAbsent(screen.queryByRole('group', { name: '권역 선택' }));
  fireEvent.click(within(mapArea()).getByRole('button', { name: /^KAIST 전국/ }));
  const selected = within(screen.getByRole('list', { name: '학교 목록' })).getByText('KAIST').closest('li');
  assert.match(selected?.textContent ?? '', /충남·대전 · 대전/);
});

test('PC 선택 행은 중복 지명만 줄이고 0점·검색·선택 상태와 명시적 방문을 유지한다', async () => {
  const { selected } = mount({ embedded: true });
  const toggle = await screen.findByRole('button', { name: '지방 선택하기' });
  const list = screen.getByRole('region', { name: '학교 목록' });
  const name = '서울대학교';
  fireEvent.click(within(list).getByRole('button', { name }));
  assert.deepEqual(selected, []);
  assert.equal(within(list).getByText('서울 · 0점').textContent, '서울 · 0점');
  const input = screen.getByRole('textbox', { name: '학교 검색' });
  const ribbons = () => [...mapArea().querySelectorAll<HTMLElement>('[data-top-rank]')].map(node => node.dataset.schoolId);
  const ranked = ribbons();
  assert.equal(ranked.length, 3);
  fireEvent.change(input, { target: { value: name } });
  assert.deepEqual(ribbons(), ranked);
  fireEvent.click(toggle);
  const chip = (region: string) => within(screen.getByRole('group', { name: '권역 선택' })).getByRole('button', { name: region });
  fireEvent.click(chip('제주'));
  assert.equal(screen.getByTestId('map-search-status').textContent, '검색 결과 없음');
  assert.ok(screen.getByText('선택 학교가 검색 결과에 없습니다.'));
  fireEvent.click(chip('전체'));
  fireEvent.click(within(list).getByRole('button', { name: `${name} 방문` }));
  assert.deepEqual(selected, [SCHOOL_ID.snu]);
  fireEvent.change(input, { target: { value: 'KAIST' } });
  fireEvent.click(within(list).getByRole('button', { name: 'KAIST' }));
  assert.equal(within(list).getByText('충남·대전 · 대전 · 0점').textContent, '충남·대전 · 대전 · 0점');
});

// 4xx is not retried by the query client (queryClient.ts), so the failure shows at once.
test('지도 조회가 실패하면 서버 문구와 다시 시도를 보이고, 다시 시도로 회복한다', async () => {
  mount({ signedIn: false, respond: (url, n) => (url.pathname.endsWith('/map/universities') && n === 1 ? fail(400, 'INVALID_INPUT', '지도 조회 조건이 올바르지 않습니다.') : undefined) });
  await waitFor(() => assert.equal(map.status, 'failed'));
  assert.match(document.body.textContent ?? '', /지도 조회 조건이 올바르지 않습니다/);
  fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
  await waitFor(() => assert.equal(map.status, 'ready'));
  assertAbsent(screen.queryByTestId('my-school-tag'), '로그인 전에는 내 학교 표시가 없다');
});

test('계약 센서: 지도 DTO 필드와 경로·상한이 백엔드와 같고, 지도 화면은 모드별 훅을 쓴다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/university/', import.meta.url);
  const dto = readJava(new URL('dto/UniversityMapResponse.java', root));
  const fields = [...dto.matchAll(/^\s+(?:long|String|BigDecimal)\s+(\w+)/gm)].map((match) => match[1]);
  assert.deepEqual(fields, ['rank', 'universityId', 'name', 'logoUrl', 'totalScore', 'latitude', 'longitude']);
  const controller = readJava(new URL('controller/UniversityMapController.java', root));
  assert.match(controller, /@RequestMapping\("\/api\/v1\/map"\)/);
  assert.match(controller, /@GetMapping\("\/universities"\)/);
  assert.match(controller, /implements UniversityMapApiDocs/);
  assert.match(readJava(new URL('controller/docs/UniversityMapApiDocs.java', root)), new RegExp(`@Max\\(${MAP_UNIVERSITY_LIMIT}\\)`));
  const phone = readFileSync(new URL('../src/components/MapScreenPhone.tsx', import.meta.url), 'utf8');
  assert.match(phone, /useMapSchools\(\)/);
  assert.doesNotMatch(phone, /useSchoolViews\(/);
});
