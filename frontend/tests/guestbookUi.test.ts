import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import React from 'react';
import { JSDOM } from 'jsdom';
import type { MyInfo } from '../src/api/users';
import type { GuestbookItem, GuestbookPage } from '../src/api/guestbooks';
import { assertAbsent, assertSameNode } from './helpers/domAssert';

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
const { render, screen, fireEvent, act, cleanup, waitFor } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { userKeys } = await import('../src/api/users');
const { guestbookKeys } = await import('../src/api/guestbooks');
const { ApiGuestbookBottomSheet } = await import('../src/components/ApiGuestbookBottomSheet');
const clients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => { cleanup(); for (const client of clients.splice(0)) { for (const m of client.getMutationCache().getAll()) m.destroy(); client.clear(); } });
after(() => dom.window.close());
const ME: MyInfo = { id: 30, nickname: '방문학생', email: 'test@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: 1, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 }, totalContribScore: 100, totalAttackScore: 0 };
const item = (id: number, content = `응원 ${id}`): GuestbookItem => ({ id, content, likeCount: 4, likedByMe: true,
  author: { nickname: '학교손님', universityName: '한국대학교' }, createdAt: '2030-01-01T23:59:00Z' });
const page = (items: GuestbookItem[] = [], nextCursor: string | null = null): GuestbookPage => ({ items, nextCursor, hasNext: nextCursor !== null });
const ok = (data: unknown, status = 200) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status });
const fail = (status: number, code: string) => new Response(JSON.stringify({ code, message: '서버 방명록 오류', data: null }), { status });
interface Call { school: number; cursor: string | null; body?: { content: string } }
function mount(options: { signedIn?: boolean; own?: boolean; initial?: GuestbookItem[]; strict?: boolean;
  list?: (call: Call) => Promise<Response>; create?: (call: Call) => Promise<Response>; profile?: () => Promise<Response>;
  like?: (call: { id: number; method: string }) => Promise<Response> } = {}) {
  const calls: Call[] = [], navigation: string[] = [], likes: Array<{ id: number; method: string }> = [];
  const rows = [...options.initial ?? []];
  const client = createApiClient({ fetch: (async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    if (url.pathname.endsWith('/users/me')) return options.profile ? options.profile() : ok(ME);
    const likedId = /guestbooks\/(\d+)\/likes$/.exec(url.pathname)?.[1];
    if (likedId) {
      const call = { id: Number(likedId), method: init?.method ?? 'GET' }; likes.push(call);
      if (options.like) return options.like(call);
      const index = rows.findIndex((row) => row.id === call.id);
      const row = rows[index]; const liked = call.method === 'POST';
      rows[index] = { ...row, likedByMe: liked, likeCount: row.likeCount + (liked ? 1 : -1) };
      return ok({ guestbookId: call.id, liked, likeCount: rows[index].likeCount });
    }
    const school = Number(/universities\/(\d+)/.exec(url.pathname)?.[1]);
    if (!school) throw new Error(`Unexpected ${url.pathname}`);
    const call = { school, cursor: url.searchParams.get('cursor'), body: init?.body ? JSON.parse(String(init.body)) : undefined };
    calls.push(call);
    if (call.body) {
      if (options.create) return options.create(call);
      const created = item(100, call.body.content.replaceAll('badword', '***')); rows.unshift(created); return ok(created, 201);
    }
    return options.list ? options.list(call) : ok(page(rows));
  }) as typeof fetch });
  const queryClient = createQueryClient(); clients.push(queryClient);
  // Make failures deterministic without waiting through the common query retry delay.
  queryClient.setDefaultOptions({ queries: { retry: false, staleTime: 10_000 }, mutations: { retry: false } });
  if (options.signedIn !== false) { client.session.signIn('member'); if (!options.profile) queryClient.setQueryData(userKeys.me, ME); }
  const props = { universityId: options.own ? undefined : 2, schoolName: '방문대학교', onClose: () => navigation.push('close'),
    onGoToMap: () => navigation.push('map'), onGoToLogin: () => navigation.push('login') };
  const ui = (id = props.universityId) => React.createElement(options.strict ? React.StrictMode : React.Fragment, null,
    React.createElement(ApiProvider, { client, queryClient, children: React.createElement(ApiGuestbookBottomSheet, { ...props, universityId: id }) }));
  const view = render(ui());
  return { client, queryClient, calls, rows, navigation, likes, posts: () => calls.filter((call) => call.body),
    rerender: (id: number) => view.rerender(ui(id)) };
}
const fill = (value: string) => fireEvent.change(screen.getByLabelText('방명록 내용 (필수 · 500자 이하)'), { target: { value } });
const submit = () => fireEvent.submit(screen.getByRole('form', { name: '방명록 작성' }));
const ids = () => screen.queryAllByTestId('guestbook-content').map((row) => row.textContent);

test('공개 빈 목록·비회원 작성 안내·로그인/닫기, 가짜 샘플/인기순/좋아요 토글 없음', async () => {
  const f = mount({ signedIn: false, strict: true }); await screen.findByText('아직 남겨진 방명록이 없습니다.');
  assertAbsent(screen.queryByRole('textbox')); assertAbsent(screen.queryByRole('button', { name: '인기순' }));
  assertAbsent(screen.queryByRole('button', { name: /좋아요/ }));
  fireEvent.click(screen.getByRole('button', { name: '로그인' })); fireEvent.click(screen.getByRole('button', { name: '방명록 닫기' }));
  assert.deepEqual(f.navigation, ['login', 'close']); assert.equal(f.posts().length, 0);
});

test('방명록 새로고침은 장식 아이콘을 숨기고, 재조회 중에는 버튼을 비활성화한다', async () => {
  let finish!: (response: Response) => void;
  const f = mount({ list: () => new Promise((resolve) => { finish = resolve; }) });
  await screen.findByText('방명록을 불러오고 있습니다.');
  await act(async () => finish(ok(page())));
  await screen.findByText('아직 남겨진 방명록이 없습니다.');

  const refresh = screen.getByRole<HTMLButtonElement>('button', { name: '방명록 새로고침' });
  assert.ok(refresh.querySelector('svg[aria-hidden="true"]'));
  fireEvent.click(refresh);
  await waitFor(() => assert.ok(refresh.disabled));
  fireEvent.click(refresh);
  assert.equal(f.calls.length, 2);
  await act(async () => finish(ok(page([item(31)]))));
  await screen.findByText('응원 31');
  assert.equal(refresh.disabled, false);
  assert.equal(f.posts().length, 0);
});

test('우리 학교는 DB 프로필의 학교 ID를 사용하며 작성 불가·지도 이동, 비회원은 조회도 하지 않는다', async () => {
  const f = mount({ own: true }); await screen.findByText(/우리 학교에는 글을 남길 수 없어요/);
  assert.deepEqual(f.calls.map((call) => call.school), [1]); assertAbsent(screen.queryByRole('textbox'));
  fireEvent.click(screen.getByRole('button', { name: '지도로 이동' })); assert.deepEqual(f.navigation, ['map']);
  cleanup(); const guest = mount({ own: true, signedIn: false }); await screen.findByText('우리 학교 방명록을 보려면 로그인해 주세요.'); assert.equal(guest.calls.length, 0);
});

test('내 소속 확인 loading/error/retry 전에는 잘못된 열람자 캐시나 작성 폼을 만들지 않는다', async () => {
  let finish!: (response: Response) => void;
  let requests = 0;
  const f = mount({ profile: () => new Promise((resolve) => { requests++; finish = resolve; }) });
  await screen.findByText('내 소속을 확인하고 있습니다.'); assert.equal(f.calls.length, 0);
  await act(async () => finish(fail(400, 'COMMON_INVALID_INPUT'))); await screen.findByRole('button', { name: '내 소속 다시 조회' });
  fireEvent.click(screen.getByRole('button', { name: '내 소속 다시 조회' }));
  await waitFor(() => assert.equal(requests, 2));
  await act(async () => finish(ok(ME))); await screen.findByRole('form', { name: '방명록 작성' });
});

test('목록 loading/실패/재시도와 HTML 리터럴·KST·좋아요 조회값을 표시한다', async () => {
  let finish!: (response: Response) => void;
  const f = mount({ list: () => new Promise((resolve) => { finish = resolve; }) });
  await screen.findByText('방명록을 불러오고 있습니다.'); assertAbsent(screen.queryByText('아직 남겨진 방명록이 없습니다.'));
  await act(async () => finish(fail(404, 'UNIV_NOT_FOUND'))); await screen.findByRole('alert');
  fireEvent.click(screen.getByRole('button', { name: '방명록 다시 조회' }));
  await waitFor(() => assert.equal(f.calls.length, 2));
  const content = '<img src=x onerror="alert(1)">\n둘째 줄';
  await act(async () => finish(ok(page([item(31, content)])))); await screen.findByTestId('guestbook-content');
  assert.equal(ids()[0], content); assertAbsent(document.querySelector('img')); assert.equal(screen.getByRole('button', { name: '방명록 31 좋아요' }).getAttribute('aria-pressed'), 'true');
  assert.match(document.querySelector('time')?.textContent ?? '', /30\. 1\. 2\. 오전 8:59 KST/); assert.equal(f.posts().length, 0);
});

test('커서 더 보기 실패는 기존 글을 유지하며 명시적 재시도로 복구하고 중복·반복 커서에서 종료한다', async () => {
  let healthy = false;
  const f = mount({ list: async ({ cursor }) => !cursor ? ok(page([item(30), item(29)], '29'))
    : healthy ? ok(page([item(29), item(28)], '29')) : fail(400, 'COMMON_INVALID_INPUT') });
  await screen.findByText('응원 30'); fireEvent.click(screen.getByRole('button', { name: '이전 방명록 더 보기' }));
  await screen.findByRole('alert'); assert.deepEqual(ids(), ['응원 30', '응원 29']); healthy = true;
  fireEvent.click(screen.getByRole('button', { name: '이전 방명록 더 보기' })); await screen.findByText('응원 28');
  assert.deepEqual(ids(), ['응원 30', '응원 29', '응원 28']); assertAbsent(screen.queryByRole('button', { name: '이전 방명록 더 보기' }));
  assert.deepEqual(f.calls.map((call) => call.cursor), [null, '29', '29']);
});

test('입력 검증은 제출 시, 오류 포커스·입력 유지·편집 시 해제, 500 emoji는 전송한다', async () => {
  const f = mount(); await screen.findByRole('textbox'); assertAbsent(screen.queryByRole('alert'));
  fill(' '); submit(); await screen.findByText('방명록 내용을 입력해 주세요.'); assertSameNode(document.activeElement, screen.getByRole('textbox'));
  fill('가'.repeat(501)); submit(); await screen.findByText('방명록은 500자 이하로 입력해 주세요.'); assert.equal(f.posts().length, 0);
  fill('😀'.repeat(500)); assertAbsent(screen.queryByRole('alert')); submit(); await screen.findByText('방명록을 남겼습니다.');
  assert.equal(f.posts()[0].body?.content, '😀'.repeat(500));
});

test('성공 201의 마스킹된 응답만 표시·같은 프레임 이중 제출 차단·서버 조회와 ID 중복 없음', async () => {
  let finish!: (response: Response) => void;
  const f = mount({ create: () => new Promise((resolve) => { finish = resolve; }) }); await screen.findByRole('textbox');
  const listElement = screen.getByRole('list', { name: '방명록 목록' }).parentElement!;
  listElement.scrollTop = 333;
  f.queryClient.setQueryData(guestbookKeys.list(2, 31), { pages: [page()], pageParams: [null] });
  f.queryClient.setQueryData(guestbookKeys.list(3, 31), { pages: [page()], pageParams: [null] });
  fill(' badword 응원 '); submit(); submit(); await waitFor(() => assert.equal(f.posts().length, 1));
  assert.deepEqual(f.posts()[0].body, { content: 'badword 응원' }); assertAbsent(screen.queryByTestId('guestbook-content'));
  assert.equal((screen.getByRole('textbox') as HTMLTextAreaElement).value, ' badword 응원 ');
  f.rows.push(item(100, '*** 응원')); await act(async () => finish(ok(item(100, '*** 응원'), 201)));
  await screen.findByText('방명록을 남겼습니다.'); await waitFor(() => assert.deepEqual(ids(), ['*** 응원']));
  assert.equal((screen.getByRole('textbox') as HTMLTextAreaElement).value, '');
  assert.equal(listElement.scrollTop, 0, '작성한 최신 글을 확인할 수 있도록 목록 상단으로 이동');
  assert.equal(f.queryClient.getQueryState(guestbookKeys.list(2, 31))?.isInvalidated, true);
  assert.equal(f.queryClient.getQueryState(guestbookKeys.list(3, 31))?.isInvalidated, false);
});

for (const code of ['GUEST_TOO_FREQUENT', 'GUEST_OWN_UNIVERSITY', 'COMMON_INVALID_INPUT']) {
  test(`${code}: 서버 실패 시 입력·포커스 유지, 자동 재전송 없이 명시적 재시도`, async () => {
    const f = mount({ create: async () => fail(code === 'GUEST_TOO_FREQUENT' ? 429 : 400, code) }); await screen.findByRole('textbox');
    fill('응원 유지'); submit(); await screen.findByRole('alert');
    assert.equal((screen.getByRole('textbox') as HTMLTextAreaElement).value, '응원 유지'); assertSameNode(document.activeElement, screen.getByRole('textbox'));
    if (code === 'GUEST_TOO_FREQUENT') screen.getByText(/학교와 관계없이 1분에 한 번/);
    assert.equal(f.posts().length, 1); submit(); await waitFor(() => assert.equal(f.posts().length, 2));
  });
}
for (const failure of ['network', '500', 'invalid-response'] as const) {
  test(`${failure}: 등록 불명확 시 재전송을 막고 목록 확인 후 명시적 준비만 허용한다`, async () => {
    const f = mount({ create: async () => {
      if (failure === 'network') throw new TypeError('lost response');
      return failure === '500' ? fail(500, 'COMMON_INTERNAL_ERROR') : new Response('broken', { status: 201 });
    } }); await screen.findByRole('textbox'); fill('전송 불명확'); submit();
    await screen.findByText(/등록 여부를 확인할 수 없습니다/); assert.equal(screen.getByRole('button', { name: '방명록 등록' }).hasAttribute('disabled'), true);
    submit(); assert.equal(f.posts().length, 1);
    fireEvent.click(screen.getByRole('button', { name: '목록에서 확인했고 다시 보내기 준비' })); assert.equal(f.posts().length, 1);
    assert.equal(screen.getByRole('button', { name: '방명록 등록' }).hasAttribute('disabled'), false);
  });
}

test('학교 전환 중 늦은 POST 성공은 새 학교의 입력·목록에 유입되지 않는다', async () => {
  let finish!: (response: Response) => void;
  const f = mount({ create: () => new Promise((resolve) => { finish = resolve; }) }); await screen.findByRole('textbox'); fill('이전 학교'); submit();
  f.rerender(3); await screen.findByRole('textbox'); fill('새 학교');
  await act(async () => finish(ok(item(100, '이전 학교'), 201)));
  assert.equal((screen.getByRole('textbox') as HTMLTextAreaElement).value, '새 학교'); assertAbsent(screen.queryByText('이전 학교'));
});

test('없는 학교 ID는 요청하지 않으며, 회원 전환은 이전 회원의 입력과 목록을 제거한다', async () => {
  const invalid = mount(); invalid.rerender(-1); await screen.findByText('학교를 찾을 수 없습니다.');
  assert.equal(invalid.calls.some((call) => call.school < 0), false);
  cleanup(); const f = mount({ initial: [item(30)] }); await screen.findByRole('textbox'); fill('이전 회원 비공개 초안');
  await act(async () => f.queryClient.setQueryData(userKeys.me, { ...ME, id: 31 }));
  await waitFor(() => assert.equal((screen.getByRole('textbox') as HTMLTextAreaElement).value, ''));
  assert.equal(f.posts().length, 0);
});

test('로그아웃은 작성 입력과 내 좋아요를 제거하고 공개 캐시를 다시 조회한다', async () => {
  const f = mount({ initial: [item(30)] }); await screen.findByRole('textbox'); fill('비공개 작성 중');
  f.rows[0] = { ...item(30), likedByMe: false };
  await act(async () => f.client.session.signOut()); await screen.findByText('방명록을 작성하려면 로그인해 주세요.');
  await waitFor(() => assertAbsent(screen.queryByRole('button', { name: /방명록 \d+ 좋아요/ })));
  assertAbsent(screen.queryByRole('textbox')); assert.ok(f.queryClient.getQueryData(guestbookKeys.list(2, null)));
});

test('좋아요는 서버 성공 후만 반영, 같은 프레임 중복 차단, 고정 이름·pressed·포커스·다른 viewer 무효화', async () => {
  let finish!: (response: Response) => void;
  const row = { ...item(30), likedByMe: false };
  const f = mount({ initial: [row], like: () => new Promise((resolve) => { finish = resolve; }) });
  const button = await screen.findByRole('button', { name: '방명록 30 좋아요' }); button.focus();
  f.queryClient.setQueryData(guestbookKeys.list(2, 31), { pages: [page([row])], pageParams: [null] });
  f.queryClient.setQueryData(guestbookKeys.list(3, 31), { pages: [page([row])], pageParams: [null] });
  fireEvent.click(button); fireEvent.click(button); await waitFor(() => assert.equal(f.likes.length, 1));
  assert.equal(button.getAttribute('aria-disabled'), 'true'); assert.equal(button.getAttribute('aria-pressed'), 'false');
  assert.match(button.textContent ?? '', /좋아요 4개/); assertSameNode(document.activeElement, button);
  f.rows[0] = { ...row, likedByMe: true, likeCount: 9 };
  await act(async () => finish(ok({ guestbookId: 30, liked: true, likeCount: 9 })));
  await waitFor(() => assert.equal(button.getAttribute('aria-pressed'), 'true'));
  assert.match(button.textContent ?? '', /좋아요 9개/); assertSameNode(document.activeElement, button);
  assert.equal(f.queryClient.getQueryState(guestbookKeys.list(2, 31))?.isInvalidated, true);
  assert.equal(f.queryClient.getQueryState(guestbookKeys.list(3, 31))?.isInvalidated, false);
  fireEvent.click(button); await waitFor(() => assert.equal(f.likes.length, 2));
  f.rows[0] = { ...row, likeCount: 8 }; await act(async () => finish(ok({ guestbookId: 30, liked: false, likeCount: 8 })));
  await waitFor(() => assert.equal(button.getAttribute('aria-pressed'), 'false'));
  assert.deepEqual(f.likes.map((call) => call.method), ['POST', 'DELETE']);
});

for (const failure of ['network', '500', '409', '404'] as const) {
  test(`좋아요 ${failure}: 실패 시 상태 유지·자동 재전송 없음·재조회 성공 전 다음 변경 차단`, async () => {
    const row = { ...item(30), likedByMe: false };
    let readsHealthy = true;
    const f = mount({ initial: [row], list: async () => readsHealthy ? ok(page([row])) : fail(500, 'COMMON_INTERNAL_ERROR'),
      like: async () => {
        readsHealthy = false;
        if (failure === 'network') throw new TypeError('lost response');
        return fail(Number(failure), failure === '409' ? 'GUEST_ALREADY_LIKED' : failure === '404' ? 'GUEST_LIKE_NOT_FOUND' : 'COMMON_INTERNAL_ERROR');
      } });
    const button = await screen.findByRole('button', { name: '방명록 30 좋아요' }); fireEvent.click(button);
    await screen.findByText(/좋아요 상태를 다시 확인해 주세요/);
    assert.equal(button.getAttribute('aria-pressed'), 'false'); assert.match(button.textContent ?? '', /4개/);
    fireEvent.click(button); assert.equal(f.likes.length, 1);
    const retry = screen.getByRole('button', { name: '좋아요 상태 다시 조회' });
    await waitFor(() => assert.equal(retry.hasAttribute('disabled'), false)); fireEvent.click(retry);
    await waitFor(() => assert.equal(retry.hasAttribute('disabled'), false));
    assert.equal(button.getAttribute('aria-disabled'), 'true'); assert.equal(f.likes.length, 1);
    readsHealthy = true; fireEvent.click(retry);
    await waitFor(() => assertAbsent(screen.queryByRole('button', { name: '좋아요 상태 다시 조회' })));
    assert.equal(button.getAttribute('aria-disabled'), 'false'); assert.equal(f.likes.length, 1);
  });
}

test('최신 목록에 아직 없는 작성 응답의 좋아요도 서버 값으로 갱신한다', async () => {
  const f = mount({ list: async () => ok(page()), create: async () => ok({ ...item(100), likeCount: 0, likedByMe: false }, 201),
    like: async () => ok({ guestbookId: 100, liked: true, likeCount: 3 }) });
  await screen.findByRole('textbox'); fill('새 글'); submit();
  const button = await screen.findByRole('button', { name: '방명록 100 좋아요' }); fireEvent.click(button);
  await waitFor(() => assert.match(button.textContent ?? '', /좋아요 3개/)); assert.equal(button.getAttribute('aria-pressed'), 'true');
  assert.equal(f.likes.length, 1);
});

test('학교/회원 경계 뒤 늦은 좋아요 응답은 새 학교나 공개 viewer 상태를 바꾸지 않는다', async () => {
  let finish!: (response: Response) => void;
  const f = mount({ list: async ({ school }) => ok(page([{ ...item(school), likedByMe: false }])),
    like: () => new Promise((resolve) => { finish = resolve; }) });
  fireEvent.click(await screen.findByRole('button', { name: '방명록 2 좋아요' })); await waitFor(() => assert.equal(f.likes.length, 1));
  f.rerender(3); const next = await screen.findByRole('button', { name: '방명록 3 좋아요' });
  await act(async () => finish(ok({ guestbookId: 2, liked: true, likeCount: 99 })));
  assert.equal(next.getAttribute('aria-pressed'), 'false'); assert.match(next.textContent ?? '', /4개/);
  fireEvent.click(next); await waitFor(() => assert.equal(f.likes.length, 2));
  await act(async () => f.client.session.signOut()); await screen.findByText('방명록을 작성하려면 로그인해 주세요.');
  await act(async () => finish(ok({ guestbookId: 3, liked: true, likeCount: 99 })));
  assertAbsent(screen.queryByRole('button', { name: /방명록 \d+ 좋아요/ })); screen.getByLabelText('좋아요 4개');
});
