import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { assertAbsent } from './helpers/domAssert';
import type { ClickRequest, ClickResponse } from '../src/api/clicks';
import type { MyInfo } from '../src/api/users';
import type { LocationFix } from '../src/utils/clickLocation';
import type { TurnstileApi, TurnstileLoader, TurnstileRenderOptions } from '../src/utils/turnstile';
import { installDialogStub } from './helpers/dialogStub';

const assets = registerHooks({
  load(url, context, nextLoad) {
    if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) {
      return { format: 'module', source: 'export default "/asset";', shortCircuit: true };
    }
    return nextLoad(url, context);
  },
});
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({
  window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, IS_REACT_ACT_ENVIRONMENT: true, isSecureContext: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });

installDialogStub(dom.window);

const { render, screen, within, fireEvent, cleanup, act, waitFor } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { userKeys } = await import('../src/api/users');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { useApiCampusClicks, useCampusClicks } = await import('../src/hooks/useCampusClicks');
const { ApiPlayerSync } = await import('../src/components/ApiPlayerSync');
const { ApiBotCheckDialog } = await import('../src/components/ApiBotCheckDialog');
const { ApiSessionWatcher } = await import('../src/components/onboarding/ApiLoginPhone');
const clients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => {
  cleanup();
  Reflect.deleteProperty(navigator, 'geolocation');
  for (const client of clients.splice(0)) {
    for (const mutation of client.getMutationCache().getAll()) mutation.destroy();
    client.clear();
  }
});
after(() => { dom.window.close(); assets.deregister(); });

const ME: MyInfo = {
  id: 10, nickname: '배치학생', email: 'test@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: 1, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 2, nextLevelRequiredScore: 100 }, totalContribScore: 10, totalAttackScore: 0,
};
const RESPONSE: ClickResponse = {
  acceptedClicks: 2, earnedScore: 4, multiplier: 1, myContribScore: 14,
  university: { id: 1, totalScore: 104, rank: 1, logoUrl: 'tempUrl' },
  department: { id: 7, totalScore: 24 }, schoolClicksLastSecond: 2, libraryBonus: false,
};
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status: 200 });
const fail = (status: number, code: string) => new Response(JSON.stringify({ code, message: '전송 오류', data: null }), { status });
const readGame = () => JSON.parse(screen.getByTestId('game').textContent ?? '{}') as { contribution: number; schoolScore: number; departmentScore: number };

function Probe() {
  const clicks = useApiCampusClicks();
  const game = useMockGame();
  const [onCampus, setOnCampus] = React.useState(true);
  return React.createElement(React.Fragment, null,
    React.createElement(ApiPlayerSync, { getPendingScore: clicks.getPendingScore }),
    React.createElement('output', { 'data-testid': 'game' }, JSON.stringify(game.state)),
    clicks.library && React.createElement(React.Fragment, null,
      React.createElement('output', { 'data-testid': 'library' }, JSON.stringify({ location: clicks.library.location, result: clicks.library.lastResult })),
      React.createElement('button', { onClick: clicks.library.request }, '위치 확인'),
      React.createElement('button', { onClick: clicks.library.clear }, '위치 중지')),
    clicks.notice && React.createElement('p', { role: 'alert' }, clicks.notice),
    clicks.retryLimited && React.createElement('button', { onClick: clicks.retryLimited }, '거절 배치 제외'),
    clicks.botCheck && React.createElement('button', { onClick: clicks.botCheck.onOpen }, '확인 열기'),
    clicks.botCheck?.open && React.createElement(ApiBotCheckDialog, { onVerified: clicks.botCheck.onVerified, onClose: clicks.botCheck.onClose }),
    onCampus && React.createElement('button', { onClick: () => clicks.tap(false) }, '클릭'),
    React.createElement('button', { onClick: () => clicks.tap(true) }, '치명타'),
    React.createElement('button', { onClick: () => setOnCampus((value) => !value) }, '탭 전환'),
  );
}

test('클릭 점수 복구 중에는 자동 재전송과 미확정 횟수를 안내한다', async () => {
  mount(async () => fail(503, 'RESTORE_SCORE_RESTORING'));
  await ready();
  fireEvent.click(screen.getByRole('button', { name: '클릭' }));
  await waitFor(() => assert.match(screen.getByRole('alert').textContent ?? '', /점수 복구 중.*자동.*미확정.*1회/), { timeout: 2500 });
});

const idleLoader: TurnstileLoader = async () => ({ render: () => 'idle', reset: () => undefined, remove: () => undefined });
function fakeTurnstile() {
  const widgets: TurnstileRenderOptions[] = [];
  const resets: string[] = [];
  const removed: string[] = [];
  const api: TurnstileApi = {
    render: (_container, options) => { widgets.push(options); return String(widgets.length); },
    reset: (id) => { resets.push(id ?? ''); },
    remove: (id) => { removed.push(id ?? ''); },
  };
  return { widgets, resets, removed, loader: async () => api };
}

function mount(send: (request: ClickRequest) => Promise<Response> = async () => ok(RESPONSE), signedIn = true,
  options: { loader?: TurnstileLoader; verify?: (token: string, signal?: AbortSignal | null) => Promise<Response> } = {}) {
  const sent: ClickRequest[] = [];
  const tokens: Array<string | null> = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    const path = String(input);
    if (path.endsWith('/users/me')) return ok(ME);
    if (path.endsWith('/clicks')) {
      const body = JSON.parse(String(init?.body)) as ClickRequest;
      sent.push(body);
      tokens.push(new Headers(init?.headers).get('Authorization'));
      return send(body);
    }
    if (path.endsWith('/abuse/verification')) {
      if (!options.verify) throw new Error('Unexpected bot verification');
      return options.verify((JSON.parse(String(init?.body)) as { turnstileToken: string }).turnstileToken, init?.signal);
    }
    if (path.endsWith('/auth/reissue')) return signedIn
      ? ok({ accessToken: 'member-a', expiresInSeconds: 3600 })
      : fail(401, 'AUTH_INVALID_REFRESH_TOKEN');
    throw new Error(`Unexpected request: ${path}`);
  }) as typeof fetch });
  if (signedIn) client.session.signIn('member-a');
  const queryClient = createQueryClient();
  clients.push(queryClient);
  render(React.createElement(React.StrictMode, null, React.createElement(ApiProvider, {
    client, queryClient, children: React.createElement(MockGameProvider, {
      turnstileLoader: options.loader ?? idleLoader,
      children: [React.createElement(ApiSessionWatcher, { key: 'watcher', onEnded: () => undefined }), React.createElement(Probe, { key: 'probe' })],
    }),
  })));
  return { client, queryClient, sent, tokens };
}
const ready = async () => waitFor(() => assert.equal(readGame().contribution, 10));
const click = () => fireEvent.click(screen.getByRole('button', { name: '클릭' }));

function locationPlatform() {
  const callbacks: Array<(fix: LocationFix) => void> = [];
  Object.defineProperty(navigator, 'geolocation', { configurable: true, value: {
    getCurrentPosition: (success: (fix: LocationFix) => void) => { callbacks.push(success); },
  } });
  const fix = (): LocationFix => ({ timestamp: Date.now(), coords: { latitude: 37.5, longitude: 127, accuracy: 10 } });
  return { callbacks, fix };
}

test('위치 대기 중에도 일반 클릭을 보내며 성공 뒤 좌표를 붙이고 보너스는 서버 점수만 반영한다', async () => {
  const geo = locationPlatform();
  let contribution = 10;
  const { sent } = mount(async (request) => {
    const bonus = request.latitude !== undefined;
    const score = Math.floor(2 * request.clickCount * (bonus ? 1.2 : 1));
    contribution += score;
    return ok({ ...RESPONSE, acceptedClicks: request.clickCount, earnedScore: score, myContribScore: contribution,
      university: { ...RESPONSE.university, totalScore: contribution + 90 }, department: { id: 7, totalScore: contribution + 10 },
      libraryBonus: bonus, multiplier: bonus ? 1.2 : 1 });
  });
  await ready();
  assert.equal(geo.callbacks.length, 0);
  fireEvent.click(screen.getByRole('button', { name: '위치 확인' }));
  click();
  await waitFor(() => assert.equal(sent.length, 1), { timeout: 2500 });
  assert.deepEqual(Object.keys(sent[0]), ['submissionId', 'clickCount']);
  await act(async () => geo.callbacks[0](geo.fix()));
  for (let i = 0; i < 5; i++) click();
  assert.equal(readGame().contribution, 22, '미확정 점수에 임의로1.2를 곱하지 않는다');
  await waitFor(() => assert.equal(readGame().contribution, 24), { timeout: 2500 });
  assert.equal(sent[1].latitude, 37.5);
  assert.equal(sent[1].longitude, 127);
  assert.equal(readGame().schoolScore, 114);
  assert.equal(readGame().departmentScore, 34);
  assert.deepEqual(JSON.parse(screen.getByTestId('library').textContent!).result, { libraryBonus: true, multiplier: 1.2 });
  fireEvent.click(screen.getByRole('button', { name: '위치 중지' }));
  click();
  await waitFor(() => assert.equal(sent.length, 3), { timeout: 2500 });
  assert.deepEqual(Object.keys(sent[2]), ['submissionId', 'clickCount']);
});

test('세션 변경은 이전 위치·보너스를 비우고 늦은 위치 콜백을 다음 회원 클릭에 사용하지 않는다', async () => {
  const geo = locationPlatform();
  const { client, queryClient, sent, tokens } = mount();
  await ready();
  fireEvent.click(screen.getByRole('button', { name: '위치 확인' }));
  await act(async () => {
    client.session.signOut(); client.session.signIn('member-b');
    queryClient.setQueryData<MyInfo>(userKeys.me, { ...ME, id: 11, totalContribScore: 100 });
  });
  await waitFor(() => assert.equal(readGame().contribution, 100));
  await act(async () => geo.callbacks[0](geo.fix()));
  assert.deepEqual(JSON.parse(screen.getByTestId('library').textContent!), { location: { status: 'idle', accuracy: null }, result: null });
  click();
  await waitFor(() => assert.equal(sent.length, 1), { timeout: 2500 });
  assert.deepEqual(tokens, ['Bearer member-b']);
  assert.deepEqual(Object.keys(sent[0]), ['submissionId', 'clickCount']);
});

test('API 탭은 실제 clickPower로 낙관 반영한 뒤 세 점수를 응답으로 보정하고 프로필 캐시도 갱신한다', async () => {
  const { sent, tokens, queryClient } = mount();
  await ready();
  click(); click();
  assert.equal(readGame().contribution, 14);
  assert.equal(sent.length, 0);
  const pending = new dom.window.Event('beforeunload', { cancelable: true });
  window.dispatchEvent(pending);
  assert.equal(pending.defaultPrevented, true);
  await waitFor(() => assert.equal(readGame().schoolScore, 104), { timeout: 2500 });
  assert.deepEqual(readGame(), { contribution: 14, clickerId: 1, schoolScore: 104, departmentScore: 24, activeBuff: null });
  assert.equal(queryClient.getQueryData<MyInfo>(userKeys.me)?.totalContribScore, 14);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].clickCount, 2);
  assert.deepEqual(tokens, ['Bearer member-a']);
  const settled = new dom.window.Event('beforeunload', { cancelable: true });
  window.dispatchEvent(settled);
  assert.equal(settled.defaultPrevented, false);
  assertAbsent(screen.queryByRole('alert'));
});

test('느린 응답 중 새 클릭·내 정보 재조회·탭 이동에도 미확정 점수를 보존한다', async () => {
  let resolve!: (response: Response) => void;
  let calls = 0;
  const { sent, queryClient } = mount(async () => ++calls === 1 ? new Promise((done) => { resolve = done; }) : ok({
    ...RESPONSE, myContribScore: 16, university: { ...RESPONSE.university, totalScore: 106 }, department: { id: 7, totalScore: 26 },
  }));
  await ready();
  click(); click();
  await waitFor(() => assert.equal(sent.length, 1), { timeout: 2500 });
  click();
  assert.equal(readGame().contribution, 16);
  await act(async () => {
    queryClient.setQueryData<MyInfo>(userKeys.me, { ...ME, nickname: '변경닉네임' });
  });
  assert.equal(readGame().contribution, 16, '프로필 변경도 미확정 3회 점수를 지우지 않는다');
  fireEvent.click(screen.getByRole('button', { name: '탭 전환' }));
  assertAbsent(screen.queryByRole('button', { name: '클릭' }));
  await act(async () => { resolve(ok(RESPONSE)); });
  assert.equal(readGame().contribution, 16);
  assert.equal(readGame().schoolScore, 106);
  assert.equal(readGame().departmentScore, 26);
  await waitFor(() => assert.equal(sent.length, 2), { timeout: 2500 });
  assert.equal(sent[1].clickCount, 1);
  await waitFor(() => assert.equal(queryClient.getQueryData<MyInfo>(userKeys.me)?.totalContribScore, 16));
  assert.equal(readGame().contribution, 16);
});

for (const [status, code] of [[429, 'CLICK_COUNT_EXCEEDED'], [403, 'ABUSE_VERIFICATION_REQUIRED']] as const) {
  test(`${status}: 오류·미확정 수·새로고침 손실을 알리고 추가 클릭을 막는다`, async () => {
    const { sent } = mount(async () => fail(status, code));
    await ready();
    click();
    await screen.findByRole('alert', {}, { timeout: 2500 });
    assert.match(screen.getByRole('alert').textContent ?? '', /미확정 1회 전송을 보류/);
    assert.match(screen.getByRole('alert').textContent ?? '', /새로고침하면 미확정 클릭은 사라지며/);
    assert.equal(readGame().contribution, 12);
    click();
    assert.equal(readGame().contribution, 12);
    assert.equal(sent.length, 1);
  });
}

test('429 명시적 재개는 거절된 20회 점수만 롤백하고 남은 5회는 적립한다', async () => {
  let calls = 0;
  const { sent } = mount(async () => ++calls === 1 ? fail(429, 'CLICK_COUNT_EXCEEDED') : ok({
    ...RESPONSE, myContribScore: 20, university: { ...RESPONSE.university, totalScore: 110 }, department: { id: 7, totalScore: 30 },
  }));
  await ready();
  for (let i = 0; i < 25; i++) click();
  assert.equal(readGame().contribution, 60);
  await screen.findByRole('button', { name: '거절 배치 제외' }, { timeout: 2500 });
  fireEvent.click(screen.getByRole('button', { name: '거절 배치 제외' }));
  assert.equal(readGame().contribution, 20);
  assert.match(screen.getByRole('alert').textContent ?? '', /거절한 20회는 적립되지 않았습니다/);
  await waitFor(() => assert.equal(sent.length, 2), { timeout: 2500 });
  assert.equal(sent[0].clickCount, 20);
  assert.equal(sent[1].clickCount, 5);
  assert.notEqual(sent[1].submissionId, sent[0].submissionId);
  await waitFor(() => assert.equal(readGame().schoolScore, 110));
  assert.equal(readGame().departmentScore, 30);
  assertAbsent(screen.queryByRole('alert'));
});

test('403은 자동으로 확인창을 열고 204 전에는 보류하며 성공 뒤 같은 ID로 클릭을 재개한다', async () => {
  const widget = fakeTurnstile();
  const verified: string[] = [];
  let finish!: (response: Response) => void;
  let calls = 0;
  const { sent } = mount(async () => ++calls === 1 ? fail(403, 'ABUSE_VERIFICATION_REQUIRED') : ok(RESPONSE), true, {
    loader: widget.loader,
    verify: async (token) => { verified.push(token); return new Promise((resolve) => { finish = resolve; }); },
  });
  await ready();
  click(); click();
  await screen.findByRole('dialog', { name: '자동 입력 방지 확인' }, { timeout: 2500 });
  await waitFor(() => assert.equal(widget.widgets.length, 1));
  assert.equal(widget.widgets[0].action, 'abuse_click');
  await act(async () => { widget.widgets[0].callback?.('fresh-token'); widget.widgets[0].callback?.('fresh-token'); });
  assert.deepEqual(verified, ['fresh-token'], '동일·동시 콜백은 확인 API를 중복 호출하지 않는다');
  assert.equal(sent.length, 1);
  assert.match(within(screen.getByRole('dialog')).getByRole('status').textContent ?? '', /서버에 확인 중/);
  await act(async () => { finish(new Response(null, { status: 204 })); });
  assertAbsent(screen.queryByRole('dialog'));
  await waitFor(() => assert.equal(sent.length, 2), { timeout: 2500 });
  assert.deepEqual(sent[1], sent[0]);
  await waitFor(() => assert.equal(readGame().schoolScore, 104));
  assertAbsent(screen.queryByRole('alert'));
  click();
  assert.equal(readGame().contribution, 16);
});

for (const rejection of ['denied', 'limited', 'network'] as const) {
  test(`봇 확인 ${rejection}: 클릭은 보류하고 사용한 토큰 대신 새 토큰으로 명시적으로 재시도한다`, async () => {
    const widget = fakeTurnstile();
    const verified: string[] = [];
    let calls = 0;
    const { sent } = mount(async () => ++calls === 1 ? fail(403, 'ABUSE_VERIFICATION_REQUIRED') : ok(RESPONSE), true, {
      loader: widget.loader,
      verify: async (token) => {
        verified.push(token);
        if (verified.length > 1) return new Response(null, { status: 204 });
        if (rejection === 'network') throw new TypeError('offline');
        return rejection === 'denied' ? fail(403, 'ABUSE_VERIFICATION_FAILED') : fail(429, 'COMMON_TOO_MANY_REQUESTS');
      },
    });
    await ready();
    click();
    await screen.findByRole('dialog', {}, { timeout: 2500 });
    await waitFor(() => assert.equal(widget.widgets.length, 1));
    await act(async () => { widget.widgets[0].callback?.('spent-token'); });
    await screen.findByRole('button', { name: '다시 시도' });
    assert.equal(sent.length, 1);
    assert.equal(readGame().contribution, 12);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    assert.equal(widget.resets.length, 1);
    await act(async () => { widget.widgets[0].callback?.('spent-token'); });
    assert.deepEqual(verified, ['spent-token'], '재설정 뒤에도 이전 토큰은 다시 보내지 않는다');
    await act(async () => { widget.widgets[0].callback?.('new-token'); });
    assert.deepEqual(verified, ['spent-token', 'new-token']);
    await waitFor(() => assert.equal(sent.length, 2), { timeout: 2500 });
    assert.deepEqual(sent[1], sent[0]);
  });
}

test('위젯 로더 실패도 서버 확인 없이 통과하지 않고 빈 토큰 확인이 204여야 재개한다', async () => {
  const verified: string[] = [];
  let finish!: (response: Response) => void;
  let calls = 0;
  const { sent } = mount(async () => ++calls === 1 ? fail(403, 'ABUSE_VERIFICATION_REQUIRED') : ok(RESPONSE), true, {
    loader: async () => { throw new Error('script blocked'); },
    verify: async (token) => { verified.push(token); return new Promise((resolve) => { finish = resolve; }); },
  });
  await ready();
  click();
  await waitFor(() => assert.deepEqual(verified, ['']), { timeout: 2500 });
  assert.equal(sent.length, 1);
  await act(async () => { finish(new Response(null, { status: 204 })); });
  await waitFor(() => assert.equal(sent.length, 2), { timeout: 2500 });
  assert.deepEqual(sent[1], sent[0]);
});

test('확인 중 닫기는 요청을 취소하고 늦은 성공을 무시하며 다음 클릭으로 다시 확인을 열 수 있다', async () => {
  const widget = fakeTurnstile();
  let finish!: (response: Response) => void;
  let signal: AbortSignal | null | undefined;
  const { sent } = mount(async () => fail(403, 'ABUSE_VERIFICATION_REQUIRED'), true, {
    loader: widget.loader,
    verify: async (_token, pendingSignal) => { signal = pendingSignal; return new Promise((resolve) => { finish = resolve; }); },
  });
  await ready();
  click();
  await screen.findByRole('dialog', {}, { timeout: 2500 });
  await waitFor(() => assert.equal(widget.widgets.length, 1));
  await act(async () => { widget.widgets[0].callback?.('old-token'); });
  fireEvent.click(screen.getByRole('button', { name: '▼ 터치하여 닫기' }));
  assert.equal(signal?.aborted, true);
  await act(async () => { finish(new Response(null, { status: 204 })); });
  assertAbsent(screen.queryByRole('dialog'));
  assert.equal(sent.length, 1);
  click();
  assert.ok(screen.getByRole('dialog'));
  assert.equal(readGame().contribution, 12);
  assert.equal(widget.removed.length, 1);
});

test('로그아웃 중 확인 성공이 늦게 도착해도 다음 세션의 큐를 재개하지 않는다', async () => {
  const widget = fakeTurnstile();
  let finish!: (response: Response) => void;
  let signal: AbortSignal | null | undefined;
  const { client, sent, queryClient } = mount(async () => fail(403, 'ABUSE_VERIFICATION_REQUIRED'), true, {
    loader: widget.loader,
    verify: async (_token, pendingSignal) => { signal = pendingSignal; return new Promise((resolve) => { finish = resolve; }); },
  });
  await ready();
  click();
  await screen.findByRole('dialog', {}, { timeout: 2500 });
  await waitFor(() => assert.equal(widget.widgets.length, 1));
  await act(async () => { widget.widgets[0].callback?.('old-token'); });
  await act(async () => {
    client.session.signOut();
    client.session.signIn('member-b');
    queryClient.setQueryData<MyInfo>(userKeys.me, { ...ME, id: 11 });
  });
  assert.equal(signal?.aborted, true);
  assertAbsent(screen.queryByRole('dialog'));
  await act(async () => { finish(new Response(null, { status: 204 })); });
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 1100)); });
  assert.equal(sent.length, 1);
  assertAbsent(screen.queryByRole('button', { name: '확인 열기' }));
});

test('미로그인 상태는 점수를 적립하지 않고 클릭 API도 보내지 않는다', async () => {
  const { sent } = mount(undefined, false);
  const initial = readGame().contribution;
  click();
  assert.equal(readGame().contribution, initial);
  assert.equal(sent.length, 0);
  assert.match(screen.getByRole('alert').textContent ?? '', /로그인해 주세요/);
});

test('세션 종료는 미전송 배치를 취소하고 다음 계정으로 재전송하지 않으며 취소 수를 알린다', async () => {
  const { client, sent, tokens, queryClient } = mount();
  await ready();
  click();
  await act(async () => { client.session.signOut(); });
  assert.match(screen.getByRole('alert').textContent ?? '', /세션 종료로 미확정 1회/);
  await act(async () => {
    client.session.signIn('member-b');
    queryClient.setQueryData<MyInfo>(userKeys.me, { ...ME, id: 11, totalContribScore: 100 });
  });
  await waitFor(() => assert.equal(readGame().contribution, 100));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 1100)); });
  assert.equal(sent.length, 0);
  click();
  await waitFor(() => assert.equal(sent.length, 1), { timeout: 2500 });
  assert.deepEqual(tokens, ['Bearer member-b']);
  assert.equal(sent[0].clickCount, 1);
});

test('같은 프레임에 같은 계정으로 재로그인해도 이전 큐를 폐기하고 새 큐를 만든다', async () => {
  const { client, queryClient, sent, tokens } = mount();
  await ready();
  click();
  await act(async () => {
    client.session.signOut();
    client.session.signIn('member-a-new-session');
  });
  // The new session must load its own profile before it can accept input.
  await waitFor(() => assert.equal(queryClient.getQueryData<MyInfo>(userKeys.me)?.id, ME.id));
  click();
  await waitFor(() => assert.equal(sent.length, 1), { timeout: 2500 });
  assert.equal(sent[0].clickCount, 1, '이전 세션의 미확정 클릭을 새 세션에 보내지 않는다');
  assert.deepEqual(tokens, ['Bearer member-a-new-session']);
});

test('API 모드에서는 목업의 치명타 배율을 요청 데이터나 점수에 섞지 않는다', async () => {
  const { sent } = mount();
  await ready();
  fireEvent.click(screen.getByRole('button', { name: '치명타' }));
  assert.equal(readGame().contribution, 12);
  await waitFor(() => assert.equal(sent.length, 1), { timeout: 2500 });
  assert.deepEqual(Object.keys(sent[0]), ['submissionId', 'clickCount']);
});

test('기본 목업 어댑터는 기존 tap을 그대로 쓰며 ApiProvider가 없어도 동작한다', () => {
  function MockProbe() {
    const clicks = useCampusClicks();
    const game = useMockGame();
    return React.createElement('button', { onClick: () => clicks.tap(false) }, String(game.state.contribution));
  }
  render(React.createElement(MockGameProvider, { children: React.createElement(MockProbe) }));
  const initial = Number(screen.getByRole('button').textContent);
  fireEvent.click(screen.getByRole('button'));
  assert.ok(Number(screen.getByRole('button').textContent) > initial);
});
