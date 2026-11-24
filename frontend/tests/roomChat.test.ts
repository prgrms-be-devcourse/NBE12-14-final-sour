import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createApiClient } from '../src/api/client';
import { universityRoom } from '../src/api/chat';
import { createRoomChat } from '../src/api/roomChat';
import { ApiError } from '../src/api/errors';
import type { ChatCallbacks } from '../src/api/chatConnection';
import { flush, message, page, success } from './helpers/chat';

function setup(fetch: typeof globalThis.fetch) {
  const api = createApiClient({ fetch });
  api.session.signIn('token');
  const callbacks: ChatCallbacks[] = [], sends: string[] = [], stopped: number[] = [];
  const chat = createRoomChat(api, universityRoom(1), (_api, _room, events) => {
    const index = callbacks.push(events) - 1;
    return { send: (content) => { sends.push(content); return true; }, stop: () => stopped.push(index) };
  });
  const ready = () => { callbacks.at(-1)!.onStatus('connected'); callbacks.at(-1)!.onReady(); };
  const ids = () => chat.getSnapshot().messages.map((item) => Number(item.messageId));
  chat.start();
  return { api, chat, callbacks, sends, stopped, ready, ids };
}
const range = (high: number, low: number) => Array.from({ length: high - low + 1 }, (_, i) => high - i);

test('구독 후 최신20개를 받으며 실시간·이력 중복을 제거하고 보내기는 서버 응답만 표시한다', async () => {
  let finish!: (value: Response) => void;
  const f = setup(async () => new Promise((resolve) => { finish = resolve; }));
  assert.equal(f.chat.send('연결 전'), false);
  f.ready(); await flush();
  f.callbacks[0].onMessage(message(21));
  finish(success(page(range(20, 1), true)));
  await flush();
  assert.deepEqual(f.ids(), range(21, 1).reverse());
  assert.equal(f.chat.getSnapshot().hasOlder, true);
  f.callbacks[0].onMessage(message(20));
  assert.equal(f.ids().length, 21);
  assert.equal(f.chat.send('  서버로  '), true);
  assert.deepEqual(f.sends, ['서버로']);
  assert.equal(f.ids().length, 21, '원문을 낙관적 버블로 표시하지 않는다');
  assert.equal(f.chat.send('😀'.repeat(201)), false);
  f.chat.stop();
});

test('최초 이력 실패 뒤에는 소켓이 연결되어도 전송하지 않고 이력 재시도 성공 뒤 허용한다', async () => {
  let failure = true;
  const f = setup(async () => failure ? new Response(JSON.stringify({ code: 'COMMON_INTERNAL_ERROR', message: '이력 실패' }), { status: 500 }) : success(page([])));
  f.ready(); await flush();
  assert.equal(f.chat.getSnapshot().connection, 'connected');
  assert.equal(f.chat.getSnapshot().syncing, false);
  assert.equal(f.chat.getSnapshot().initialized, false);
  assert.equal(f.chat.send('아직 보내지 않음'), false);
  assert.deepEqual(f.sends, []);
  failure = false;
  await f.chat.refresh();
  assert.equal(f.chat.send('이력 확인 뒤 전송'), true);
  assert.deepEqual(f.sends, ['이력 확인 뒤 전송']);
  f.chat.stop();
});

test('과거 메시지는20개 커서로 중복 요청 없이 읽고 hasNext=false에서 멈춘다', async () => {
  const cursors: Array<string | null> = [];
  let finish!: (value: Response) => void;
  const f = setup(async (url) => {
    const cursor = new URL(String(url), 'http://local').searchParams.get('cursor');
    cursors.push(cursor);
    return cursor ? new Promise((resolve) => { finish = resolve; }) : success(page(range(40, 21), true));
  });
  f.ready(); await flush();
  const older = f.chat.loadOlder();
  await f.chat.loadOlder();
  assert.deepEqual(cursors, [null, message(21).messageId]);
  finish(success(page(range(20, 1))));
  await older;
  await f.chat.loadOlder();
  assert.equal(cursors.length, 2);
  assert.deepEqual(f.ids(), range(40, 1).reverse());
  assert.equal(f.chat.getSnapshot().hasOlder, false);
  f.chat.stop();
});

test('재연결 공백이20개를 넘어도 완료 경계까지 모두 복구하며 새 live 메시지가 조기 중단시키지 않는다', async () => {
  const cursors: Array<string | null> = [];
  let recovering = false;
  const f = setup(async (url) => {
    const cursor = new URL(String(url), 'http://local').searchParams.get('cursor');
    cursors.push(cursor);
    if (!recovering) return success(page(range(20, 1), true));
    if (!cursor) return success(page(range(65, 46), true));
    if (cursor === message(46).messageId) return success(page(range(45, 26), true));
    assert.equal(cursor, message(26).messageId);
    return success(page(range(25, 6), true));
  });
  f.ready(); await flush();
  f.callbacks[0].onStatus('reconnecting');
  assert.equal(f.chat.send('안 보내짐'), false);
  recovering = true;
  f.callbacks[0].onMessage(message(65)); // It overlaps the next page, but is NOT the recovery boundary.
  f.ready(); await flush();
  assert.deepEqual(cursors, [null, null, message(46).messageId, message(26).messageId]);
  assert.deepEqual(f.ids(), range(65, 1).reverse());
  assert.equal(f.chat.getSnapshot().cursor, message(1).messageId, '재연결이 과거 페이지 커서를 덮지 않는다');
  assert.equal(f.chat.getSnapshot().syncing, false);
  f.chat.stop();
});

test('실패한 복구의 부분 결과는 경계를 전진시키지 않고 다시 시도하면 공백을 끝까지 채운다', async () => {
  let recovering = false, fail = true;
  const f = setup(async (url) => {
    const cursor = new URL(String(url), 'http://local').searchParams.get('cursor');
    if (!recovering) return success(page([2, 1]));
    if (!cursor) return success(page(range(30, 11), true));
    if (fail) throw new Error('offline');
    return success(page(range(10, 1)));
  });
  f.ready(); await flush();
  recovering = true;
  f.callbacks[0].onStatus('reconnecting'); f.ready(); await flush();
  assert.ok(f.chat.getSnapshot().historyError);
  assert.ok(!f.ids().includes(10));
  fail = false;
  await f.chat.refresh();
  assert.deepEqual(f.ids(), range(30, 1).reverse());
  assert.equal(f.chat.getSnapshot().historyError, null);
  f.chat.stop();
});

test('해제·재연결·로그아웃은 HTTP를 중단하고 늦은 응답과 이전 소켓 콜백을 무시한다', async () => {
  let finish!: (value: Response) => void;
  let signal: AbortSignal | null | undefined;
  const f = setup(async (_url, init) => { signal = init?.signal; return new Promise((resolve) => { finish = resolve; }); });
  f.ready(); await flush();
  f.chat.reconnect();
  assert.equal(signal?.aborted, true);
  finish(success(page([1]))); await flush();
  f.callbacks[0].onMessage(message(10));
  f.callbacks[0].onStatus('blocked');
  assert.deepEqual(f.ids(), []);
  assert.equal(f.chat.getSnapshot().connection, 'connecting');
  f.api.session.signOut();
  f.callbacks[1].onMessage(message(20));
  assert.deepEqual(f.ids(), []);
  assert.deepEqual(f.stopped, [0, 1]);
});

test('연결이 끊긴 동안 끝난 HTTP가 새 재연결의 이력을 덮지 못한다', async () => {
  const finishes: Array<(value: Response) => void> = [];
  const f = setup(async () => new Promise((resolve) => finishes.push(resolve)));
  f.ready(); await flush();
  f.callbacks[0].onStatus('reconnecting');
  f.ready(); await flush();
  finishes[1](success(page([2]))); await flush();
  finishes[0](success(page([1]))); await flush();
  assert.deepEqual(f.ids(), [2]);
  f.chat.stop();
});

test('서버 처리 오류를 표시하되 연결은 유지하고 자동 재전송하지 않는다', async () => {
  const f = setup(async () => success(page([])));
  f.ready(); await flush();
  assert.equal(f.chat.send('한 번만'), true);
  f.callbacks[0].onError(new ApiError({ kind: 'http', status: 429, code: 'CHAT_TOO_FREQUENT', serverMessage: '천천히 보내세요' }));
  assert.equal(f.chat.getSnapshot().error, '천천히 보내세요');
  assert.equal(f.chat.getSnapshot().connection, 'connected');
  assert.deepEqual(f.sends, ['한 번만']);
  f.chat.stop();
});
