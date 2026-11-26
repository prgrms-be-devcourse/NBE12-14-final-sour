// SCRUM-172: the admin menu is shown only to a profile whose role is ADMIN, and the admin home handles the server's
// own role check (403 COMMON_FORBIDDEN while the access token still carries the old role).
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { readJava } from './helpers/javaSource';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';

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

const { render, screen, fireEvent, cleanup, waitFor } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { adminKeys, isAdminForbidden } = await import('../src/api/admin');
const { ApiError } = await import('../src/api/errors');
const { MyInfoScreenPhone } = await import('../src/components/MyInfoScreenPhone');
const { AdminScreenPhone } = await import('../src/components/AdminScreenPhone');
const { ApiSessionWatcher } = await import('../src/components/onboarding/ApiLoginPhone');
const { SCHOOL_ID } = await import('../src/data/schoolIds');
const { assertAbsent } = await import('./helpers/domAssert');
type MyInfo = import('../src/api/users').MyInfo;

const queryClients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => {
  cleanup();
  for (const client of queryClients.splice(0)) client.clear();
});
after(() => { assets.deregister(); dom.window.close(); });

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const ok = (data: unknown) => json(200, { code: 'SUCCESS', message: '요청이 성공했습니다.', data });
const fail = (status: number, code: string, message = '실패') => json(status, { code, message, data: null });
const pendingPage = (totalCount: number) => ok({ items: [], page: 0, size: 1, totalCount, hasNext: totalCount > 1 });

const MEMBER: MyInfo = {
  id: 3,
  nickname: '한양새내기',
  email: 'student@hanyang.ac.kr',
  role: 'USER',
  status: 'ACTIVE',
  profileImageUrl: null,
  university: { id: SCHOOL_ID.hanyang, name: '한양대학교', logoUrl: null },
  department: { id: 11, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 },
  totalContribScore: 0,
  totalAttackScore: 0,
};
const ADMIN: MyInfo = { ...MEMBER, id: 1, nickname: '운영자', email: 'admin@hanyang.ac.kr', role: 'ADMIN' };

interface Call { route: string; search: string; auth: string | null }
interface MountOptions {
  screen: 'my-info' | 'admin';
  me: () => MyInfo;
  pending?: () => Response;
  signedIn?: boolean;
}
function mount({ screen: which, me, pending = () => pendingPage(0), signedIn = true }: MountOptions) {
  const calls: Call[] = [];
  const navigation: string[] = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const route = `${init?.method ?? 'GET'} ${url.pathname}`;
    calls.push({ route, search: url.search, auth: new Headers(init?.headers).get('Authorization') });
    if (route === 'GET /api/v1/users/me') return ok(me());
    if (route === 'GET /api/v1/admin/supports') return pending();
    throw new Error(`Unexpected ${route}`);
  }) as typeof fetch });
  if (signedIn) client.session.signIn('access-1');
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  const ui = which === 'my-info'
    ? React.createElement(MyInfoScreenPhone, { key: 'screen', onBack: () => navigation.push('back'), onGoToLogin: () => navigation.push('login'), onOpenAdmin: () => navigation.push('admin') })
    : React.createElement(AdminScreenPhone, { key: 'screen', onBack: () => navigation.push('back'), onGoToLogin: () => navigation.push('login'), schoolName: { value: 'default', onChange: () => undefined } });
  render(React.createElement(ApiProvider, { client, queryClient, children: [
    React.createElement(ApiSessionWatcher, { key: 'watcher', onEnded: () => navigation.push('ended') }), ui,
  ] }));
  return { calls, navigation, client, queryClient };
}
const adminCalls = (calls: Call[]) => calls.filter(({ route }) => route.includes('/api/v1/admin/'));
const meCalls = (calls: Call[]) => calls.filter(({ route }) => route === 'GET /api/v1/users/me');

test('일반 회원의 내 정보에는 관리자 메뉴 버튼이 없다', async () => {
  const f = mount({ screen: 'my-info', me: () => MEMBER });
  await waitFor(() => assert.equal(screen.getByTestId('my-info-nickname').textContent, '한양새내기'));
  assertAbsent(screen.queryByRole('button', { name: '관리자 메뉴' }));
  assert.equal(adminCalls(f.calls).length, 0);
});

test('관리자의 내 정보에는 관리자 메뉴 버튼이 있고 누르면 관리자 화면으로 간다', async () => {
  const f = mount({ screen: 'my-info', me: () => ADMIN });
  fireEvent.click(await screen.findByRole('button', { name: '관리자 메뉴' }));
  assert.deepEqual(f.navigation, ['admin']);
  assert.equal(adminCalls(f.calls).length, 0, '내 정보 화면은 관리자 API를 부르지 않는다');
});

test('일반 회원이 관리자 화면에 들어오면 안내만 보이고 관리자 API를 부르지 않는다', async () => {
  const f = mount({ screen: 'admin', me: () => MEMBER });
  await screen.findByTestId('admin-denied');
  assertAbsent(screen.queryByRole('navigation', { name: '관리자 메뉴' }));
  assert.equal(adminCalls(f.calls).length, 0);
});

test('로그인하지 않았으면 로그인 안내를 보이고 아무 요청도 하지 않는다', async () => {
  const f = mount({ screen: 'admin', me: () => ADMIN, signedIn: false });
  fireEvent.click(await screen.findByRole('button', { name: '로그인' }));
  assert.deepEqual(f.navigation, ['login']);
  // Only the watcher's session restore (POST /auth/reissue) may run.
  assert.deepEqual(f.calls.map(({ route }) => route).filter((route) => route !== 'POST /api/v1/auth/reissue'), []);
});

test('관리자 화면은 답변 대기 문의 수와 관리자 메뉴를 보인다', async () => {
  const f = mount({ screen: 'admin', me: () => ADMIN, pending: () => pendingPage(1234) });
  await waitFor(() => assert.equal(screen.getByTestId('admin-pending-count').textContent, '1,234'));
  assert.deepEqual(adminCalls(f.calls), [{ route: 'GET /api/v1/admin/supports', search: '?status=RECEIVED&page=0&size=1', auth: 'Bearer access-1' }]);
  const menu = screen.getByRole('navigation', { name: '관리자 메뉴' });
  const labels = [...menu.querySelectorAll('button')].map((button) => button.firstChild?.textContent);
  assert.deepEqual(labels, ['문의 처리', '회원 정지·해제', '어뷰징 순위', '테스트 도구']);
});

test('403 COMMON_FORBIDDEN이면 권한 안내를 보이고 내 정보를 다시 읽는다. USER로 바뀌면 비관리자 안내가 된다', async () => {
  let profile = ADMIN;
  const f = mount({ screen: 'admin', me: () => profile, pending: () => { profile = MEMBER; return fail(403, 'COMMON_FORBIDDEN', '권한이 없습니다.'); } });
  await screen.findByTestId('admin-denied');
  assert.equal(meCalls(f.calls).length, 2, '403 뒤 /users/me 재조회');
  assert.equal(adminCalls(f.calls).length, 1, '4xx는 재시도하지 않는다');
});

test('403인데 내 정보가 여전히 ADMIN이면(토큰이 옛 역할) 다시 로그인 안내를 보인다', async () => {
  const f = mount({ screen: 'admin', me: () => ADMIN, pending: () => fail(403, 'COMMON_FORBIDDEN', '권한이 없습니다.') });
  await screen.findByTestId('admin-forbidden');
  await waitFor(() => assert.equal(meCalls(f.calls).length, 2));
  screen.getByTestId('admin-forbidden'); // 재조회 뒤에도 안내 유지 (없으면 throw)
  assertAbsent(screen.queryByTestId('admin-pending-count'));
  fireEvent.click(screen.getByRole('button', { name: '로그인 화면으로' }));
  assert.deepEqual(f.navigation, ['login']);
});

test('그 밖의 오류는 실패 문구를 보이고 다시 시도하면 다시 요청한다', async () => {
  let failing = true;
  const f = mount({ screen: 'admin', me: () => ADMIN, pending: () => failing ? fail(400, 'COMMON_INVALID_INPUT', '입력값이 올바르지 않습니다.') : pendingPage(2) });
  await screen.findByText('답변 대기 문의를 불러오지 못했어요');
  assertAbsent(screen.queryByTestId('admin-forbidden'));
  failing = false;
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
  await waitFor(() => assert.equal(screen.getByTestId('admin-pending-count').textContent, '2'));
  assert.equal(adminCalls(f.calls).length, 2);
});

test('로그아웃하면 관리자 캐시를 비운다', async () => {
  const f = mount({ screen: 'admin', me: () => ADMIN, pending: () => pendingPage(5) });
  await waitFor(() => assert.equal(screen.getByTestId('admin-pending-count').textContent, '5'));
  assert.equal(f.queryClient.getQueryData(adminKeys.pendingSupports(ADMIN.id)), 5);
  f.client.session.signOut();
  await waitFor(() => assert.equal(f.queryClient.getQueryData(adminKeys.pendingSupports(ADMIN.id)), undefined));
  await screen.findByRole('button', { name: '로그인' });
});

test('isAdminForbidden은 403 COMMON_FORBIDDEN만 권한 거절로 본다', () => {
  assert.equal(isAdminForbidden(new ApiError({ kind: 'http', status: 403, code: 'COMMON_FORBIDDEN' })), true);
  assert.equal(isAdminForbidden(new ApiError({ kind: 'http', status: 403, code: 'USER_SUSPENDED' })), false);
  assert.equal(isAdminForbidden(new ApiError({ kind: 'http', status: 401, code: 'COMMON_UNAUTHORIZED' })), false);
  assert.equal(isAdminForbidden(new Error('x')), false);
});

test('계약 센서: 관리자 경로 권한, 거절 코드, 문의 목록 응답 필드와 진입 연결을 유지한다', () => {
  const main = new URL('../../src/main/java/com/back/nbe1214finalsour/', import.meta.url);
  const security = readJava(new URL('global/security/SecurityConfig.java', main));
  assert.match(security, /requestMatchers\("\/api\/v1\/admin\/\*\*"\)\.hasRole\(Role\.ADMIN\.name\(\)\)/);
  const denied = readJava(new URL('global/security/ApiAccessDeniedHandler.java', main));
  assert.match(denied, /ErrorCode\.COMMON_FORBIDDEN/);
  const controller = readJava(new URL('domain/support/controller/AdminSupportController.java', main));
  assert.match(controller, /@RequestMapping\("\/api\/v1\/admin\/supports"\)/);
  assert.match(controller, /SupportStatus status/);
  const list = readJava(new URL('domain/support/dto/AdminSupportListResponse.java', main));
  assert.match(list, /List<AdminSupportListItem> items,\s*int page,\s*int size,\s*long totalCount,\s*boolean hasNext/);
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(app, /onOpenAdmin=\{\(\) => setViewMode\('admin'\)\}/);
});
