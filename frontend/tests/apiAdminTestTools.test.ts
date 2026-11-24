// SCRUM-175: the admin test tools. Request faults, the click refusal and the school name all stay in this browser,
// only an admin can reach them, and they go back to default when the session is no longer an admin's.
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

const { render, screen, fireEvent, cleanup, waitFor, within, act } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createApiFaults } = await import('../src/api/faults');
const { createQueryClient } = await import('../src/api/queryClient');
const { submitClicks } = await import('../src/api/clicks');
const { isClickLimitError } = await import('../src/api/abuse');
const { isApiError } = await import('../src/api/errors');
const { userKeys } = await import('../src/api/users');
const { AdminScreenPhone } = await import('../src/components/AdminScreenPhone');
const { ApiTestToolsNotice } = await import('../src/components/AdminTestToolsPanel');
const { SCHOOL_ID } = await import('../src/data/schoolIds');
const { assertAbsent } = await import('./helpers/domAssert');
type MyInfo = import('../src/api/users').MyInfo;
type DemoSchoolNameId = import('../src/data/demoSchoolNames').DemoSchoolNameId;

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

const ok = (data: unknown) => new Response(JSON.stringify({ code: 'SUCCESS', message: '요청이 성공했습니다.', data }), { status: 200 });

const ADMIN: MyInfo = {
  id: 1, nickname: '운영자', email: 'admin@hanyang.ac.kr', role: 'ADMIN', status: 'ACTIVE', profileImageUrl: null,
  university: { id: SCHOOL_ID.hanyang, name: '한양대학교', logoUrl: null }, department: { id: 11, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 }, totalContribScore: 0, totalAttackScore: 0,
};
const CLICK_RESPONSE = {
  acceptedClicks: 1, earnedScore: 1, multiplier: 1, myContribScore: 1, university: { id: SCHOOL_ID.hanyang, totalScore: 1, rank: 1, logoUrl: '' },
  department: { id: 11, totalScore: 1 }, schoolClicksLastSecond: 0, libraryBonus: false,
};

/** The app's school name state and the admin screen, with the root notice, as App mounts them in API mode. */
function Harness() {
  const [value, setValue] = React.useState<DemoSchoolNameId>('default');
  const schoolName = { value, onChange: setValue };
  return React.createElement(React.Fragment, null,
    React.createElement('p', { 'data-testid': 'header-school' }, value),
    React.createElement(ApiTestToolsNotice, { schoolName }),
    React.createElement(AdminScreenPhone, { onBack: () => undefined, onGoToLogin: () => undefined, schoolName }));
}

function mount() {
  const routes: string[] = [];
  const profile = { me: ADMIN };
  const faults = createApiFaults({ delayMs: 5, failDelayMs: 0 });
  const client = createApiClient({ faults, fetch: (async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const route = `${init?.method ?? 'GET'} ${url.pathname}`;
    routes.push(route);
    if (route === 'GET /api/v1/users/me') return ok(profile.me);
    if (route === 'GET /api/v1/admin/supports') return ok({ items: [], page: 0, size: 1, totalCount: 0, hasNext: false });
    if (route === 'POST /api/v1/clicks') return ok(CLICK_RESPONSE);
    throw new Error(`Unexpected ${route}`);
  }) as typeof fetch });
  client.session.signIn('access-1');
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  render(React.createElement(ApiProvider, { client, queryClient, children: React.createElement(Harness) }));
  const count = (route: string) => routes.filter((sent) => sent === route).length;
  return { client, faults, queryClient, profile, routes, count };
}

async function openTools() {
  await screen.findByTestId('admin-pending-count');
  fireEvent.click(screen.getByRole('button', { name: /^테스트 도구/ }));
  await screen.findByRole('heading', { name: '테스트 도구' });
}
const choose = (group: string, name: string) => fireEvent.click(within(screen.getByRole('group', { name: group })).getByRole('radio', { name }));
const probe = () => fireEvent.click(screen.getByRole('button', { name: '확인 요청 보내기' }));
const notice = () => screen.getByTestId('test-tools-active').textContent;

test('계속 실패를 주입하면 확인 요청이 서버에 가지 않고 실패하며, 해제하면 같은 요청이 정상으로 돌아온다', async () => {
  const f = mount();
  await openTools();
  assertAbsent(screen.queryByTestId('test-tools-active'), '기본값에서는 켜짐 표시가 없다');
  choose('다음 API 요청', '계속 실패');
  assert.equal(f.faults.getMode(), 'fail');
  assert.equal(notice(), '테스트 도구 켜짐: 요청 계속 실패모두 해제');
  const before = f.count('GET /api/v1/users/me');

  probe();
  const failed = await screen.findByRole('alert');
  assert.match(failed.textContent ?? '', /^실패 · \[테스트 도구\] 주입한 실패.* · 보낼 때 모드: 계속 실패$/);
  assert.equal(f.count('GET /api/v1/users/me'), before, '주입한 실패는 서버로 가지 않는다');
  assert.equal(f.faults.getMode(), 'fail', '계속 실패는 유지된다');

  choose('다음 API 요청', '정상');
  probe();
  await waitFor(() => assert.match(screen.getByTestId('admin-probe-result').textContent ?? '', /^성공 · 내 정보 조회\(GET \/users\/me\) [\d,]+ms · 보낼 때 모드: 정상$/));
  assert.equal(screen.getByTestId('admin-probe-result').getAttribute('role'), 'status');
  assert.equal(f.count('GET /api/v1/users/me'), before + 1, '해제한 뒤 확인 요청은 서버에 한 번 간다');
  assertAbsent(screen.queryByTestId('test-tools-active'), '해제하면 켜짐 표시가 사라진다');
});

test('1회 실패는 다음 요청 하나만 실패시키고 정상으로 돌아가며, 지연은 요청을 늦춰도 성공한다', async () => {
  const f = mount();
  await openTools();
  choose('다음 API 요청', '1회 실패');
  probe();
  await waitFor(() => assert.match(screen.getByTestId('admin-probe-result').textContent ?? '', /^실패 .* 보낼 때 모드: 1회 실패$/));
  assert.equal((within(screen.getByRole('group', { name: '다음 API 요청' })).getByRole('radio', { name: '정상' }) as HTMLInputElement).checked, true,
    '실패 한 번 뒤 선택이 정상으로 돌아온다');
  probe();
  await waitFor(() => assert.match(screen.getByTestId('admin-probe-result').textContent ?? '', /^성공 .* 보낼 때 모드: 정상$/));

  choose('다음 API 요청', '지연 1.5초');
  probe();
  await waitFor(() => assert.match(screen.getByTestId('admin-probe-result').textContent ?? '', /^성공 .* 보낼 때 모드: 지연 1\.5초$/));
  assert.equal(f.faults.getMode(), 'delay', '지연은 유지된다');
});

test('클릭 거부를 켜면 POST /clicks만 서버 대신 429 CLICK_TOO_FREQUENT로 끝나고, 끄면 서버로 간다', async () => {
  const f = mount();
  await openTools();
  fireEvent.click(screen.getByRole('checkbox', { name: '내 캠퍼스 클릭을 서버 대신 429로 거부' }));
  assert.equal(f.faults.getClickRefusal(), true);
  assert.equal(notice(), '테스트 도구 켜짐: 클릭 거부모두 해제');

  await assert.rejects(submitClicks(f.client, { submissionId: 's-1', clickCount: 3 }), (error) => {
    assert.ok(isClickLimitError(error), '기존 클릭 제한 안내 흐름을 그대로 탄다');
    assert.ok(isApiError(error) && error.kind === 'http' && error.serverMessage === '제출 요청이 너무 많습니다.');
    return true;
  });
  assert.equal(f.count('POST /api/v1/clicks'), 0, '거부한 클릭은 서버로 가지 않는다');
  probe();
  await waitFor(() => assert.match(screen.getByTestId('admin-probe-result').textContent ?? '', /^성공 /, '다른 요청은 그대로 간다'));

  fireEvent.click(screen.getByRole('checkbox', { name: '내 캠퍼스 클릭을 서버 대신 429로 거부' }));
  assert.equal((await submitClicks(f.client, { submissionId: 's-2', clickCount: 3 })).acceptedClicks, 1);
  assert.equal(f.count('POST /api/v1/clicks'), 1);
});

test('학교명 길이를 바꾸면 앱 상단 값이 바뀌고, 모두 해제는 주입·클릭 거부·학교명을 함께 되돌린다', async () => {
  const f = mount();
  await openTools();
  choose('내 캠퍼스 상단에 보일 학교명', '긴 이름 (한국방송통신대학교 프라임칼리지 서울캠퍼스)');
  assert.equal(screen.getByTestId('header-school').textContent, 'long');
  choose('다음 API 요청', '계속 실패');
  fireEvent.click(screen.getByRole('checkbox', { name: '내 캠퍼스 클릭을 서버 대신 429로 거부' }));
  assert.equal(notice(), '테스트 도구 켜짐: 요청 계속 실패 · 클릭 거부 · 학교명 긴 이름모두 해제');

  fireEvent.click(within(screen.getByTestId('test-tools-active')).getByRole('button', { name: '모두 해제' }));
  assert.equal(f.faults.getMode(), 'normal');
  assert.equal(f.faults.getClickRefusal(), false);
  assert.equal(screen.getByTestId('header-school').textContent, 'default');
  assertAbsent(screen.queryByTestId('test-tools-active'));
  assert.equal((screen.getByRole('button', { name: '모두 기본값이에요' }) as HTMLButtonElement).disabled, true);

  choose('내 캠퍼스 상단에 보일 학교명', '9자 (서울과학기술대학교)');
  const [inNotice, inPanel] = screen.getAllByRole('button', { name: '모두 해제' });
  assert.ok(within(screen.getByTestId('test-tools-active')).queryByRole('button') === inNotice);
  fireEvent.click(inPanel);
  assert.equal(screen.getByTestId('header-school').textContent, 'default', '도구 화면의 모두 해제도 같다');
});

test('로그아웃하면 모든 도구가 기본으로 돌아가, 같은 브라우저의 다음 회원에게 남지 않는다', async () => {
  const f = mount();
  await openTools();
  choose('다음 API 요청', '계속 실패');
  fireEvent.click(screen.getByRole('checkbox', { name: '내 캠퍼스 클릭을 서버 대신 429로 거부' }));
  choose('내 캠퍼스 상단에 보일 학교명', '9자 (서울과학기술대학교)');

  act(() => f.client.session.signOut());
  await waitFor(() => assert.equal(f.faults.getMode(), 'normal'));
  assert.equal(f.faults.getClickRefusal(), false);
  assert.equal(screen.getByTestId('header-school').textContent, 'default');
  assertAbsent(screen.queryByTestId('test-tools-active'));
});

test('관리자 권한이 사라지면 도구가 기본으로 돌아가고 메뉴에 들어갈 수 없다', async () => {
  const f = mount();
  await openTools();
  fireEvent.click(screen.getByRole('checkbox', { name: '내 캠퍼스 클릭을 서버 대신 429로 거부' }));
  choose('내 캠퍼스 상단에 보일 학교명', '9자 (서울과학기술대학교)');

  f.profile.me = { ...ADMIN, role: 'USER' };
  await act(() => f.queryClient.invalidateQueries({ queryKey: userKeys.me }));
  await waitFor(() => assert.equal(f.faults.getClickRefusal(), false));
  assert.equal(screen.getByTestId('header-school').textContent, 'default');
  await screen.findByTestId('admin-denied');
  assertAbsent(screen.queryByRole('heading', { name: '테스트 도구' }));
});

test('주입 규칙: 실패 주입이 클릭 거부보다 먼저이고, 클릭 거부는 POST /clicks에만 걸린다. reset은 한 번만 알린다', async () => {
  const faults = createApiFaults({ delayMs: 0, failDelayMs: 0 });
  let notified = 0;
  faults.subscribe(() => { notified++; });
  faults.setClickRefusal(true);
  await faults.beforeRequest(undefined, { method: 'GET', path: '/clicks' });
  await faults.beforeRequest(undefined, { method: 'POST', path: '/clickers/upgrade' });
  await assert.rejects(faults.beforeRequest(undefined, { method: 'POST', path: '/clicks' }), (error) => isClickLimitError(error));
  faults.setMode('fail');
  await assert.rejects(faults.beforeRequest(undefined, { method: 'POST', path: '/clicks' }), (error) => isApiError(error) && error.kind === 'injected');
  notified = 0;
  faults.reset();
  faults.reset();
  assert.equal(notified, 1);
  assert.deepEqual([faults.getMode(), faults.getClickRefusal()], ['normal', false]);
});

test('계약 센서: 클릭 거부 응답과 API 모드의 전역 [시연] 도구 정리', () => {
  const main = new URL('../../src/main/java/com/back/nbe1214finalsour/', import.meta.url);
  const read = (path: string) => readFileSync(new URL(path, main), 'utf8');
  const fix = 'faults.ts의 CLICK_REFUSAL·CLICK_REFUSAL_TARGET을 백엔드 계약에 맞춰 고친 뒤 이 센서를 갱신하세요.';
  const clicks = read('domain/click/controller/ClickController.java');
  assert.match(clicks, /@RequestMapping\("\/api\/v1\/clicks"\)/, fix);
  assert.match(clicks, /@PostMapping\s*\n/, fix);
  assert.match(read('global/exception/ErrorCode.java'),
    /CLICK_TOO_FREQUENT\(HttpStatus\.TOO_MANY_REQUESTS, "CLICK_TOO_FREQUENT", "제출 요청이 너무 많습니다\."\)/, fix);

  // API mode: the admin test tool owns these three; the mock keeps its [시연] controls.
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  const gate = app.indexOf("{API_MODE === 'mock' && <>");
  const end = app.indexOf('</>}', gate);
  assert.ok(gate >= 0 && end > gate, 'App.tsx의 [시연] 학교명·요청·클릭 거절은 목업 모드 전용 블록 안에 둔다.');
  for (const label of ['aria-label="[시연] 학교명"', 'aria-label="[시연] 요청 상태"', '[시연] 서버 클릭 거절 응답']) {
    const at = app.indexOf(label);
    assert.ok(at > gate && at < end, `${label}는 API 모드에서 숨긴다. 관리자 테스트 도구(SCRUM-175)가 대신한다.`);
  }
});
