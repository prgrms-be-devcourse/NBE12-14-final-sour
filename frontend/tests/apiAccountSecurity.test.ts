// SCRUM-167: password change and withdrawal on my account screen. A 204 closes this browser's session with the
// reason, the session watcher opens the login screen, and the login form shows why.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React, { useState } from 'react';
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
const { createAuthSession, ACCOUNT_CLOSE_MESSAGES } = await import('../src/api/session');
const { userKeys } = await import('../src/api/users');
const { MyInfoScreenPhone } = await import('../src/components/MyInfoScreenPhone');
const { ApiLoginPhone, ApiSessionWatcher } = await import('../src/components/onboarding/ApiLoginPhone');
const { SCHOOL_ID } = await import('../src/data/schoolIds');
type MyInfo = import('../src/api/users').MyInfo;

const queryClients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => {
  cleanup();
  for (const client of queryClients.splice(0)) {
    for (const mutation of client.getMutationCache().getAll()) mutation.destroy();
    client.clear();
  }
});
after(() => { assets.deregister(); dom.window.close(); });

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const ok = (data: unknown) => json(200, { code: 'SUCCESS', message: '요청이 성공했습니다.', data });
const fail = (status: number, code: string, message: string, data: unknown = null) => json(status, { code, message, data });
const noContent = () => new Response(null, { status: 204 });
const WRONG_PASSWORD = () => fail(400, 'USER_INVALID_PASSWORD', '비밀번호가 올바르지 않습니다.');

const STUDENT: MyInfo = {
  id: 3, nickname: '카이새내기', email: 'student@kaist.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: SCHOOL_ID.kaist, name: 'KAIST', logoUrl: null }, department: { id: 41, name: '전산학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 }, totalContribScore: 0, totalAttackScore: 0,
};
const OLD = 'OldPass1!';
const NEW = 'NewPass2@';

interface Sent { route: string; auth: string | null; body: unknown }
/** The app's routing in small: the watcher opens the login screen when the session ends with a reason. */
function mount(answers: Record<string, Array<() => Response>>) {
  const sent: Sent[] = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    const route = `${init?.method ?? 'GET'} ${new URL(String(input), 'http://localhost').pathname}`;
    if (route === 'POST /api/v1/auth/reissue') return ok({ accessToken: 'access-1', expiresInSeconds: 3600 });
    if (route === 'GET /api/v1/users/me') return ok(STUDENT);
    sent.push({ route, auth: new Headers(init?.headers).get('Authorization'), body: init?.body ? JSON.parse(String(init.body)) : null });
    const answer = answers[route]?.shift();
    if (!answer) throw new Error(`Unexpected ${route}`);
    return answer();
  }) as typeof fetch });
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  function Router() {
    const [view, setView] = useState<'my_info' | 'login'>('my_info');
    return React.createElement(React.Fragment, null,
      React.createElement(ApiSessionWatcher, { onEnded: () => setView('login') }),
      view === 'my_info'
        ? React.createElement(MyInfoScreenPhone, { onBack: () => undefined, onGoToLogin: () => setView('login') })
        : React.createElement(ApiLoginPhone, { onGoToSignup: () => undefined, onGoToCampus: () => undefined }));
  }
  render(React.createElement(ApiProvider, { client, queryClient, children: React.createElement(Router) }));
  return { sent, client, queryClient };
}
const fill = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
const text = (id: string) => screen.queryByTestId(id)?.textContent ?? '';
const ready = () => screen.findByTestId('my-info-nickname');

test('세션 close: 로그인 상태에서만 이유와 안내 문구를 남기고 끝낸다', () => {
  const session = createAuthSession();
  session.close('withdrawn');
  assert.deepEqual(session.getState(), { status: 'signed-out', ended: null }, '로그아웃 상태면 아무것도 하지 않는다');
  session.signIn('t');
  session.close('password-changed');
  assert.deepEqual(session.getState(), {
    status: 'signed-out',
    ended: { reason: 'password-changed', code: null, message: '비밀번호를 바꿨어요. 새 비밀번호로 다시 로그인해 주세요.' },
  });
  session.signIn('t2');
  assert.deepEqual(session.getState(), { status: 'signed-in', accessToken: 't2' }, '다시 로그인하면 안내는 사라진다');
});

test('비밀번호 변경: 틀린 현재 비밀번호는 오류, 204면 세션을 닫고 로그인 화면에 안내를 보인다', async () => {
  const f = mount({ 'PATCH /api/v1/users/me/password': [WRONG_PASSWORD, noContent] });
  await ready();
  fill('현재 비밀번호', 'Wrong1!x');
  fill('새 비밀번호', NEW);
  fill('새 비밀번호 확인', NEW);
  click('비밀번호 바꾸기');
  await waitFor(() => assert.equal(text('password-change-error'), '현재 비밀번호가 올바르지 않아요.'));
  assert.equal(f.client.session.getState().status, 'signed-in', '실패하면 로그인은 그대로');

  fill('현재 비밀번호', OLD);
  click('비밀번호 바꾸기');
  await waitFor(() => assert.match(screen.getByRole('heading', { name: '로그인' }).textContent ?? '', /로그인/));
  assert.ok(screen.getByText(ACCOUNT_CLOSE_MESSAGES['password-changed']), '로그인 폼에 이유를 보인다');
  assert.deepEqual(f.sent, [
    { route: 'PATCH /api/v1/users/me/password', auth: 'Bearer access-1', body: { currentPassword: 'Wrong1!x', newPassword: NEW } },
    { route: 'PATCH /api/v1/users/me/password', auth: 'Bearer access-1', body: { currentPassword: OLD, newPassword: NEW } },
  ]);
  const state = f.client.session.getState();
  assert.equal(state.status === 'signed-out' && state.ended?.reason, 'password-changed');
  await waitFor(() => assert.equal(f.queryClient.getQueryData(userKeys.me), undefined, '이전 회원의 내 정보를 비운다'));
});

test('비밀번호 변경: 규칙 위반·확인 불일치·현재 비밀번호 없음은 요청하지 않고, 서버 필드 오류는 그 문구를 보인다', async () => {
  const f = mount({ 'PATCH /api/v1/users/me/password': [() => fail(400, 'COMMON_INVALID_INPUT', '입력값이 올바르지 않습니다.', [{ field: 'newPassword', reason: '비밀번호 규칙 위반' }])] });
  await ready();
  click('비밀번호 바꾸기');
  assert.equal(text('password-change-error'), '현재 비밀번호를 입력해 주세요.');
  fill('현재 비밀번호', OLD);
  fill('새 비밀번호', 'short');
  fill('새 비밀번호 확인', 'short');
  click('비밀번호 바꾸기');
  assert.match(text('password-change-error'), /공백 없이 8~64자/);
  fill('새 비밀번호', NEW);
  fill('새 비밀번호 확인', `${NEW}x`);
  click('비밀번호 바꾸기');
  assert.equal(text('password-change-error'), '비밀번호 확인이 일치하지 않습니다.');
  assert.equal(f.sent.length, 0);
  fill('새 비밀번호 확인', NEW);
  click('비밀번호 바꾸기');
  await waitFor(() => assert.equal(text('password-change-error'), '비밀번호 규칙 위반'));
});

test('회원 탈퇴: 확인 체크 전에는 누를 수 없고, 틀린 비밀번호는 오류, 204면 세션을 닫고 로그인 화면에 안내를 보인다', async () => {
  const f = mount({ 'POST /api/v1/users/me/withdrawal': [WRONG_PASSWORD, noContent] });
  await ready();
  const button = () => screen.getByRole('button', { name: '탈퇴하기' }) as HTMLButtonElement;
  fill('탈퇴 확인용 비밀번호', 'Wrong1!x');
  assert.equal(button().disabled, true, '확인 체크 전');
  fireEvent.click(screen.getByLabelText('되돌릴 수 없다는 것을 확인했어요'));
  assert.equal(button().disabled, false);
  click('탈퇴하기');
  await waitFor(() => assert.equal(text('withdrawal-error'), '비밀번호가 올바르지 않아요.'));
  assert.equal(f.client.session.getState().status, 'signed-in');

  fill('탈퇴 확인용 비밀번호', OLD);
  click('탈퇴하기');
  await waitFor(() => assert.ok(screen.getByText(ACCOUNT_CLOSE_MESSAGES.withdrawn)));
  assert.deepEqual(f.sent.map((s) => s.body), [{ password: 'Wrong1!x' }, { password: OLD }]);
  assert.ok(f.sent.every((s) => s.auth === 'Bearer access-1'));
  const state = f.client.session.getState();
  assert.equal(state.status === 'signed-out' && state.ended?.reason, 'withdrawn');
});

test('회원 탈퇴: 정지 회원(403 USER_SUSPENDED)은 정지 안내와 함께 세션이 끝난다', async () => {
  const f = mount({ 'POST /api/v1/users/me/withdrawal': [() => fail(403, 'USER_SUSPENDED', '정지된 계정입니다.')] });
  await ready();
  fill('탈퇴 확인용 비밀번호', OLD);
  fireEvent.click(screen.getByLabelText('되돌릴 수 없다는 것을 확인했어요'));
  click('탈퇴하기');
  await waitFor(() => assert.ok(screen.getByText('정지된 계정입니다.')));
  const state = f.client.session.getState();
  assert.equal(state.status === 'signed-out' && state.ended?.reason, 'suspended');
});

test('계약 센서: 비밀번호 변경·탈퇴 경로, 요청 필드, 204와 쿠키 만료, 오류 코드가 백엔드와 같다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/', import.meta.url);
  const controller = readFileSync(new URL('domain/user/controller/UserController.java', root), 'utf8');
  assert.match(controller, /@PatchMapping\("\/me\/password"\)\s+public ResponseEntity<Void> changePassword/);
  assert.match(controller, /@PostMapping\("\/me\/withdrawal"\)\s+public ResponseEntity<Void> withdraw/);
  assert.equal(controller.match(/ResponseEntity\.noContent\(\)\s+\.header\(HttpHeaders\.SET_COOKIE, RefreshTokenCookies\.expire\(\)/g)?.length, 2);
  const change = readFileSync(new URL('domain/user/dto/ChangePasswordRequest.java', root), 'utf8');
  assert.match(change, /record ChangePasswordRequest\(\s*@NotBlank @Size\(max = 64\) String currentPassword,\s*@Password String newPassword\)/);
  const leave = readFileSync(new URL('domain/user/dto/WithdrawalRequest.java', root), 'utf8');
  assert.match(leave, /record WithdrawalRequest\(@NotBlank @Size\(max = 64\) String password\)/);
  const codes = readFileSync(new URL('global/exception/ErrorCode.java', root), 'utf8');
  assert.match(codes, /USER_INVALID_PASSWORD\(HttpStatus\.BAD_REQUEST, "USER_INVALID_PASSWORD"/);
  assert.match(codes, /USER_SUSPENDED\(HttpStatus\.FORBIDDEN, "USER_SUSPENDED"/);
});
