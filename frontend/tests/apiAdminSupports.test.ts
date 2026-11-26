// SCRUM-173: the admin inquiry list (status filter, pages), the detail and the answer form. Completion criterion:
// after an answer the inquiry shows as answered and the pending count drops.
import assert from 'node:assert/strict';
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

const { render, screen, fireEvent, cleanup, waitFor, within } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { validateSupportAnswer } = await import('../src/api/admin');
const { AdminScreenPhone } = await import('../src/components/AdminScreenPhone');
const { SCHOOL_ID } = await import('../src/data/schoolIds');
const { assertAbsent } = await import('./helpers/domAssert');
type MyInfo = import('../src/api/users').MyInfo;
type AdminSupportDetail = import('../src/api/admin').AdminSupportDetail;

const queryClients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => {
  cleanup();
  for (const client of queryClients.splice(0)) {
    // MutationCache.clear() keeps each answer's 5-minute gc timer alive, so node would not exit; destroy() cancels it.
    for (const mutation of client.getMutationCache().getAll()) mutation.destroy();
    client.clear();
  }
});
after(() => { assets.deregister(); dom.window.close(); });

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const ok = (data: unknown) => json(200, { code: 'SUCCESS', message: '요청이 성공했습니다.', data });
const fail = (status: number, code: string, message = '실패', data: unknown = null) => json(status, { code, message, data });

const ADMIN: MyInfo = {
  id: 1, nickname: '운영자', email: 'admin@hanyang.ac.kr', role: 'ADMIN', status: 'ACTIVE', profileImageUrl: null,
  university: { id: SCHOOL_ID.hanyang, name: '한양대학교', logoUrl: null }, department: { id: 11, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 }, totalContribScore: 0, totalAttackScore: 0,
};

function support(id: number, overrides: Partial<AdminSupportDetail> = {}): AdminSupportDetail {
  return {
    supportId: id, title: `문의 ${id}`, content: `본문 ${id}\n둘째 줄`, attachFileUrl: null, recipientEmail: `user${id}@example.com`,
    isMember: id % 2 === 0, status: 'RECEIVED', answer: null, createdAt: '2026-11-19T01:00:00Z', ...overrides,
  };
}

interface Call { route: string; search: URLSearchParams; body: unknown }
type Handler = (call: Call, rows: AdminSupportDetail[]) => Response | undefined;

/** A small in-memory AdminSupportController: newest (highest id) first, conditional answer like the server. */
function mount({ rows: initial, handle }: { rows: AdminSupportDetail[]; handle?: Handler }) {
  const rows = initial.map((row) => ({ ...row }));
  const calls: Call[] = [];
  const navigation: string[] = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const call = { route: `${init?.method ?? 'GET'} ${url.pathname}`, search: url.searchParams, body: init?.body ? JSON.parse(String(init.body)) : undefined };
    calls.push(call);
    const custom = handle?.(call, rows);
    if (custom) return custom;
    if (call.route === 'GET /api/v1/users/me') return ok(ADMIN);
    if (call.route === 'GET /api/v1/admin/supports') {
      const status = url.searchParams.get('status');
      const page = Number(url.searchParams.get('page'));
      const size = Number(url.searchParams.get('size'));
      const matching = rows.filter((row) => !status || row.status === status).sort((a, b) => b.supportId - a.supportId);
      const items = matching.slice(page * size, page * size + size)
        .map(({ supportId, title, recipientEmail, isMember, status: rowStatus, createdAt }) => ({ supportId, title, recipientEmail, isMember, status: rowStatus, createdAt }));
      return ok({ items, page, size, totalCount: matching.length, hasNext: (page + 1) * size < matching.length });
    }
    const detail = /^GET \/api\/v1\/admin\/supports\/(\d+)$/.exec(call.route);
    if (detail) {
      const row = rows.find(({ supportId }) => supportId === Number(detail[1]));
      return row ? ok(row) : fail(404, 'SUPPORT_NOT_FOUND', '문의를 찾을 수 없습니다.');
    }
    const answer = /^POST \/api\/v1\/admin\/supports\/(\d+)\/answer$/.exec(call.route);
    if (answer) {
      const row = rows.find(({ supportId }) => supportId === Number(answer[1]));
      if (!row) return fail(404, 'SUPPORT_NOT_FOUND', '문의를 찾을 수 없습니다.');
      if (row.status === 'ANSWERED') return fail(409, 'SUPPORT_ALREADY_ANSWERED', '이미 답변한 문의입니다.');
      const { answerTitle, answerContent } = call.body as { answerTitle: string; answerContent: string };
      Object.assign(row, { status: 'ANSWERED', answer: { title: answerTitle, content: answerContent } });
      return ok({ supportId: row.supportId, status: 'ANSWERED' });
    }
    throw new Error(`Unexpected ${call.route}`);
  }) as typeof fetch });
  client.session.signIn('access-1');
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  render(React.createElement(ApiProvider, { client, queryClient, children:
    React.createElement(AdminScreenPhone, { onBack: () => navigation.push('back'), onGoToLogin: () => navigation.push('login'), schoolName: { value: 'default', onChange: () => undefined } }) }));
  return { calls, navigation, rows };
}
/** List page requests; the home's pending count asks for size 1. */
const lists = (calls: Call[]) => calls.filter(({ route, search }) => route === 'GET /api/v1/admin/supports' && search.get('size') === '20')
  .map(({ search }) => search.toString());
const posts = (calls: Call[]) => calls.filter(({ route }) => route.startsWith('POST /api/v1/admin/'));
const rowTitles = () => screen.queryAllByTestId('admin-support-row').map((row) => within(row).getAllByRole('button')[0].textContent);

async function openSupports() {
  await screen.findByTestId('admin-pending-count');
  fireEvent.click(screen.getByRole('button', { name: /^문의 처리/ }));
  await screen.findByRole('heading', { name: '문의 처리' });
}
async function openDetail(title: string) {
  fireEvent.click(await screen.findByRole('button', { name: title }));
  await screen.findByTestId('admin-support-content');
}
const fill = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label, { exact: false }), { target: { value } });
const submit = () => fireEvent.click(screen.getByRole('button', { name: '답변 등록' }));

test('문의 처리 메뉴가 열리고, 목록은 답변 대기부터 20개씩 최신순으로 보인다', async () => {
  const f = mount({ rows: [support(1), support(2), support(3, { status: 'ANSWERED', answer: { title: '답', content: '완료' } })] });
  await screen.findByTestId('admin-pending-count');
  const menu = screen.getByRole('navigation', { name: '관리자 메뉴' });
  const enabled = [...menu.querySelectorAll('button')].filter((button) => !button.disabled).map((button) => button.firstChild?.textContent);
  assert.deepEqual(enabled, ['문의 처리', '회원 정지·해제', '어뷰징 순위', '테스트 도구'], '메뉴가 모두 켜졌다');
  await openSupports();
  await waitFor(() => assert.deepEqual(rowTitles(), ['문의 2', '문의 1']));
  assert.deepEqual(lists(f.calls), ['status=RECEIVED&page=0&size=20']);
  screen.getByText('1페이지 · 전체 2건');
  assert.equal(screen.getByRole('button', { name: '답변 대기' }).getAttribute('aria-pressed'), 'true');
});

test('상태 필터: 전체는 status 없이, 답변 완료는 ANSWERED로 요청하고 페이지를 처음으로 돌린다', async () => {
  const rows = Array.from({ length: 25 }, (_, index) => support(index + 1, index < 3 ? { status: 'ANSWERED', answer: { title: 't', content: 'c' } } : {}));
  const f = mount({ rows });
  await openSupports();
  await waitFor(() => assert.equal(rowTitles().length, 20));
  fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
  await waitFor(() => assert.deepEqual(rowTitles(), ['문의 5', '문의 4']));
  assert.equal(screen.getByRole('button', { name: '다음 페이지' }).hasAttribute('disabled'), true);
  fireEvent.click(screen.getByRole('button', { name: '전체' }));
  await waitFor(() => assert.equal(rowTitles()[0], '문의 25'));
  fireEvent.click(screen.getByRole('button', { name: '답변 완료' }));
  await waitFor(() => assert.deepEqual(rowTitles(), ['문의 3', '문의 2', '문의 1']));
  assert.deepEqual(lists(f.calls), [
    'status=RECEIVED&page=0&size=20', 'status=RECEIVED&page=1&size=20', 'page=0&size=20', 'status=ANSWERED&page=0&size=20',
  ]);
});

test('상세는 본문·받는 이메일·회원 여부를 보이고, 안전하지 않은 첨부 주소는 링크로 만들지 않는다', async () => {
  const f = mount({ rows: [support(7, { attachFileUrl: 'javascript:alert(1)' }), support(8, { attachFileUrl: 'https://cdn.example.com/a.png' })] });
  await openSupports();
  await openDetail('문의 7');
  assert.ok(f.calls.some(({ route }) => route === 'GET /api/v1/admin/supports/7'));
  assert.equal(screen.getByTestId('admin-support-content').textContent, '본문 7\n둘째 줄');
  assert.equal(screen.getByTestId('admin-support-email').textContent, 'user7@example.com');
  screen.getByText(/비회원 문의/);
  assertAbsent(screen.queryByRole('link', { name: /첨부 파일/ }));
  screen.getByText('첨부 파일 주소를 확인할 수 없어요.');
  fireEvent.click(screen.getByRole('button', { name: '문의 목록으로 돌아가기' }));
  await openDetail('문의 8');
  assert.equal(screen.getByRole('link', { name: /첨부 파일/ }).getAttribute('href'), 'https://cdn.example.com/a.png');
});

test('답변 등록: 상태가 답변 완료로 바뀌고 목록과 답변 대기 건수가 다시 조회된다', async () => {
  const f = mount({ rows: [support(1), support(2)] });
  await waitFor(() => assert.equal(screen.getByTestId('admin-pending-count').textContent, '2'));
  await openSupports();
  await openDetail('문의 2');
  fill('답변 제목', '문의 답변드립니다');
  fill('답변 내용', '확인 결과\n적용했습니다.');
  submit();
  await screen.findByTestId('admin-answer-done');
  assert.deepEqual(posts(f.calls).map(({ route, body }) => [route, body]), [
    ['POST /api/v1/admin/supports/2/answer', { answerTitle: '문의 답변드립니다', answerContent: '확인 결과\n적용했습니다.' }],
  ]);
  assert.equal(screen.getByTestId('admin-support-status').textContent, '답변 완료');
  assert.equal(screen.getByTestId('admin-support-answer').textContent, '확인 결과\n적용했습니다.');
  assertAbsent(screen.queryByRole('form', { name: '문의 답변 작성' }));
  fireEvent.click(screen.getByRole('button', { name: '문의 목록으로 돌아가기' }));
  await waitFor(() => assert.deepEqual(rowTitles(), ['문의 1']));
  fireEvent.click(screen.getByRole('button', { name: '관리자 홈으로 돌아가기' }));
  await waitFor(() => assert.equal(screen.getByTestId('admin-pending-count').textContent, '1'));
});

test('빈 제목·공백 내용은 보내지 않고 안내하며, 서버 필드 오류는 입력과 함께 보인다', async () => {
  const f = mount({ rows: [support(1)], handle: ({ route }) => route.endsWith('/answer')
    ? fail(400, 'COMMON_INVALID_INPUT', '입력값이 올바르지 않습니다.', [{ field: 'answerContent', value: '', reason: '내용이 너무 깁니다' }]) : undefined });
  await openSupports();
  await openDetail('문의 1');
  submit();
  assert.equal((await screen.findByRole('alert')).textContent, '답변 제목을 입력해 주세요.');
  fill('답변 제목', '제목');
  fill('답변 내용', '   ');
  submit();
  assert.equal((await screen.findByRole('alert')).textContent, '답변 내용을 입력해 주세요.');
  assert.equal(posts(f.calls).length, 0);
  fill('답변 내용', '내용');
  submit();
  await waitFor(() => assert.match(screen.getByRole('alert').textContent ?? '', /내용이 너무 깁니다/));
  assert.equal(screen.getByLabelText('답변 내용', { exact: false }).getAttribute('aria-invalid'), 'true');
  assert.equal((screen.getByLabelText('답변 제목', { exact: false }) as HTMLInputElement).value, '제목', '실패해도 입력을 지우지 않는다');
  assert.equal(posts(f.calls).length, 1);
});

test('409 SUPPORT_ALREADY_ANSWERED면 다시 보내지 않고, 먼저 등록된 답변을 불러와 보인다', async () => {
  const f = mount({ rows: [support(4)], handle: ({ route }, rows) => {
    if (!route.endsWith('/answer')) return undefined;
    Object.assign(rows[0], { status: 'ANSWERED', answer: { title: '다른 관리자', content: '먼저 답변함' } });
    return fail(409, 'SUPPORT_ALREADY_ANSWERED', '이미 답변한 문의입니다.');
  } });
  await openSupports();
  await openDetail('문의 4');
  fill('답변 제목', '늦은 답변');
  fill('답변 내용', '내용');
  submit();
  await screen.findByText('다른 관리자가 먼저 답변했어요. 등록된 답변을 확인해 주세요.');
  await waitFor(() => assert.equal(screen.getByTestId('admin-support-answer').textContent, '먼저 답변함'));
  assertAbsent(screen.queryByTestId('admin-answer-done'));
  assert.equal(posts(f.calls).length, 1);
});

test('응답을 못 받은 답변은 자동으로 다시 보내지 않고, 상세를 다시 읽어 실제 상태를 보인다', async () => {
  let attempt = 0;
  const f = mount({ rows: [support(5)], handle: ({ route }, rows) => {
    if (!route.endsWith('/answer')) return undefined;
    attempt += 1;
    Object.assign(rows[0], { status: 'ANSWERED', answer: { title: '제목', content: '서버에는 저장됨' } });
    throw new TypeError('Failed to fetch');
  } });
  await openSupports();
  await openDetail('문의 5');
  fill('답변 제목', '제목');
  fill('답변 내용', '서버에는 저장됨');
  submit();
  await screen.findByText(/답변 결과를 받지 못했지만 이 문의는 이미 답변 완료 상태예요/);
  assert.equal(screen.getByTestId('admin-support-answer').textContent, '서버에는 저장됨');
  assert.equal(attempt, 1);
  assert.equal(posts(f.calls).length, 1);
});

test('응답을 못 받았고 서버에도 답변이 없으면 입력을 남긴 채 다시 등록할 수 있다', async () => {
  let failing = true;
  const f = mount({ rows: [support(6)], handle: ({ route }) => {
    if (!route.endsWith('/answer') || !failing) return undefined;
    failing = false;
    throw new TypeError('Failed to fetch');
  } });
  await openSupports();
  await openDetail('문의 6');
  fill('답변 제목', '제목');
  fill('답변 내용', '내용');
  submit();
  await screen.findByRole('alert');
  assert.equal((screen.getByLabelText('답변 내용', { exact: false }) as HTMLTextAreaElement).value, '내용');
  submit();
  await screen.findByTestId('admin-answer-done');
  assert.equal(posts(f.calls).length, 2);
});

test('없는 문의는 찾을 수 없다고 안내하고 목록으로 돌아갈 수 있다', async () => {
  mount({ rows: [support(9)], handle: ({ route }) => route === 'GET /api/v1/admin/supports/9' ? fail(404, 'SUPPORT_NOT_FOUND', '문의를 찾을 수 없습니다.') : undefined });
  await openSupports();
  fireEvent.click(await screen.findByRole('button', { name: '문의 9' }));
  await screen.findByText('문의를 찾을 수 없어요.');
  fireEvent.click(screen.getByRole('button', { name: '문의 목록으로' }));
  await screen.findByRole('heading', { name: '문의 처리' });
});

test('문의 목록이 403이면 권한 안내를 보이고 내 정보를 다시 읽는다', async () => {
  const f = mount({ rows: [support(1)], handle: ({ route, search }) => route === 'GET /api/v1/admin/supports' && search.get('size') === '20'
    ? fail(403, 'COMMON_FORBIDDEN', '권한이 없습니다.') : undefined });
  await openSupports();
  await screen.findByTestId('admin-forbidden');
  await waitFor(() => assert.equal(f.calls.filter(({ route }) => route === 'GET /api/v1/users/me').length, 2));
});

test('validateSupportAnswer는 서버 @NotBlank·@Size와 같은 경계를 쓴다', () => {
  assert.equal(validateSupportAnswer({ answerTitle: ' ', answerContent: '내용' }), '답변 제목을 입력해 주세요.');
  assert.equal(validateSupportAnswer({ answerTitle: '가'.repeat(101), answerContent: '내용' }), '답변 제목은 100자 이하로 입력해 주세요.');
  assert.equal(validateSupportAnswer({ answerTitle: '제목', answerContent: '\n' }), '답변 내용을 입력해 주세요.');
  assert.equal(validateSupportAnswer({ answerTitle: '제목', answerContent: '가'.repeat(5001) }), '답변 내용은 5,000자 이하로 입력해 주세요.');
  assert.equal(validateSupportAnswer({ answerTitle: '가'.repeat(100), answerContent: '가'.repeat(5000) }), null);
});

test('계약 센서: 관리자 문의 상세·답변 DTO와 오류 코드가 바뀌면 화면을 다시 확인한다', () => {
  const main = new URL('../../src/main/java/com/back/nbe1214finalsour/', import.meta.url);
  const read = (path: string) => readJava(new URL(path, main));
  const fix = 'admin.ts 타입과 AdminSupportsPanel 화면을 백엔드 계약에 맞춰 고친 뒤 이 센서를 갱신하세요.';
  const controller = read('domain/support/controller/AdminSupportController.java');
  assert.match(controller, /@GetMapping\("\/\{supportId\}"\)\s*public ApiResponse<AdminSupportDetailResponse>/, fix);
  assert.match(controller, /@PostMapping\("\/\{supportId\}\/answer"\)/, fix);
  const detail = read('domain/support/dto/AdminSupportDetailResponse.java');
  assert.match(detail, /Long supportId,\s*String title,\s*String content,\s*@Nullable String attachFileUrl,\s*String recipientEmail,\s*boolean isMember,\s*SupportStatus status,\s*SupportDetailResponse\.@Nullable Answer answer,\s*OffsetDateTime createdAt/, fix);
  assert.match(read('domain/support/dto/SupportDetailResponse.java'), /record Answer\(\s*String title,\s*String content\)/, fix);
  assert.match(read('domain/support/dto/AdminSupportListItem.java'), /Long supportId,\s*String title,\s*String recipientEmail,\s*boolean isMember,\s*SupportStatus status,\s*OffsetDateTime createdAt/, fix);
  const request = read('domain/support/dto/SupportAnswerRequest.java');
  assert.match(request, /@NotBlank @Size\(max = 100\) String answerTitle/, fix);
  assert.match(request, /@NotBlank @Size\(max = 5000\) String answerContent/, fix);
  assert.match(read('domain/support/dto/SupportAnswerResponse.java'), /record SupportAnswerResponse\(\s*Long supportId,\s*SupportStatus status\)/, fix);
  const errors = read('global/exception/ErrorCode.java');
  assert.match(errors, /SUPPORT_ALREADY_ANSWERED\(HttpStatus\.CONFLICT, "SUPPORT_ALREADY_ANSWERED"/, fix);
  assert.match(errors, /SUPPORT_NOT_FOUND\(HttpStatus\.NOT_FOUND, "SUPPORT_NOT_FOUND"/, fix);
});
