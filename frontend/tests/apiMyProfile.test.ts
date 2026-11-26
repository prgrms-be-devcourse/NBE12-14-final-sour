// SCRUM-166: my account screen. GET /users/me shows the profile; PATCH /users/me changes the nickname, which reaches
// the game's player (ApiPlayerSync, the HUD's source) without the answer's scores; 409 shows the duplicate notice.
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
const { userKeys } = await import('../src/api/users');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { ApiPlayerSync } = await import('../src/components/ApiPlayerSync');
const { MyInfoScreenPhone } = await import('../src/components/MyInfoScreenPhone');
const { ApiLoginPhone } = await import('../src/components/onboarding/ApiLoginPhone');
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

const STUDENT: MyInfo = {
  id: 3,
  nickname: '카이새내기',
  email: 'student@kaist.ac.kr',
  role: 'USER',
  status: 'ACTIVE',
  profileImageUrl: null,
  university: { id: SCHOOL_ID.kaist, name: 'KAIST', logoUrl: null },
  department: { id: 41, name: '전산학부' },
  clicker: { level: 2, clickPower: 3, nextLevelRequiredScore: 50 },
  totalContribScore: 1234,
  totalAttackScore: 7,
};

function PlayerProbe() {
  return React.createElement('span', { 'data-testid': 'player-nickname' }, useMockGame().player.nickname);
}

interface Patch { auth: string | null; body: unknown }
function mount({ signedIn = true, patch }: { signedIn?: boolean; patch?: (body: { nickname: string }) => Response } = {}) {
  const patches: Patch[] = [];
  const gets: string[] = [];
  const navigation: string[] = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    const route = `${init?.method ?? 'GET'} ${new URL(String(input), 'http://localhost').pathname}`;
    if (route === 'GET /api/v1/users/me') { gets.push(route); return ok(STUDENT); }
    if (route === 'PATCH /api/v1/users/me') {
      const body = JSON.parse(String(init?.body)) as { nickname: string };
      patches.push({ auth: new Headers(init?.headers).get('Authorization'), body });
      return patch ? patch(body) : fail(500, 'COMMON_INTERNAL_ERROR', '없음');
    }
    throw new Error(`Unexpected ${route}`);
  }) as typeof fetch });
  if (signedIn) client.session.signIn('access-1');
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  render(React.createElement(ApiProvider, { client, queryClient, children: React.createElement(MockGameProvider, { children: [
    React.createElement(ApiPlayerSync, { key: 'sync' }),
    React.createElement(PlayerProbe, { key: 'probe' }),
    React.createElement(MyInfoScreenPhone, { key: 'screen', onBack: () => navigation.push('back'), onGoToLogin: () => navigation.push('login') }),
  ] }) }));
  return { patches, gets, navigation, queryClient };
}
const nicknameField = () => screen.getByLabelText('닉네임') as HTMLInputElement;
const saveButton = () => screen.getByRole('button', { name: '닉네임 저장' }) as HTMLButtonElement;
const changeNickname = (value: string) => fireEvent.change(nicknameField(), { target: { value } });
const errorText = () => screen.queryByTestId('my-info-error')?.textContent ?? '';

test('내 정보를 보이고, 닉네임을 바꾸면 PATCH 응답의 닉네임만 캐시와 게임 플레이어에 반영한다', async () => {
  // The answer carries an older score than the cache: a click batch landed while the PATCH was on its way.
  const f = mount({ patch: ({ nickname }) => ok({ ...STUDENT, nickname, totalContribScore: 1000 }) });
  await waitFor(() => assert.equal(screen.getByTestId('my-info-nickname').textContent, '카이새내기'));
  assert.equal(screen.getByTestId('my-info-email').textContent, 'student@kaist.ac.kr');
  assert.equal(screen.getByTestId('my-info-university').textContent, 'KAIST');
  assert.equal(screen.getByTestId('my-info-department').textContent, '전산학부');
  assert.equal(screen.getByTestId('my-info-clicker').textContent, 'Lv.2 · 클릭당 3점');
  assert.equal(screen.getByTestId('my-info-contribution').textContent, '1,234점');
  assert.equal(screen.getByTestId('my-info-attack').textContent, '7점');
  await waitFor(() => assert.equal(screen.getByTestId('player-nickname').textContent, '카이새내기'));
  assert.equal(nicknameField().value, '카이새내기');
  assert.equal(saveButton().disabled, true, '바뀐 것이 없으면 저장할 수 없다');

  changeNickname('카이고학번');
  fireEvent.click(saveButton());
  await waitFor(() => assert.match(screen.getByTestId('my-info-result').textContent ?? '', /닉네임을 바꿨어요/));
  assert.deepEqual(f.patches, [{ auth: 'Bearer access-1', body: { nickname: '카이고학번' } }]);
  assert.equal(screen.getByTestId('my-info-nickname').textContent, '카이고학번');
  await waitFor(() => assert.equal(screen.getByTestId('player-nickname').textContent, '카이고학번', 'HUD의 출처인 게임 플레이어에 반영'));
  const cached = f.queryClient.getQueryData<MyInfo>(userKeys.me);
  assert.equal(cached?.nickname, '카이고학번');
  assert.equal(cached?.totalContribScore, 1234, '응답의 점수로 캐시를 되돌리지 않는다');
  assert.equal(nicknameField().value, '카이고학번');
  assert.equal(saveButton().disabled, true);
});

test('409 닉네임 중복이면 중복 문구를 보이고 닉네임을 바꾸지 않는다', async () => {
  const f = mount({ patch: () => fail(409, 'USER_NICKNAME_DUPLICATED', '이미 사용 중인 닉네임입니다.') });
  await waitFor(() => assert.ok(screen.getByTestId('my-info-nickname')));
  changeNickname('남의닉네임');
  fireEvent.click(saveButton());
  await waitFor(() => assert.equal(errorText(), '이미 사용 중인 닉네임이에요. 다른 닉네임을 입력해 주세요.'));
  assert.equal(f.patches.length, 1);
  assert.equal(nicknameField().value, '남의닉네임', '고쳐 쓸 수 있게 입력은 남긴다');
  assert.equal(nicknameField().getAttribute('aria-invalid'), 'true');
  assert.equal(screen.getByTestId('player-nickname').textContent, '카이새내기');
  assert.equal(f.queryClient.getQueryData<MyInfo>(userKeys.me)?.nickname, '카이새내기');
  changeNickname('남의닉네임2');
  assert.equal(errorText(), '', '다시 입력하면 오류를 지운다');
});

test('규칙에 어긋나면 보내지 않고, 서버의 400 필드 오류는 그 문구를 보인다', async () => {
  const f = mount({ patch: () => fail(400, 'COMMON_INVALID_INPUT', '입력값이 올바르지 않습니다.', [{ field: 'nickname', reason: '닉네임은 앞뒤 공백 없이 2~12자여야 합니다.' }]) });
  await waitFor(() => assert.ok(screen.getByTestId('my-info-nickname')));
  for (const bad of [' 앞공백', '가']) {
    changeNickname(bad);
    fireEvent.click(saveButton());
    assert.match(errorText(), /앞뒤 공백 없이 2~12자/, bad);
  }
  assert.equal(f.patches.length, 0, '규칙 위반은 요청하지 않는다');
  changeNickname('서버가거절');
  fireEvent.click(saveButton());
  await waitFor(() => assert.equal(errorText(), '닉네임은 앞뒤 공백 없이 2~12자여야 합니다.'));
});

test('로그아웃 상태면 로그인 안내를 보이고 조회하지 않는다', async () => {
  const f = mount({ signedIn: false });
  assert.match(screen.getByRole('status').textContent ?? '', /로그인하면 내 정보를 볼 수 있어요/);
  fireEvent.click(screen.getByRole('button', { name: '로그인' }));
  fireEvent.click(screen.getByRole('button', { name: '뒤로가기' }));
  assert.deepEqual(f.navigation, ['login', 'back']);
  assert.deepEqual(f.gets, []);
});

test('로그인된 계정 화면의 "내 정보" 버튼이 내 정보 화면을 연다', async () => {
  const client = createApiClient({ fetch: (async () => ok(STUDENT)) as typeof fetch });
  client.session.signIn('access-1');
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  const opened: string[] = [];
  render(React.createElement(ApiProvider, { client, queryClient, children: React.createElement(ApiLoginPhone, {
    onGoToSignup: () => undefined, onGoToCampus: () => undefined, onOpenMyInfo: () => opened.push('my-info'),
  }) }));
  fireEvent.click(await screen.findByRole('button', { name: '내 정보' }));
  assert.deepEqual(opened, ['my-info']);
});

test('계약 센서: PATCH /users/me는 닉네임만 받고 내 정보 형식으로 답하며, 중복은 409다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/', import.meta.url);
  const controller = readJava(new URL('domain/user/controller/UserController.java', root));
  assert.match(controller, /@RequestMapping\("\/api\/v1\/users"\)/);
  assert.match(controller, /@PatchMapping\("\/me"\)\s+public ApiResponse<MyInfoResponse> updateProfile/);
  const request = readJava(new URL('domain/user/dto/UpdateProfileRequest.java', root));
  assert.match(request, /record UpdateProfileRequest\(\s*@Nickname String nickname\)/, '학과·이미지 필드가 생기면 화면도 바꾼다');
  const codes = readJava(new URL('global/exception/ErrorCode.java', root));
  assert.match(codes, /USER_NICKNAME_DUPLICATED\(HttpStatus\.CONFLICT, "USER_NICKNAME_DUPLICATED"/);
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(app, /<MyInfoScreenPhone /);
  assert.match(app, /onOpenMyInfo=\{\(\) => setViewMode\('my_info'\)\}/);
});
