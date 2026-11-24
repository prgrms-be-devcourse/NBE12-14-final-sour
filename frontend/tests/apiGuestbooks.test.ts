import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { createApiClient } from '../src/api/client';
import { createGuestbook, getGuestbooks, setGuestbookLike, guestbookKeys, mergeGuestbookPages, validateGuestbookContent, type GuestbookItem } from '../src/api/guestbooks';

const item = (id: number): GuestbookItem => ({ id, content: `응원 ${id}`, likeCount: 2, likedByMe: false,
  author: { nickname: '방문학생', universityName: '한양대학교' }, createdAt: '2026-11-19T05:00:00+09:00' });
const page = (items: GuestbookItem[]) => ({ items, nextCursor: null, hasNext: false });
const ok = (data: unknown, status = 200) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status });

test('방명록 GET의 공개 조회·선택 인증·커서·크기·취소 신호, POST는 content만 보낸다', async () => {
  const calls: Array<{ path: string; auth: string | null; body: unknown; signal: AbortSignal | null | undefined }> = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    calls.push({ path: String(input), auth: new Headers(init?.headers).get('Authorization'),
      body: init?.body ? JSON.parse(String(init.body)) : null, signal: init?.signal });
    return ok(init?.method === 'POST' ? item(31) : page([item(30)]), init?.method === 'POST' ? 201 : 200);
  }) as typeof fetch });
  const signal = new AbortController().signal;
  assert.deepEqual(await getGuestbooks(client, 2, null, signal), page([item(30)]));
  client.session.signIn('member');
  await getGuestbooks(client, 2, '30', signal);
  assert.deepEqual(await createGuestbook(client, 2, '응원'), item(31));
  assert.deepEqual(calls.map((call) => call.path), ['/api/v1/universities/2/guestbooks?size=20',
    '/api/v1/universities/2/guestbooks?size=20&cursor=30', '/api/v1/universities/2/guestbooks']);
  assert.equal(calls[0].auth, null); assert.equal(calls[1].auth, 'Bearer member'); assert.equal(calls[1].signal, signal);
  assert.equal(calls[2].auth, 'Bearer member'); assert.deepEqual(calls[2].body, { content: '응원' });
});

test('좋아요 POST/DELETE는 로그인 토큰·글 ID만 전송하고 명세 응답을 그대로 반환한다', async () => {
  const calls: Array<{ path: string; method: string | undefined; body: unknown; auth: string | null }> = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    calls.push({ path: String(input), method: init?.method, body: init?.body, auth: new Headers(init?.headers).get('Authorization') });
    return ok({ guestbookId: 31, liked: init?.method === 'POST', likeCount: init?.method === 'POST' ? 7 : 6 });
  }) as typeof fetch });
  client.session.signIn('member');
  assert.deepEqual(await setGuestbookLike(client, 31, true), { guestbookId: 31, liked: true, likeCount: 7 });
  assert.deepEqual(await setGuestbookLike(client, 31, false), { guestbookId: 31, liked: false, likeCount: 6 });
  assert.deepEqual(calls, ['POST', 'DELETE'].map((method) => ({ path: '/api/v1/guestbooks/31/likes', method, body: undefined, auth: 'Bearer member' })));
});

test('다른 글·다른 의도·음수·문자열·null 좋아요 응답은 UI 캐시에 넣지 않는다', async () => {
  for (const data of [null, { guestbookId: 99, liked: true, likeCount: 3 }, { guestbookId: 31, liked: false, likeCount: 3 },
    { guestbookId: 31, liked: true, likeCount: -1 }, { guestbookId: 31, liked: true, likeCount: '3' }]) {
    const client = createApiClient({ fetch: (async () => ok(data)) as typeof fetch });
    await assert.rejects(setGuestbookLike(client, 31, true), { code: 'CLIENT_INVALID_RESPONSE' });
  }
});

test('내용은 trim 후 Unicode 1–500자: 공백·501자는 거부하고 emoji 500자는 허용한다', () => {
  assert.deepEqual(validateGuestbookContent(' \n '), { problem: '방명록 내용을 입력해 주세요.' });
  assert.deepEqual(validateGuestbookContent(' 응원\n둘째 줄 '), { content: '응원\n둘째 줄' });
  assert.deepEqual(validateGuestbookContent('😀'.repeat(500)), { content: '😀'.repeat(500) });
  assert.deepEqual(validateGuestbookContent('가'.repeat(501)), { problem: '방명록은 500자 이하로 입력해 주세요.' });
});

test('학교/열람자별 좋아요 조회 캐시 분리, 페이지 병합은 서버 순서와 최신 조회 좋아요를 유지한다', () => {
  assert.notDeepEqual(guestbookKeys.list(1, null), guestbookKeys.list(1, 10));
  assert.notDeepEqual(guestbookKeys.list(1, 10), guestbookKeys.list(1, 11));
  assert.notDeepEqual(guestbookKeys.list(1, 10), guestbookKeys.list(2, 10));
  const current = { ...item(30), likedByMe: true, likeCount: 7 };
  const pages = [page([item(31), current]), page([item(30), item(29)])];
  assert.deepEqual(mergeGuestbookPages(pages, item(32)).map((row) => row.id), [32, 31, 30, 29]);
  assert.deepEqual(mergeGuestbookPages(pages, item(30))[1], current);
});

test('계약 센서: 서비스의 500 code-point 길이와 1분 제한, 생성 응답 DTO에 대응한다', () => {
  const base = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/guestbook/', import.meta.url);
  const service = readFileSync(new URL('service/GuestBookWriteService.java', base), 'utf8');
  const controller = readFileSync(new URL('controller/GuestBookController.java', base), 'utf8');
  assert.match(service, /MAX_CONTENT_LENGTH = 500/); assert.match(service, /Duration.ofMinutes\(1\)/);
  assert.match(service, /codePointCount/); assert.match(service, /GUEST_OWN_UNIVERSITY/);
  assert.match(controller, /ApiResponse<GuestBookListResponse.Item> createGuestBook/);
});

test('좋아요 계약 센서: JPA 서버의 POST·DELETE 경로·응답·없는 좋아요 오류와 일치해야 한다', () => {
  const base = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/guestbook/', import.meta.url);
  const controller = readFileSync(new URL('controller/GuestBookLikeController.java', base), 'utf8');
  const response = readFileSync(new URL('dto/GuestBookLikeResponse.java', base), 'utf8');
  const errors = readFileSync(new URL('../../global/exception/ErrorCode.java', base), 'utf8');
  assert.match(controller, /@RequestMapping\("\/api\/v1\/guestbooks"\)/, '좋아요 기본 경로가 바뀌면 API 클라이언트도 함께 갱신하세요.');
  for (const method of ['Post', 'Delete']) {
    assert.ok(controller.includes(`@${method}Mapping("/{guestbookId}/likes")`), `${method} 좋아요 매핑 누락: 서버와 프론트를 같은 기능 단위로 적용하세요.`);
  }
  assert.match(response, /Long guestbookId/);
  assert.match(response, /boolean liked/);
  assert.match(response, /int likeCount/);
  assert.match(errors, /GUEST_ALREADY_LIKED\(HttpStatus.CONFLICT/);
  assert.match(errors, /GUEST_LIKE_NOT_FOUND\(HttpStatus.NOT_FOUND/);
});
