// SCRUM-164: the guest inquiry form sends POST /api/v1/supports without a token, answers 403
// ABUSE_VERIFICATION_REQUIRED with the widget and the same request plus `turnstileToken`, asks for a new token after
// ABUSE_VERIFICATION_FAILED, and shows the hourly limit on 429.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { readJava } from './helpers/javaSource';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
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

const { render, screen, fireEvent, act, cleanup, waitFor } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { TurnstileLoaderProvider } = await import('../src/hooks/useTurnstileLoader');
const { useApiGuestSupportSender } = await import('../src/hooks/useGuestSupportSender');
const { SupportGuestView } = await import('../src/components/SupportGuestScreenPhone');
const { SUPPORT_MESSAGES } = await import('../src/utils/mockSupport');
type TurnstileApi = import('../src/utils/turnstile').TurnstileApi;
afterEach(() => cleanup());
after(() => { assets.deregister(); dom.window.close(); });

function fakeTurnstile() {
  const rendered: Array<string | undefined> = [];
  const reset: Array<string | undefined> = [];
  let onToken: ((token: string) => void) | undefined;
  const api: TurnstileApi = {
    render(_container, options) { rendered.push(options.action); onToken = options.callback; return `widget-${rendered.length}`; },
    reset(id) { reset.push(id); },
    remove() {},
  };
  return { api, rendered, reset, solve: (token: string) => act(() => onToken?.(token)) };
}
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status: 201 });
const fail = (status: number, code: string, message: string) => new Response(JSON.stringify({ code, message, data: null }), { status });

interface Sent { auth: string | null; body: Record<string, unknown> }
function mount(answers: Array<() => Response>, { signedIn = false } = {}) {
  const sent: Sent[] = [];
  const turnstile = fakeTurnstile();
  const client = createApiClient({ fetch: (async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    if (url.pathname !== '/api/v1/supports' || init?.method !== 'POST') throw new Error(`Unexpected ${init?.method} ${url.pathname}`);
    sent.push({ auth: new Headers(init?.headers).get('Authorization'), body: JSON.parse(String(init?.body)) });
    const answer = answers.shift();
    if (!answer) throw new Error('No answer left');
    return answer();
  }) as typeof fetch });
  if (signedIn) client.session.signIn('member-a');
  const queryClient = createQueryClient();
  function Screen() { return React.createElement(SupportGuestView, { sender: useApiGuestSupportSender() }); }
  render(React.createElement(ApiProvider, { client, queryClient, children:
    React.createElement(TurnstileLoaderProvider, { loader: () => Promise.resolve(turnstile.api), children: React.createElement(Screen) }) }));
  return { sent, turnstile };
}
const field = (label: string) => screen.getByLabelText(label) as HTMLInputElement | HTMLTextAreaElement;
function fillAndSend(title = '결제 문의') {
  fireEvent.change(field('받을 이메일'), { target: { value: '  guest@example.com ' } });
  fireEvent.change(field('제목'), { target: { value: title } });
  fireEvent.change(field('본문'), { target: { value: '결제가 두 번 됐어요.' } });
  fireEvent.click(screen.getByRole('button', { name: '보내기' }));
}
const FORM = { recipientEmail: 'guest@example.com', title: '결제 문의', content: '결제가 두 번 됐어요.' };
const result = () => screen.queryByTestId('support-result')?.textContent ?? '';
const error = () => screen.queryByTestId('support-error')?.textContent ?? '';
const botCheck = () => screen.queryByTestId('support-bot-check');

test('익명 POST로 접수하고, 토큰 필드를 보내지 않으며, [시연] 보낸 수 칸은 없다', async () => {
  const f = mount([() => ok({ supportId: 1, status: 'PENDING' })], { signedIn: true });
  fillAndSend();
  await waitFor(() => assert.match(result(), /접수되었어요/));
  assert.deepEqual(f.sent, [{ auth: null, body: FORM }], '회원 토큰이 있어도 비회원 폼은 토큰 없이 보낸다');
  assert.equal(field('제목').value, '');
  assertAbsent(screen.queryByTestId('support-sent-count'));
  assert.equal(f.turnstile.rendered.length, 0);
});

test('403 확인 필요 → 위젯(support_guest) → 같은 내용에 토큰을 붙여 다시 보내 접수된다', async () => {
  const f = mount([
    () => fail(403, 'ABUSE_VERIFICATION_REQUIRED', '자동 입력 방지 확인이 필요합니다.'),
    () => ok({ supportId: 3, status: 'PENDING' }),
  ]);
  fillAndSend();
  await waitFor(() => assert.ok(botCheck()));
  assert.equal(field('제목').disabled, true, '확인 중에는 폼을 잠근다');
  await waitFor(() => assert.deepEqual(f.turnstile.rendered, ['support_guest']));
  f.turnstile.solve('token-1');
  await waitFor(() => assert.match(result(), /접수되었어요/));
  assert.deepEqual(f.sent.map((s) => s.body), [FORM, { ...FORM, turnstileToken: 'token-1' }]);
  assertAbsent(botCheck());
});

test('403 확인 실패면 확인 칸을 두고 서버 문구를 보이며, 다시 시도로 위젯을 초기화해 새 토큰으로 보낸다', async () => {
  const f = mount([
    () => fail(403, 'ABUSE_VERIFICATION_REQUIRED', '자동 입력 방지 확인이 필요합니다.'),
    () => fail(403, 'ABUSE_VERIFICATION_FAILED', '자동 입력 방지 확인에 실패했습니다. 다시 시도해주세요.'),
    () => ok({ supportId: 4, status: 'PENDING' }),
  ]);
  fillAndSend();
  await waitFor(() => assert.equal(f.turnstile.rendered.length, 1));
  f.turnstile.solve('used-token');
  await waitFor(() => assert.match(error(), /자동 입력 방지 확인에 실패했습니다/));
  assert.ok(botCheck(), '확인 칸은 남는다');
  assert.equal(f.sent.length, 2, '거절된 토큰을 자동으로 다시 보내지 않는다');
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
  assert.deepEqual(f.turnstile.reset, ['widget-1']);
  f.turnstile.solve('fresh-token');
  await waitFor(() => assert.match(result(), /접수되었어요/));
  assert.deepEqual(f.sent.map((s) => s.body.turnstileToken), [undefined, 'used-token', 'fresh-token']);
});

test('429는 1시간 5건 안내로 끝내고 확인 칸을 닫으며, 그 밖의 오류는 서버 문구를 보인다', async () => {
  mount([
    () => fail(429, 'COMMON_TOO_MANY_REQUESTS', '요청이 너무 많습니다. 잠시 후 다시 시도해주세요.'),
    () => fail(400, 'COMMON_INVALID_INPUT', '입력값이 올바르지 않습니다.'),
  ]);
  fillAndSend();
  await waitFor(() => assert.equal(error(), SUPPORT_MESSAGES.tooMany));
  assertAbsent(botCheck());
  assert.equal(field('제목').disabled, false, '다시 고칠 수 있다');
  fireEvent.click(screen.getByRole('button', { name: '보내기' }));
  await waitFor(() => assert.match(error(), /입력값이 올바르지 않습니다/));
});

test('계약 센서: 문의 경로·요청 필드·봇 확인/한도 오류 코드가 백엔드와 같다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/', import.meta.url);
  const controller = readJava(new URL('domain/support/controller/SupportController.java', root));
  assert.match(controller, /@RequestMapping\("\/api\/v1\/supports"\)/);
  assert.match(controller, /@PostMapping\s+@ResponseStatus\(HttpStatus\.CREATED\)/);
  const request = readJava(new URL('domain/support/dto/SupportCreateRequest.java', root));
  const fields = [...request.matchAll(/(?:^|,)\s*(?:@\w+(?:\([^)]*\))?\s+)*String\s+(\w+)/gm)].map((m) => m[1]);
  assert.deepEqual(fields, ['recipientEmail', 'title', 'content', 'attachFileUrl', 'turnstileToken']);
  const response = readJava(new URL('domain/support/dto/SupportCreateResponse.java', root));
  assert.match(response, /record SupportCreateResponse\(\s*Long supportId,\s*SupportStatus status\)/);
  const codes = readJava(new URL('global/exception/ErrorCode.java', root));
  assert.match(codes, /ABUSE_VERIFICATION_REQUIRED\(HttpStatus\.FORBIDDEN, "ABUSE_VERIFICATION_REQUIRED"/);
  assert.match(codes, /ABUSE_VERIFICATION_FAILED\(HttpStatus\.FORBIDDEN, "ABUSE_VERIFICATION_FAILED"/);
  assert.match(codes, /COMMON_TOO_MANY_REQUESTS\(HttpStatus\.TOO_MANY_REQUESTS/);
  const bot = readJava(new URL('domain/support/service/SupportBotCheck.java', root));
  assert.match(bot, /GUEST_ACTION = "support_guest"/);
  const screenSource = readFileSync(new URL('../src/components/SupportGuestScreenPhone.tsx', import.meta.url), 'utf8');
  assert.match(screenSource, /useGuestSupportSender\(\)/);
  assert.doesNotMatch(screenSource, /judgeGuestSupport|useMockRequest/);
});
