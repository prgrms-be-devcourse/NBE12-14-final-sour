// SCRUM-174: member status change (suspend, ban, lift) and the abuse ranking. The server revokes a suspended member's
// session; the frontend only has to send the right PATCH after an explicit confirmation.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
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

const { render, screen, fireEvent, cleanup, waitFor, within } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { parseUserStatusTarget } = await import('../src/api/admin');
const { AdminScreenPhone } = await import('../src/components/AdminScreenPhone');
const { SCHOOL_ID } = await import('../src/data/schoolIds');
const { assertAbsent } = await import('./helpers/domAssert');
type MyInfo = import('../src/api/users').MyInfo;
type AbuseRankingItem = import('../src/api/admin').AbuseRankingItem;

const queryClients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => {
  cleanup();
  for (const client of queryClients.splice(0)) {
    // MutationCache.clear() keeps each mutation's 5-minute gc timer alive, so node would not exit; destroy() cancels it.
    for (const mutation of client.getMutationCache().getAll()) mutation.destroy();
    client.clear();
  }
});
after(() => { assets.deregister(); dom.window.close(); });

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const ok = (data: unknown) => json(200, { code: 'SUCCESS', message: '요청이 성공했습니다.', data });
const fail = (status: number, code: string, message = '실패') => json(status, { code, message, data: null });

const ADMIN: MyInfo = {
  id: 1, nickname: '운영자', email: 'admin@hanyang.ac.kr', role: 'ADMIN', status: 'ACTIVE', profileImageUrl: null,
  university: { id: SCHOOL_ID.hanyang, name: '한양대학교', logoUrl: null }, department: { id: 11, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 }, totalContribScore: 0, totalAttackScore: 0,
};

interface Call { route: string; search: URLSearchParams; body: unknown }
type Handler = (call: Call) => Response | undefined;

function mount({ ranking = [], handle }: { ranking?: AbuseRankingItem[]; handle?: Handler } = {}) {
  const calls: Call[] = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const call = { route: `${init?.method ?? 'GET'} ${url.pathname}`, search: url.searchParams, body: init?.body ? JSON.parse(String(init.body)) : undefined };
    calls.push(call);
    const custom = handle?.(call);
    if (custom) return custom;
    if (call.route === 'GET /api/v1/users/me') return ok(ADMIN);
    if (call.route === 'GET /api/v1/admin/supports') return ok({ items: [], page: 0, size: 1, totalCount: 0, hasNext: false });
    if (call.route === 'GET /api/v1/admin/abuse-rankings') {
      const page = Number(url.searchParams.get('page'));
      const size = Number(url.searchParams.get('size'));
      return ok({ content: ranking.slice(page * size, page * size + size), page, size, totalElements: ranking.length, hasNext: (page + 1) * size < ranking.length });
    }
    const patch = /^PATCH \/api\/v1\/admin\/users\/(\d+)\/status$/.exec(call.route);
    if (patch) return ok({ userId: Number(patch[1]), status: (call.body as { status: string }).status });
    throw new Error(`Unexpected ${call.route}`);
  }) as typeof fetch });
  client.session.signIn('access-1');
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  render(React.createElement(ApiProvider, { client, queryClient, children:
    React.createElement(AdminScreenPhone, { onBack: () => undefined, onGoToLogin: () => undefined, schoolName: { value: 'default', onChange: () => undefined } }) }));
  return { calls };
}
const patches = (calls: Call[]) => calls.filter(({ route }) => route.startsWith('PATCH ')).map(({ route, body }) => [route, body]);
const rank = (position: number, userId: number, nickname: string | null, abuseCount: number): AbuseRankingItem => ({ rank: position, userId, nickname, abuseCount });

async function open(menu: '회원 정지·해제' | '어뷰징 순위') {
  await screen.findByTestId('admin-pending-count');
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${menu}`) }));
  await screen.findByRole('heading', { name: menu });
}
const typeId = (value: string) => fireEvent.change(screen.getByLabelText('회원 ID'), { target: { value } });
const pick = (label: string) => fireEvent.click(screen.getByRole('radio', { name: label }));
const next = () => fireEvent.click(screen.getByRole('button', { name: '다음' }));

test('정지: 확인 단계를 거쳐서만 PATCH {status: SUSPENDED}를 보내고 결과를 보인다', async () => {
  const f = mount();
  await open('회원 정지·해제');
  typeId(' 42 ');
  pick('정지');
  next();
  const confirm = await screen.findByRole('group', { name: '상태 변경 확인' });
  within(confirm).getByText('회원 #42을(를) 정지할까요?');
  within(confirm).getByText(/바로 로그아웃되고/);
  assert.deepEqual(patches(f.calls), [], '확인 전에는 보내지 않는다');
  fireEvent.click(within(confirm).getByRole('button', { name: '정지하기' }));
  await waitFor(() => assert.equal(screen.getByTestId('admin-user-status-done').textContent, '회원 #42: 정지됨'));
  assert.deepEqual(patches(f.calls), [['PATCH /api/v1/admin/users/42/status', { status: 'SUSPENDED' }]]);
  assertAbsent(screen.queryByRole('group', { name: '상태 변경 확인' }));
});

test('취소하면 보내지 않고, 차단·해제도 각 상태로 보내며 기록을 최근 순으로 남긴다', async () => {
  const f = mount();
  await open('회원 정지·해제');
  typeId('7');
  pick('차단 (영구)');
  next();
  fireEvent.click(await screen.findByRole('button', { name: '취소' }));
  assert.deepEqual(patches(f.calls), []);
  next();
  fireEvent.click(await screen.findByRole('button', { name: '차단하기' }));
  await screen.findByTestId('admin-user-status-done');
  pick('해제');
  next();
  fireEvent.click(await screen.findByRole('button', { name: '해제하기' }));
  await waitFor(() => assert.equal(screen.getByTestId('admin-user-status-done').textContent, '회원 #7: 정상(해제됨)'));
  assert.deepEqual(patches(f.calls).map(([, body]) => body), [{ status: 'BANNED' }, { status: 'ACTIVE' }]);
  const log = screen.getByRole('region', { name: '이 화면에서 바꾼 기록' });
  assert.deepEqual([...log.querySelectorAll('li')].map((item) => item.textContent), ['회원 #7: 정상(해제됨)', '회원 #7: 차단됨']);
});

test('빈 ID·정수가 아닌 ID·내 ID·상태 미선택은 보내지 않고 안내한다', async () => {
  const f = mount();
  await open('회원 정지·해제');
  pick('정지');
  next();
  assert.equal((await screen.findByRole('alert')).textContent, '회원 ID를 입력해 주세요.');
  for (const value of ['0', '-3', '1.5', 'abc', '99999999999999999999']) {
    typeId(value);
    next();
    assert.equal((await screen.findByRole('alert')).textContent, '회원 ID는 1 이상의 정수로 입력해 주세요.', value);
    assert.equal(screen.getByLabelText('회원 ID').getAttribute('aria-invalid'), 'true');
  }
  typeId(String(ADMIN.id));
  next();
  assert.equal((await screen.findByRole('alert')).textContent, '내 계정의 상태는 바꿀 수 없어요.');
  assert.deepEqual(patches(f.calls), []);
});

test('상태를 고르지 않으면 안내한다', async () => {
  mount();
  await open('회원 정지·해제');
  typeId('5');
  next();
  assert.equal((await screen.findByRole('alert')).textContent, '바꿀 상태를 골라 주세요.');
  assert.equal(screen.getByLabelText('회원 ID').getAttribute('aria-invalid'), 'false');
});

test('404 USER_NOT_FOUND와 400은 확인 단계에 남아 안내하고, 다시 시도할 수 있다', async () => {
  let reply: () => Response = () => fail(404, 'USER_NOT_FOUND', '회원을 찾을 수 없습니다.');
  const f = mount({ handle: ({ route }) => route.startsWith('PATCH ') ? reply() : undefined });
  await open('회원 정지·해제');
  typeId('404');
  pick('정지');
  next();
  fireEvent.click(await screen.findByRole('button', { name: '정지하기' }));
  assert.equal((await screen.findByRole('alert')).textContent, '회원을 찾을 수 없어요. 없거나 탈퇴한 회원이에요.');
  reply = () => fail(400, 'COMMON_INVALID_INPUT', '입력값이 올바르지 않습니다.');
  fireEvent.click(screen.getByRole('button', { name: '정지하기' }));
  await waitFor(() => assert.match(screen.getByRole('alert').textContent ?? '', /^상태를 바꾸지 못했어요\./));
  assertAbsent(screen.queryByTestId('admin-user-status-done'));
  assert.equal(patches(f.calls).length, 2);
});

test('상태 변경이 403이면 권한 안내를 보이고 내 정보를 다시 읽는다', async () => {
  const f = mount({ handle: ({ route }) => route.startsWith('PATCH ') ? fail(403, 'COMMON_FORBIDDEN', '권한이 없습니다.') : undefined });
  await open('회원 정지·해제');
  typeId('9');
  pick('정지');
  next();
  fireEvent.click(await screen.findByRole('button', { name: '정지하기' }));
  await screen.findByTestId('admin-forbidden');
  await waitFor(() => assert.equal(f.calls.filter(({ route }) => route === 'GET /api/v1/users/me').length, 2));
});

test('어뷰징 순위: 20명씩 보이고, 닉네임이 없으면 알 수 없는 회원으로 표시한다', async () => {
  const ranking = Array.from({ length: 23 }, (_, index) => rank(index + 1, 100 + index, index === 1 ? null : `회원${index + 1}`, 1000 - index));
  const f = mount({ ranking });
  await open('어뷰징 순위');
  await waitFor(() => assert.equal(screen.getAllByTestId('admin-abuse-row').length, 20));
  const second = screen.getAllByTestId('admin-abuse-row')[1];
  within(second).getByText('2위 · 알 수 없는 회원');
  within(second).getByText('회원 #101 · 부정행위 999회');
  screen.getByText('1페이지 · 전체 23명');
  fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
  await waitFor(() => assert.equal(screen.getAllByTestId('admin-abuse-row').length, 3));
  assert.deepEqual(f.calls.filter(({ route }) => route === 'GET /api/v1/admin/abuse-rankings').map(({ search }) => search.toString()),
    ['page=0&size=20', 'page=1&size=20']);
});

test('순위가 비면 최근 3일 기록이 없다고 보인다', async () => {
  mount();
  await open('어뷰징 순위');
  await screen.findByText('최근 3일 동안 기록된 부정행위가 없어요.');
});

test('순위의 상태 변경은 회원 ID·닉네임을 채워 열고, 뒤로가기는 순위로 돌아간다', async () => {
  const f = mount({ ranking: [rank(1, 55, '클릭왕', 30)] });
  await open('어뷰징 순위');
  fireEvent.click(await screen.findByRole('button', { name: '회원 #55 (클릭왕) 상태 변경' }));
  await screen.findByRole('heading', { name: '회원 정지·해제' });
  assert.equal((screen.getByLabelText('회원 ID') as HTMLInputElement).value, '55');
  screen.getByText('어뷰징 순위에서 고른 회원: 회원 #55 (클릭왕)');
  pick('정지');
  next();
  fireEvent.click(await screen.findByRole('button', { name: '정지하기' }));
  await waitFor(() => assert.equal(screen.getByTestId('admin-user-status-done').textContent, '회원 #55 (클릭왕): 정지됨'));
  assert.deepEqual(patches(f.calls), [['PATCH /api/v1/admin/users/55/status', { status: 'SUSPENDED' }]]);
  fireEvent.click(screen.getByRole('button', { name: '어뷰징 순위로 돌아가기' }));
  await screen.findByRole('heading', { name: '어뷰징 순위' });
});

test('어뷰징 순위가 403이면 권한 안내를 보인다', async () => {
  mount({ handle: ({ route }) => route === 'GET /api/v1/admin/abuse-rankings' ? fail(403, 'COMMON_FORBIDDEN', '권한이 없습니다.') : undefined });
  await open('어뷰징 순위');
  await screen.findByTestId('admin-forbidden');
});

test('parseUserStatusTarget은 양의 안전한 정수만 받고 관리자 자신을 거른다', () => {
  assert.deepEqual(parseUserStatusTarget(' 12 ', 1), { userId: 12 });
  assert.deepEqual(parseUserStatusTarget('1', 1), { problem: '내 계정의 상태는 바꿀 수 없어요.' });
  for (const value of ['', ' ']) assert.deepEqual(parseUserStatusTarget(value, 1), { problem: '회원 ID를 입력해 주세요.' });
  for (const value of ['0', '+3', '1e3', '9007199254740993', '٣']) {
    assert.deepEqual(parseUserStatusTarget(value, 1), { problem: '회원 ID는 1 이상의 정수로 입력해 주세요.' }, value);
  }
});

test('계약 센서: 회원 상태 변경과 어뷰징 순위 계약이 바뀌면 화면을 다시 확인한다', () => {
  const main = new URL('../../src/main/java/com/back/nbe1214finalsour/', import.meta.url);
  const read = (path: string) => readFileSync(new URL(path, main), 'utf8');
  const fix = 'admin.ts 타입과 AdminUsersPanel 화면을 백엔드 계약에 맞춰 고친 뒤 이 센서를 갱신하세요.';
  const users = read('domain/user/controller/AdminUserController.java');
  assert.match(users, /@RequestMapping\("\/api\/v1\/admin\/users"\)/, fix);
  assert.match(users, /@PatchMapping\("\/\{userId\}\/status"\)/, fix);
  assert.match(read('domain/user/dto/AdminUserStatusRequest.java'), /record AdminUserStatusRequest\(@NotNull UserStatus status\)/, fix);
  assert.match(read('domain/user/dto/AdminUserStatusResponse.java'), /record AdminUserStatusResponse\(Long userId, UserStatus status\)/, fix);
  assert.match(read('domain/user/entity/UserStatus.java'), /ACTIVE,[\s\S]*SUSPENDED,[\s\S]*BANNED,[\s\S]*WITHDRAWN\s*\}/, fix);
  const service = read('domain/user/service/AdminUserService.java');
  assert.match(service, /userId\.equals\(adminId\)[\s\S]*COMMON_INVALID_INPUT/, fix);
  assert.match(service, /status != UserStatus\.ACTIVE\)\s*\{\s*refreshTokenService\.revokeAll\(userId\)/, fix);
  const abuse = read('domain/click/controller/AdminAbuseController.java');
  assert.match(abuse, /@RequestMapping\("\/api\/v1\/admin\/abuse-rankings"\)/, fix);
  assert.match(abuse, /@Min\(1\) @Max\(100\) int size/, fix);
  const ranking = read('domain/click/dto/AbuseRankingResponse.java');
  assert.match(ranking, /List<AbuseRankingItem> content,\s*int page,\s*int size,\s*long totalElements,\s*boolean hasNext/, fix);
  assert.match(ranking, /record AbuseRankingItem\(long rank, Long userId, String nickname, long abuseCount\)/, fix);
  assert.match(read('domain/click/service/AbuseRankingService.java'), /RANK_DAYS = 3;/, fix);
  assert.match(read('global/exception/ErrorCode.java'), /USER_NOT_FOUND\(HttpStatus\.NOT_FOUND, "USER_NOT_FOUND"/, fix);
});
