import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import type { MyInfo } from '../src/api/users';
import type { MemberSupportRequest, SupportDetail, SupportListResponse } from '../src/api/supports';
import type { ApiFaults } from '../src/api/faults';
import { assertAbsent } from './helpers/domAssert';

const assets = registerHooks({ load(url, context, nextLoad) {
  if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) return { format: 'module', source: 'export default "/asset";', shortCircuit: true };
  return nextLoad(url, context);
} });
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
const { render, screen, fireEvent, act, cleanup, waitFor } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createApiFaults } = await import('../src/api/faults');
const { createQueryClient } = await import('../src/api/queryClient');
const { createMemberSupport, getMySupports, getSupportDetail, safeSupportAttachment, supportKeys } = await import('../src/api/supports');
const { userKeys } = await import('../src/api/users');
const { ApiSessionWatcher } = await import('../src/components/onboarding/ApiLoginPhone');
const { SupportMemberScreenPhone } = await import('../src/components/SupportMemberScreenPhone');
const { MyInfoScreenPhone } = await import('../src/components/MyInfoScreenPhone');
const clients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => { cleanup(); for (const client of clients.splice(0)) client.clear(); });
after(() => { assets.deregister(); dom.window.close(); });
const ME: MyInfo = { id: 30, nickname: '문의학생', email: 'test@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: 1, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 }, totalContribScore: 100, totalAttackScore: 0 };
const DETAIL: SupportDetail = { supportId: 101, title: '테스트 문의', content: '첫째 줄\n둘째 줄', attachFileUrl: null,
  status: 'RECEIVED', answer: null, createdAt: '2030-01-01T23:59:00Z' };
const pageOf = (items: SupportDetail[] = [], page = 0, hasNext = false): SupportListResponse => ({ items, page, size: 20, totalCount: hasNext ? 21 : items.length, hasNext });
const ok = (data: unknown, status = 200) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status });
const fail = (status: number, code: string, data: unknown = null) => new Response(JSON.stringify({ code, message: '서버 문의 오류', data }), { status });
interface Call { path: string; auth: string | null; method?: string; signal?: AbortSignal | null; body?: unknown }
function mount(options: { signedIn?: boolean; strict?: boolean; profileScreen?: boolean; faults?: ApiFaults;
  list?: (page: number, call: Call) => Promise<Response>; detail?: (id: number) => Promise<Response>; create?: (body: MemberSupportRequest) => Promise<Response> } = {}) {
  const calls: Call[] = [], navigation: string[] = [];
  let profile = ME, rows: SupportDetail[] = [];
  let detail = DETAIL;
  const client = createApiClient({ faults: options.faults, fetch: (async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const call = { path: url.pathname + url.search, method: init?.method, auth: new Headers(init?.headers).get('Authorization'), signal: init?.signal,
      body: init?.body ? JSON.parse(String(init.body)) : undefined };
    calls.push(call);
    if (url.pathname.endsWith('/users/me')) return ok(profile);
    if (url.pathname.endsWith('/auth/reissue')) return options.signedIn === false ? fail(401, 'AUTH_INVALID_REFRESH_TOKEN') : ok({ accessToken: 'member-a', expiresInSeconds: 3600 });
    if (url.pathname.endsWith('/supports/me')) return options.list ? options.list(Number(url.searchParams.get('page')), call) : ok(pageOf(rows));
    if (url.pathname.endsWith('/supports') && init?.method === 'POST') {
      const body = call.body as MemberSupportRequest;
      if (options.create) return options.create(body);
      detail = { ...DETAIL, title: body.title, content: body.content }; rows = [detail, ...rows];
      return ok({ supportId: detail.supportId, status: detail.status }, 201);
    }
    if (/\/supports\/\d+$/.test(url.pathname)) return options.detail ? options.detail(Number(url.pathname.split('/').at(-1))) : ok(detail);
    throw new Error(`Unexpected ${url.pathname}`);
  }) as typeof fetch });
  const queryClient = createQueryClient(); clients.push(queryClient);
  if (options.signedIn !== false) { client.session.signIn('member-a'); queryClient.setQueryData(userKeys.me, profile); }
  const ui = options.profileScreen ? React.createElement(MyInfoScreenPhone, { onBack: () => navigation.push('back'), onGoToLogin: () => navigation.push('login'), onOpenSupports: () => navigation.push('support') })
    : React.createElement(SupportMemberScreenPhone, { onBack: () => navigation.push('back'), onGoToLogin: () => navigation.push('login') });
  render(React.createElement(options.strict ? React.StrictMode : React.Fragment, null,
    React.createElement(ApiProvider, { client, queryClient, children: React.createElement(React.Fragment, null,
      React.createElement(ApiSessionWatcher, { onEnded: () => navigation.push('ended') }), ui,
    ) })));
  return { client, queryClient, calls, navigation, posts: () => calls.filter((call) => call.method === 'POST' && call.path.endsWith('/supports')),
    lists: () => calls.filter((call) => call.path.includes('/supports/me')),
    setRows: (value: SupportDetail[]) => { rows = value; }, setDetail: (value: SupportDetail) => { detail = value; }, setProfile: (value: MyInfo) => { profile = value; } };
}
async function write() { fireEvent.click(await screen.findByRole('button', { name: '새 문의 작성' })); }
function fill(title = '테스트 문의', content = '첫째 줄\n둘째 줄') {
  fireEvent.change(screen.getByLabelText('제목 (필수 · 100자 이하)'), { target: { value: title } });
  fireEvent.change(screen.getByLabelText('본문 (필수 · 5,000자 이하)'), { target: { value: content } });
}
const submit = () => fireEvent.submit(screen.getByRole('form', { name: '회원 문의 작성' }));
const backToList = () => fireEvent.click(screen.getByRole('button', { name: '목록으로' }));

test('회원 등록·목록·상세 DTO와 인증 헤더·페이지·signal, 익명 fallback 없음', async () => {
  const calls: Call[] = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    const path = String(input); calls.push({ path, method: init?.method, auth: new Headers(init?.headers).get('Authorization'), signal: init?.signal, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    return ok(path.endsWith('/supports') ? { supportId: 101, status: 'RECEIVED' } : path.includes('/me?') ? pageOf([DETAIL], 2) : DETAIL, path.endsWith('/supports') ? 201 : 200);
  }) as typeof fetch });
  await assert.rejects(createMemberSupport(client, { recipientEmail: ME.email, title: '제목', content: '본문' }), /로그인/); assert.equal(calls.length, 0);
  client.session.signIn('member'); const abort = new AbortController();
  assert.deepEqual(await createMemberSupport(client, { recipientEmail: ` ${ME.email} `, title: ' 제목 ', content: ' 본문\n' }, abort.signal), { supportId: 101, status: 'RECEIVED' });
  assert.deepEqual(await getMySupports(client, 2, abort.signal), pageOf([DETAIL], 2)); assert.deepEqual(await getSupportDetail(client, 101, abort.signal), DETAIL);
  assert.deepEqual(calls.map((call) => call.path), ['/api/v1/supports', '/api/v1/supports/me?page=2&size=20', '/api/v1/supports/101']);
  for (const call of calls) { assert.equal(call.auth, 'Bearer member'); assert.equal(call.signal, abort.signal); }
  assert.deepEqual(calls[0].body, { recipientEmail: ME.email, title: ' 제목 ', content: ' 본문\n' });
});

test('StrictMode 빈 목록·비회원 안내·내 정보 진입은 실제 내 문의 화면으로 연결된다', async () => {
  const f = mount({ strict: true }); await screen.findByText('아직 등록한 문의가 없습니다.');
  assert.equal(screen.getByRole('button', { name: '이전 페이지' }).hasAttribute('disabled'), true);
  assert.equal(screen.getByRole('button', { name: '다음 페이지' }).hasAttribute('disabled'), true);
  fireEvent.click(screen.getByRole('button', { name: '내 정보로' })); assert.deepEqual(f.navigation, ['back']);
});

test('로그아웃 상태에서는 POST/목록/상세 없이 로그인 안내를 제공한다', async () => {
  const f = mount({ signedIn: false }); await screen.findByText('회원 문의를 이용하려면 로그인해 주세요.');
  fireEvent.click(screen.getByRole('button', { name: '로그인' }));
  assert.deepEqual(f.navigation, ['login']); assert.equal(f.calls.filter((call) => call.path.includes('/supports')).length, 0);
});

test('내 정보에 회원 문의 진입 버튼이 있고 진입 전에 목록을 미리 가져오지 않는다', async () => {
  const f = mount({ profileScreen: true }); fireEvent.click(await screen.findByRole('button', { name: '회원 문의 · 내 문의' }));
  assert.deepEqual(f.navigation, ['support']); assert.equal(f.lists().length, 0);
});

test('폼은 미리 오류를 띄우지 않고 필수·길이 검증 실패 시 입력 보존·오류 요약 포커스·POST 없음', async () => {
  const f = mount(); await write(); assertAbsent(screen.queryByRole('alert'));
  assert.equal((screen.getByLabelText('받을 이메일 (필수)') as HTMLInputElement).value, ME.email);
  submit(); await screen.findByText('제목을 입력해 주세요.'); assert.equal(document.activeElement?.getAttribute('role'), 'alert');
  for (const [title, content, expected] of [[' ', '본문', '제목을 입력'], ['제목', ' ', '본문을 입력'], ['가'.repeat(101), '본문', '100자 이하'], ['제목', '가'.repeat(5001), '5,000자 이하']]) {
    fill(title, content); submit(); await waitFor(() => assert.match(screen.getByRole('alert').textContent ?? '', new RegExp(expected)));
  }
  assert.equal(f.posts().length, 0); assertAbsent(document.querySelector('input[type="file"]'));
  assert.equal(screen.getByLabelText('제목 (필수 · 100자 이하)').getAttribute('maxlength'), '100');
});

test('등록 201 → 0페이지 목록 → 미답변 상세, 수신 이메일은 상세에서 추정하지 않는다', async () => {
  const f = mount(); await write(); fill(); submit();
  await screen.findByText('문의 #101가 접수되었습니다. 목록에서 내용을 확인해 주세요.');
  fireEvent.click(await screen.findByRole('button', { name: '테스트 문의' }));
  await screen.findByText('아직 답변이 등록되지 않았습니다.');
  assert.equal(screen.getByTestId('member-support-content').textContent, DETAIL.content);
  assert.match(screen.getByText(/2030/).textContent ?? '', /1\. 2\. 오전 8:59/);
  assert.equal(f.posts().length, 1); assert.deepEqual(f.posts()[0].body, { recipientEmail: ME.email, title: DETAIL.title, content: DETAIL.content });
  assertAbsent(screen.queryByText(ME.email)); assertAbsent(document.querySelector('[data-testid="support-bot-check"]'));
});

test('같은 프레임 이중 submit과 작성/목록 이동 중 재등록은 POST 1건만 보내고 성공 전 입력을 지우지 않는다', async () => {
  let finish!: (response: Response) => void;
  const f = mount({ create: () => new Promise((resolve) => { finish = resolve; }) }); await write(); fill();
  submit(); submit(); await waitFor(() => assert.equal(f.posts().length, 1));
  assert.equal((screen.getByLabelText('제목 (필수 · 100자 이하)') as HTMLInputElement).value, DETAIL.title);
  backToList(); await write(); submit(); assert.equal(f.posts().length, 1);
  await act(async () => finish(ok({ supportId: 101, status: 'RECEIVED' }, 201)));
  await screen.findByText('문의 #101가 접수되었습니다. 목록에서 내용을 확인해 주세요.');
});

test('서버 필드 오류는 관련 input과 연결하고 편집 시 지우며 400은 명시적 재등록 가능하다', async () => {
  const f = mount({ create: async () => fail(400, 'COMMON_INVALID_INPUT', [{ field: 'title', reason: '서버 제목 규칙' }]) }); await write(); fill(); submit();
  await screen.findByText(/서버 제목 규칙/);
  const input = screen.getByLabelText('제목 (필수 · 100자 이하)'); assert.equal(input.getAttribute('aria-invalid'), 'true');
  assert.equal(input.getAttribute('aria-describedby'), screen.getByRole('alert').id);
  fill('수정 제목'); assertAbsent(screen.queryByRole('alert')); assert.equal(input.getAttribute('aria-invalid'), 'false');
  submit(); await waitFor(() => assert.equal(f.posts().length, 2));
});

test('429는 회원별 한도를 표시하고 입력 보존·자동 재전송 없음', async () => {
  const f = mount({ create: async () => fail(429, 'COMMON_TOO_MANY_REQUESTS') }); await write(); fill(); submit();
  await screen.findByText('회원은 1시간에 5건까지 문의할 수 있어요. 잠시 후 다시 시도해 주세요.');
  assert.equal(f.posts().length, 1); assert.equal((screen.getByLabelText('본문 (필수 · 5,000자 이하)') as HTMLTextAreaElement).value, DETAIL.content);
});

for (const failure of ['network', '500', 'invalid-response'] as const) {
  test(`${failure}: 접수 불명확 시 POST 재시도 없이 목록 확인과 명시적 준비만 허용한다`, async () => {
    const f = mount({ create: async () => {
      if (failure === 'network') throw new TypeError('lost response');
      return failure === '500' ? fail(500, 'COMMON_INTERNAL_ERROR') : new Response('broken', { status: 201 });
    } }); await write(); fill(); submit();
    await screen.findByText(/접수 여부를 확인할 수 없습니다/);
    assert.equal(screen.getByRole('button', { name: '문의 등록' }).hasAttribute('disabled'), true);
    submit(); assert.equal(f.posts().length, 1);
    backToList(); await write(); assert.equal(screen.getByRole('button', { name: '문의 등록' }).hasAttribute('disabled'), true);
    fireEvent.click(screen.getByRole('button', { name: '목록에서 확인했고 다시 보내기 준비' }));
    assert.equal(f.posts().length, 1); assert.equal(screen.getByRole('button', { name: '문의 등록' }).hasAttribute('disabled'), false);
  });
}

test('목록 페이지는 서버 순서 유지·0 기반, 상세에서 목록 복귀 시 원래 페이지를 보존한다', async () => {
  const f = mount({ list: async (page) => ok(pageOf([{ ...DETAIL, supportId: page === 0 ? 102 : 101, title: page === 0 ? '최신 문의' : '이전 문의' }], page, page === 0)) });
  await screen.findByRole('button', { name: '최신 문의' }); fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
  fireEvent.click(await screen.findByRole('button', { name: '이전 문의' })); await screen.findByTestId('member-support-content');
  backToList(); await screen.findByRole('button', { name: '이전 문의' }); assert.match(screen.getByRole('status').textContent ?? '', /2페이지/);
  assert.equal(f.lists().some((call) => call.path.endsWith('page=1&size=20')), true);
});

test('목록 오류는 재조회로 복구하고 이전 데이터로 위장하지 않는다', async () => {
  let healthy = false;
  mount({ list: async () => healthy ? ok(pageOf([DETAIL])) : fail(400, 'COMMON_INVALID_INPUT') });
  await screen.findByRole('alert'); assertAbsent(screen.queryByTestId('member-support-row')); healthy = true;
  fireEvent.click(screen.getByRole('button', { name: '문의 목록 다시 조회' })); await screen.findByRole('button', { name: DETAIL.title });
});

for (const [status, code, message] of [[403, 'SUPPORT_NOT_OWNER', '내 문의만 확인할 수'], [404, 'SUPPORT_NOT_FOUND', '문의를 찾을 수']] as const) {
  test(`상세 ${code}는 본문/답변을 숨기고 서버 재조회를 제공한다`, async () => {
    let healthy = false;
    mount({ list: async () => ok(pageOf([DETAIL])), detail: async () => healthy ? ok(DETAIL) : fail(status, code) });
    fireEvent.click(await screen.findByRole('button', { name: DETAIL.title }));
    await waitFor(() => assert.match(screen.getByRole('alert').textContent ?? '', new RegExp(message)));
    assertAbsent(screen.queryByTestId('member-support-content')); assertAbsent(screen.queryByTestId('member-support-answer'));
    healthy = true; fireEvent.click(screen.getByRole('button', { name: '문의 상세 다시 조회' })); await screen.findByTestId('member-support-content');
  });
}

test('답변 새로고침은 실제 상세 조회값만 사용하고 문의/답변 HTML을 실행하지 않으며 안전한 첨부 링크만 제공한다', async () => {
  const f = mount({ list: async () => ok(pageOf([DETAIL])) }); fireEvent.click(await screen.findByRole('button', { name: DETAIL.title }));
  await screen.findByText('아직 답변이 등록되지 않았습니다.');
  const text = '<img src=x onerror="alert(1)">\n답변 둘째 줄';
  f.setDetail({ ...DETAIL, content: text, status: 'ANSWERED', answer: { title: '<script>답변</script>', content: text }, attachFileUrl: 'https://cdn.example/support/30/test.pdf' });
  fireEvent.click(screen.getByRole('button', { name: '문의 상세 새로고침' }));
  await screen.findByTestId('member-support-answer');
  assert.equal(screen.getByTestId('member-support-answer').textContent, text); assertAbsent(document.querySelector('img,script'));
  const link = screen.getByRole('link', { name: '첨부 파일 열기 (새 탭)' }); assert.equal(link.getAttribute('rel'), 'noopener noreferrer');
  f.setDetail({ ...DETAIL, attachFileUrl: 'javascript:alert(1)' }); fireEvent.click(screen.getByRole('button', { name: '문의 상세 새로고침' }));
  await screen.findByText('첨부 파일 주소를 확인할 수 없습니다.'); assertAbsent(screen.queryByRole('link'));
});

test('첨부 URL은 http(s)만 허용하며 자격증명·상대 경로·실행 스킴을 거부한다', () => {
  for (const url of [null, '', '/support/test.pdf', 'javascript:alert(1)', 'data:text/html,x', 'https://user:pass@cdn.example/a.pdf']) assert.equal(safeSupportAttachment(url), null);
  assert.equal(safeSupportAttachment('https://cdn.example/a.pdf'), 'https://cdn.example/a.pdf');
});

test('동일 프레임 계정 교체는 늦은 이전 POST를 취소하고 접수 알림·draft를 새 계정에 남기지 않는다', async () => {
  let finish!: (response: Response) => void;
  const f = mount({ create: () => new Promise((resolve) => { finish = resolve; }) }); await write(); fill(); submit(); await waitFor(() => assert.equal(f.posts().length, 1));
  await act(async () => {
    f.client.session.signOut(); f.client.session.signIn('member-b');
    const profile = { ...ME, id: 31, email: 'other@example.com' }; f.setProfile(profile); f.queryClient.setQueryData(userKeys.me, profile);
  });
  assert.equal(f.posts()[0].signal?.aborted, true);
  await act(async () => finish(ok({ supportId: 101, status: 'RECEIVED' }, 201)));
  assertAbsent(screen.queryByText(/문의 #101가 접수/)); await write();
  assert.equal((screen.getByLabelText('제목 (필수 · 100자 이하)') as HTMLInputElement).value, '');
  assert.equal((screen.getByLabelText('받을 이메일 (필수)') as HTMLInputElement).value, 'other@example.com');
});

test('세션 종료는 최초 목록 GET과 캐시된 상세도 초기화하여 새 계정에 이전 응답을 노출하지 않는다', async () => {
  let finish!: (response: Response) => void;
  const f = mount({ list: async (_page, call) => call.auth === 'Bearer member-a' ? new Promise((resolve) => { finish = resolve; }) : ok(pageOf([])) });
  await waitFor(() => assert.equal(f.lists().length, 1));
  f.queryClient.setQueryData(supportKeys.detail(ME.id, 101), DETAIL);
  const old = f.lists()[0];
  await act(async () => {
    f.client.session.signOut(); f.client.session.signIn('member-b'); const profile = { ...ME, id: 31 };
    f.setProfile(profile); f.queryClient.setQueryData(userKeys.me, profile);
  });
  assert.equal(old.signal?.aborted, true); await act(async () => finish(ok(pageOf([DETAIL]))));
  await screen.findByText('아직 등록한 문의가 없습니다.'); assertAbsent(screen.queryByRole('button', { name: DETAIL.title }));
  assert.equal(f.queryClient.getQueryData(supportKeys.detail(ME.id, 101)), undefined);
});

test('HTTP 전 지연 중 로그아웃은 회원 POST를 새 토큰이나 익명으로 보내지 않는다', async () => {
  let signal: AbortSignal | undefined, release!: () => void;
  const faults = createApiFaults(undefined, async (_ms, next) => {
    signal = next; await new Promise<void>((resolve, reject) => { release = resolve; next?.addEventListener('abort', () => reject(next.reason), { once: true }); });
  });
  const f = mount({ faults }); await screen.findByText('아직 등록한 문의가 없습니다.'); await write(); fill();
  faults.setMode('delay'); submit(); await waitFor(() => assert.ok(signal)); assert.equal(f.posts().length, 0); faults.setMode('normal');
  await act(async () => {
    f.client.session.signOut(); f.client.session.signIn('member-b'); const profile = { ...ME, id: 31 };
    f.setProfile(profile); f.queryClient.setQueryData(userKeys.me, profile);
  });
  assert.equal(signal?.aborted, true); await act(async () => release()); assert.equal(f.posts().length, 0);
});

test('201 뒤 목록 GET 실패는 접수 성공을 유지하고 재조회가 POST를 다시 보내지 않는다', async () => {
  let reads = 0, healthy = false;
  const f = mount({ list: async () => ++reads === 1 ? ok(pageOf([])) : healthy ? ok(pageOf([DETAIL])) : fail(400, 'COMMON_INVALID_INPUT') });
  await screen.findByText('아직 등록한 문의가 없습니다.'); await write(); fill(); submit();
  await screen.findByText('문의 #101가 접수되었습니다. 목록에서 내용을 확인해 주세요.'); await screen.findByRole('alert');
  healthy = true; fireEvent.click(screen.getByRole('button', { name: '문의 목록 다시 조회' })); await screen.findByRole('button', { name: DETAIL.title }); assert.equal(f.posts().length, 1);
});

test('캐시 없는 최초 목록 GET 중 201도 이전 snapshot을 취소하고 최신 목록을 가져온다', async () => {
  let hold = true, finishPost!: (value: Response) => void;
  const pending: Array<(value: Response) => void> = [];
  const f = mount({ list: async () => hold ? new Promise((resolve) => { pending.push(resolve); }) : ok(pageOf([DETAIL])),
    create: () => new Promise((resolve) => { finishPost = resolve; }) });
  await screen.findByText('문의 목록을 불러오는 중…'); await write(); fill(); submit();
  await waitFor(() => assert.equal(f.posts().length, 1)); backToList();
  await waitFor(() => assert.equal(f.lists().length, 2));
  const old = f.lists()[1]; hold = false;
  try {
    await act(async () => finishPost(ok({ supportId: 101, status: 'RECEIVED' }, 201)));
    await waitFor(() => assert.equal(f.lists().length, 3)); assert.equal(old.signal?.aborted, true);
    await screen.findByRole('button', { name: DETAIL.title });
  } finally {
    await act(async () => { for (const finish of pending) finish(ok(pageOf([]))); });
  }
  assert.ok(screen.getByRole('button', { name: DETAIL.title }));
});

test('상세 재조회가 403이면 이전에 캐시된 본문과 답변도 화면에서 숨긴다', async () => {
  let allowed = true;
  mount({ list: async () => ok(pageOf([DETAIL])), detail: async () => allowed
    ? ok({ ...DETAIL, status: 'ANSWERED', answer: { title: '비공개 답변', content: '비공개 본문' } }) : fail(403, 'SUPPORT_NOT_OWNER') });
  fireEvent.click(await screen.findByRole('button', { name: DETAIL.title })); await screen.findByTestId('member-support-answer');
  allowed = false; fireEvent.click(screen.getByRole('button', { name: '문의 상세 새로고침' }));
  await screen.findByRole('alert'); assertAbsent(screen.queryByTestId('member-support-content')); assertAbsent(screen.queryByTestId('member-support-answer'));
});

test('계약 센서: 서버 상태·최신순·페이지·소유자 거절과 신규 진입을 유지한다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/support/', import.meta.url);
  const status = readFileSync(new URL('entity/SupportStatus.java', root), 'utf8');
  for (const name of ['RECEIVED', 'ANSWERED']) assert.match(status, new RegExp(`\\b${name}\\b`));
  const repo = readFileSync(new URL('repository/CustomerSupportRepository.java', root), 'utf8'); assert.match(repo, /createdAt desc, s.id desc/);
  const service = readFileSync(new URL('service/SupportService.java', root), 'utf8'); assert.match(service, /SUPPORT_NOT_OWNER/); assert.match(service, /rateLimiter.checkMember\(userId\)/);
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8'); assert.match(app, /onOpenSupports=\{\(\) => setViewMode\('member_support'\)\}/);
});
