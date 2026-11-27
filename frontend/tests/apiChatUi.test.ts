import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import React from 'react';
import { JSDOM } from 'jsdom';
import type { ChatRoom } from '../src/api/chat';
import type { ChatCallbacks, ChatConnectionFactory } from '../src/api/chatConnection';
import type { MyInfo } from '../src/api/users';
import { flush, message, page, success } from './helpers/chat';
import { assertAbsent } from './helpers/domAssert';

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, IS_REACT_ACT_ENVIRONMENT: true })) {
  Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
}
const { render, screen, fireEvent, cleanup, waitFor, act, within } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { ApiError } = await import('../src/api/errors');
const { ApiChatBottomSheet } = await import('../src/components/ApiChatBottomSheet');
const clients: ReturnType<typeof createQueryClient>[] = [];
afterEach(async () => { cleanup(); await flush(); for (const client of clients.splice(0)) client.clear(); });
after(() => dom.window.close());
const ME: MyInfo = { id: 5, nickname: '우리학생', email: 'student@example.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: 1, name: '한양대학교', logoUrl: null }, department: { id: 1, name: '공학과' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 }, totalContribScore: 0, totalAttackScore: 0 };

async function mount(options: { signedOut?: boolean; history?: (url: URL) => Response | Promise<Response>; words?: () => Response;
  alliance?: () => Response; embedded?: boolean } = {}) {
  const calls: string[] = [], sends: string[] = [], sentTo: string[] = [], callbacks: ChatCallbacks[] = [], rooms: ChatRoom[] = [];
  let stopped = 0, login = 0;
  const api = createApiClient({ fetch: async (url) => {
    const parsed = new URL(String(url), 'http://localhost');
    calls.push(parsed.pathname + parsed.search);
    if (parsed.pathname === '/api/v1/users/me') return success(ME);
    if (parsed.pathname === '/api/v1/forbidden-words') return options.words?.() ?? success({ words: ['바보', 'badword'] });
    if (parsed.pathname === '/api/v1/alliances/me') return options.alliance?.() ?? success(null);
    return options.history?.(parsed) ?? success(page([]));
  } });
  if (!options.signedOut) api.session.signIn('token');
  const query = createQueryClient();
  query.setDefaultOptions({ queries: { retry: false, gcTime: Infinity } });
  clients.push(query);
  const factory: ChatConnectionFactory = (_api, room, events) => {
    callbacks.push(events);
    rooms.push(room);
    return { send: (value) => { sends.push(value); sentTo.push(`${room.kind}#${room.id}`); return true; }, stop: () => { stopped++; } };
  };
  const ui = render(React.createElement(ApiProvider, { client: api, queryClient: query,
    children: React.createElement(ApiChatBottomSheet, { connectionFactory: factory, onGoToLogin: () => login++, embedded: options.embedded }) }));
  if (!options.signedOut) {
    await waitFor(() => assert.equal(callbacks.length, 1));
    act(() => { callbacks[0].onStatus('connected'); callbacks[0].onReady(); });
    await waitFor(() => assert.equal((screen.getByLabelText('채팅 메시지') as HTMLTextAreaElement).disabled, false));
  }
  return { api, calls, sends, sentTo, callbacks, rooms, ui, counts: () => ({ stopped, login }) };
}

test('로그인 전에는 REST/소켓을 열지 않고 로그인으로 안내한다', async () => {
  const f = await mount({ signedOut: true });
  fireEvent.click(screen.getByRole('button', { name: '로그인' }));
  assert.equal(f.counts().login, 1);
  assert.deepEqual(f.calls, []);
  assert.deepEqual(f.callbacks, []);
});

test('빈 학교 대화·서버 메시지만 표시하며 HTML과 금칙어 원문을 낙관적으로 렌더하지 않는다', async () => {
  const f = await mount();
  screen.getByText('아직 메시지가 없어요. 첫 대화를 시작해 보세요.');
  const input = screen.getByLabelText('채팅 메시지');
  fireEvent.change(input, { target: { value: 'badword' } });
  fireEvent.click(screen.getByRole('button', { name: '전송' }));
  assert.deepEqual(f.sends, ['badword']);
  assert.equal(screen.queryAllByTestId('chat-message').length, 0);
  act(() => f.callbacks[0].onMessage(message(1, { content: '** <script>문자열</script>' })));
  const bubble = screen.getByTestId('chat-message');
  assert.match(bubble.textContent ?? '', /\*\* <script>문자열<\/script>/);
  assertAbsent(bubble.querySelector('script'));
  assert.equal(screen.queryAllByText('badword').length, 0);
  f.ui.unmount();
  assert.equal(f.counts().stopped, 1);
});

test('한글 IME와 Safari229·Shift+Enter는 보내지 않고 일반 Enter와 버튼은 한 번씩만 보낸다', async () => {
  const f = await mount();
  const input = screen.getByLabelText('채팅 메시지') as HTMLTextAreaElement;
  fireEvent.change(input, { target: { value: '안녕하세요' } });
  fireEvent.keyDown(input, { key: 'Enter', isComposing: true });
  fireEvent.keyDown(input, { key: 'Enter', keyCode: 229 });
  fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
  assert.deepEqual(f.sends, []);
  fireEvent.keyDown(input, { key: 'Enter', keyCode: 13 });
  assert.deepEqual(f.sends, ['안녕하세요']);
  assert.equal(input.value, '');
  fireEvent.change(input, { target: { value: '😀'.repeat(200) } });
  assert.equal(input.getAttribute('maxlength'), null);
  fireEvent.click(screen.getByRole('button', { name: '전송' }));
  assert.deepEqual(f.sends, ['안녕하세요', '😀'.repeat(200)]);
  fireEvent.change(input, { target: { value: '😀'.repeat(201) } });
  assert.equal(input.getAttribute('aria-invalid'), 'true');
  fireEvent.keyDown(input, { key: 'Enter', keyCode: 13 });
  assert.equal(f.sends.length, 2);
  assert.equal(input.value, '😀'.repeat(201));
});

test('오류 큐는 메시지를 알리고 연결 중단은 전송을 막는다', async () => {
  const f = await mount();
  act(() => f.callbacks[0].onError(new ApiError({ kind: 'http', status: 429, code: 'CHAT_TOO_FREQUENT', serverMessage: '천천히 보내세요' })));
  assert.match(screen.getByRole('alert').textContent ?? '', /천천히 보내세요/);
  act(() => f.callbacks[0].onStatus('reconnecting'));
  assert.equal((screen.getByLabelText('채팅 메시지') as HTMLTextAreaElement).disabled, true);
  assert.deepEqual(f.sends, []);
});

test('① PC 패널에서 연합 이력과 실시간 메시지를 볼 수 있다', async () => {
  const f = await mount({ embedded: true, alliance: () => success({ id: 9, name: '서울', totalScore: 0, status: 'ACTIVE', durationHours: 24, expiredAt: '2026-11-20T13:00:00+09:00' }) });
  fireEvent.click(screen.getByRole('button', { name: '연합 채팅' }));
  await waitFor(() => assert.equal(f.callbacks.length, 2));
  act(() => { f.callbacks[1].onStatus('connected'); f.callbacks[1].onReady(); });
  await waitFor(() => assert.ok(f.calls.includes('/api/v1/chat/alliances/9/messages?size=20')));
  act(() => f.callbacks[1].onMessage(message(2, { roomId: 'ALLIANCE#9', content: '연합 실시간 인사' })));
  await screen.findByText('연합 실시간 인사');
});

test('② PC 패널에 연합이 없으면 기존 안내가 보인다', async () => {
  await mount({ embedded: true });
  fireEvent.click(screen.getByRole('button', { name: '연합 채팅' }));
  await screen.findByText('우리 학교가 속한 연합이 없어요. 연합을 만들거나 가입이 승인되면 연합 채팅을 이용할 수 있어요.');
});

test('③ PC 패널에는 닫기 버튼이 없다', async () => {
  await mount({ embedded: true });
  assertAbsent(screen.queryByRole('button', { name: '채팅창 닫기' }));
});

test('④ PC 패널에는 우리 학교 채팅 문구가 없다', async () => {
  await mount({ embedded: true });
  assertAbsent(screen.queryByText('우리 학교 채팅'));
});

test('연합 탭은 처음 열 때 내 연합을 조회하고, 소속 연합이 없으면 안내만 보이며 소켓을 열지 않는다', async () => {
  let failure = true;
  const f = await mount({ alliance: () => failure ? new Response(JSON.stringify({ code: 'COMMON_INTERNAL_ERROR', message: '연합 조회 실패' }), { status: 500 }) : success(null) });
  assert.ok(!f.calls.includes('/api/v1/alliances/me'), '교내 채팅만 쓰면 연합을 조회하지 않는다');
  fireEvent.click(screen.getByRole('button', { name: '연합 채팅' }));
  await waitFor(() => assert.match(screen.getByRole('alert').textContent ?? '', /연합 조회 실패/));
  failure = false;
  fireEvent.click(screen.getByRole('button', { name: '연합 다시 확인' }));
  await screen.findByText('우리 학교가 속한 연합이 없어요. 연합을 만들거나 가입이 승인되면 연합 채팅을 이용할 수 있어요.');
  assertAbsent(screen.queryByRole('textbox'));
  assert.equal(f.callbacks.length, 1, '연합이 없으면 연합 소켓을 만들지 않는다');
});

test('연합 탭은 연합 방 소켓·이력을 따로 열고, 탭을 오가도 두 방의 메시지와 전송 대상이 섞이지 않는다', async () => {
  const f = await mount({ alliance: () => success({ id: 9, name: '서울', totalScore: 0, status: 'ACTIVE', durationHours: 24, expiredAt: '2026-11-20T13:00:00+09:00' }) });
  act(() => f.callbacks[0].onMessage(message(1, { content: '교내 인사' })));
  fireEvent.click(screen.getByRole('button', { name: '연합 채팅' }));
  await waitFor(() => assert.equal(f.callbacks.length, 2));
  assert.deepEqual(f.rooms, [{ kind: 'university', id: 1 }, { kind: 'alliance', id: 9 }]);
  act(() => { f.callbacks[1].onStatus('connected'); f.callbacks[1].onReady(); });
  await waitFor(() => assert.ok(f.calls.includes('/api/v1/chat/alliances/9/messages?size=20')));
  screen.getByText(/서울 연합 · .*KST까지 · 욕설·비방은 서버가 가려요\./);
  const allianceLog = screen.getByRole('log', { name: '연합 메시지' });
  act(() => f.callbacks[1].onMessage(message(2, { roomId: 'ALLIANCE#9', universityName: '연세대학교', content: '연합 인사' })));
  within(allianceLog).getByText('연합 인사');
  assert.equal(within(allianceLog).queryAllByText('교내 인사').length, 0);
  // getByRole skips the hidden school panel; both panels stay mounted so their sockets survive tab switches.
  const input = screen.getByRole('textbox', { name: '채팅 메시지' }) as HTMLTextAreaElement;
  await waitFor(() => assert.equal(input.disabled, false));
  fireEvent.change(input, { target: { value: '연합에게' } });
  fireEvent.click(screen.getByRole('button', { name: '전송' }));
  fireEvent.click(screen.getByRole('button', { name: '교내 채팅' }));
  fireEvent.change(screen.getByRole('textbox', { name: '채팅 메시지' }), { target: { value: '교내에게' } });
  fireEvent.click(screen.getByRole('button', { name: '전송' }));
  assert.deepEqual(f.sentTo, ['alliance#9', 'university#1']);
  assert.deepEqual(f.sends, ['연합에게', '교내에게']);
  fireEvent.click(screen.getByRole('button', { name: '연합 채팅' }));
  assert.equal(f.callbacks.length, 2, '탭을 다시 열어도 연합 소켓을 새로 만들지 않는다');
  f.ui.unmount();
  assert.equal(f.counts().stopped, 2, '시트를 닫으면 두 방의 소켓을 모두 닫는다');
});

test('숨긴 연합 채팅은 폴링하지 않고, 다시 열면 바뀐 소속으로 방을 교체한다', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  let allianceId = 9;
  const expiry = new Date(Date.now() + 3_600_000).toISOString();
  const f = await mount({ alliance: () => success({ id: allianceId, name: `연합 ${allianceId}`, totalScore: 0,
    status: 'ACTIVE', durationHours: 24, expiredAt: expiry }) });
  const reads = () => f.calls.filter((path) => path === '/api/v1/alliances/me').length;
  fireEvent.click(screen.getByRole('button', { name: '연합 채팅' }));
  await waitFor(() => assert.equal(f.rooms.at(-1)?.id, 9));
  fireEvent.click(screen.getByRole('button', { name: '교내 채팅' }));
  allianceId = 10;
  await act(async () => { t.mock.timers.tick(30_000); });
  assert.equal(reads(), 1);
  assert.equal(f.counts().stopped, 0);

  fireEvent.click(screen.getByRole('button', { name: '연합 채팅' }));
  await waitFor(() => assert.deepEqual(f.rooms, [{ kind: 'university', id: 1 }, { kind: 'alliance', id: 9 }, { kind: 'alliance', id: 10 }]));
  assert.equal(reads(), 2);
  assert.equal(f.counts().stopped, 1, '기존 연합 소켓만 닫는다');
});

test('공통 금칙어 목록의 로딩·오류·재시도·빈 목록과 복귀를 제공한다', async () => {
  let failure = true;
  const f = await mount({ words: () => failure ? new Response(JSON.stringify({ code: 'COMMON_INTERNAL_ERROR', message: '목록 실패' }), { status: 500 }) : success({ words: [] }) });
  fireEvent.click(screen.getByRole('button', { name: '금칙어' }));
  await screen.findByRole('heading', { name: '공통 금칙어 목록' });
  await waitFor(() => assert.match(screen.getByRole('alert').textContent ?? '', /목록 실패/));
  failure = false;
  fireEvent.click(screen.getByRole('button', { name: '금칙어 다시 불러오기' }));
  await screen.findByText('등록된 금칙어가 없어요.');
  fireEvent.click(screen.getByRole('button', { name: '채팅으로 돌아가기' }));
  assert.equal(document.activeElement?.textContent, '금칙어');
  assert.equal(f.callbacks.length, 1, '목록을 열어도 중복 소켓을 만들지 않는다');
  assert.ok(f.calls.includes('/api/v1/forbidden-words'));
});

test('과거 prepend와 동시 live append가 있어도 보고 있던 메시지의 위치를 지킨다', async (t) => {
  let finish!: (value: Response) => void;
  const f = await mount({ history: (url) => url.searchParams.has('cursor') ? new Promise((resolve) => { finish = resolve; })
    : success(page(Array.from({ length: 20 }, (_, i) => 40 - i), true)) });
  const viewport = screen.getByTestId('chat-history');
  Object.defineProperty(viewport, 'scrollHeight', { get: () => screen.getAllByTestId('chat-message').length * 100 });
  Object.defineProperty(viewport, 'clientHeight', { value: 200 });
  const rect = (top: number, height = 100) => ({ x: 0, y: top, width: 300, height, top, bottom: top + height, left: 0, right: 300, toJSON() {} });
  t.mock.method(dom.window.HTMLElement.prototype, 'getBoundingClientRect', function (this: HTMLElement) {
    if (this === viewport) return rect(0, 200);
    const nodes = [...viewport.querySelectorAll('[data-message-id]')];
    return rect(nodes.indexOf(this) * 100 - viewport.scrollTop);
  });
  viewport.scrollTop = 150;
  fireEvent.scroll(viewport);
  fireEvent.click(within(viewport).getByRole('button', { name: '이전 메시지 20개 불러오기' }));
  await act(async () => { await flush(); });
  act(() => f.callbacks[0].onMessage(message(41)));
  assert.equal(viewport.scrollTop, 150, '새 메시지가 와도 과거 읽기를 끌어내리지 않는다');
  await act(async () => { finish(success(page(Array.from({ length: 20 }, (_, i) => 20 - i)))); await flush(); });
  assert.equal(viewport.scrollTop, 2150, '기존 메시지22의 화면 내 위치를 보존한다');
  assert.equal(screen.getAllByTestId('chat-message').length, 41);
  assertAbsent(screen.queryByRole('button', { name: '이전 메시지 20개 불러오기' }));
  fireEvent.click(screen.getByRole('button', { name: '최신 메시지로' }));
  assert.equal(viewport.scrollTop, 4100);
});
