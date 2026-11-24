import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { createApiClient } from '../src/api/client';
import { CHAT_PAGE_SIZE, allianceRoom, chatRoomId, chatSendDestination, chatTopic, getChatHistory, getForbiddenWords, mergeChatMessages, parseChatFault,
  parseChatMessage, universityRoom, validateChatContent } from '../src/api/chat';
import { message, page, success } from './helpers/chat';

test('채팅 길이는 trim 후 코드 포인트 1~200자이고 본문만 만든다', () => {
  for (const value of ['', ' \n ', '가'.repeat(201), '😀'.repeat(201)]) assert.ok('problem' in validateChatContent(value));
  assert.deepEqual(validateChatContent(`  ${'😀'.repeat(200)} \n`), { content: '😀'.repeat(200) });
  assert.deepEqual(validateChatContent(' 한글\n대화 '), { content: '한글\n대화' });
});

test('실시간과 이력을 ID로 병합해 오름차순 표시하고 입력 배열을 바꾸지 않는다', () => {
  const current = [message(3), message(2)];
  assert.deepEqual(mergeChatMessages(current, [message(1), message(3)]), [message(1), message(2), message(3)]);
  assert.deepEqual(current, [message(3), message(2)]);
});

test('다른 학교·잘못된 프레임은 거절하고 raw 오류 내용을 노출하지 않는다', () => {
  assert.deepEqual(parseChatMessage(JSON.stringify(message(1)), universityRoom(1)), message(1));
  for (const body of ['broken', JSON.stringify(message(1, { roomId: 'UNIV#2' })), JSON.stringify({ content: '<script>' }),
    JSON.stringify(message(1, { createdAt: 'bad' })), JSON.stringify(message(1, { messageId: 'bad' }))]) {
    assert.throws(() => parseChatMessage(body, universityRoom(1)), /서버 응답을 읽지/);
  }
  assert.deepEqual(parseChatFault('{"code":"CHAT_TOO_FREQUENT","message":"잠시 뒤 보내세요"}'), { code: 'CHAT_TOO_FREQUENT', message: '잠시 뒤 보내세요' });
  assert.equal(parseChatFault('Bearer secret').code, 'CLIENT_INVALID_RESPONSE');
  assert.doesNotMatch(parseChatFault('Bearer secret').message, /secret/);
});

test('이력은 인증 헤더·size20·cursor·중단 신호를 공통 클라이언트로 전송한다', async () => {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const client = createApiClient({ fetch: async (url, init) => { calls.push({ url: String(url), init }); return success(page([2, 1])); } });
  client.session.signIn('token');
  const controller = new AbortController();
  assert.deepEqual(await getChatHistory(client, universityRoom(1), message(3).messageId, controller.signal), page([2, 1]));
  assert.equal(calls[0].url, `/api/v1/chat/universities/1/messages?size=20&cursor=${message(3).messageId}`);
  assert.equal(calls[0].init?.signal, controller.signal);
  assert.equal(new Headers(calls[0].init?.headers).get('Authorization'), 'Bearer token');
});

test('깨진 페이지·반복 커서·다른 학교 이력은 무한 조회하지 않고 실패한다', async () => {
  for (const data of [{ ...page([1], true), nextCursor: null }, { ...page([1], true), nextCursor: message(3).messageId },
    { ...page([1]), items: [message(1, { roomId: 'UNIV#2' })] }, {}]) {
    const client = createApiClient({ fetch: async () => success(data) });
    await assert.rejects(getChatHistory(client, universityRoom(1), message(3).messageId), /서버 응답을 읽지/);
  }
});

test('연합 방은 연합 목적지·방 ID·이력 경로를 쓰고, 같은 숫자의 다른 종류 방 메시지는 거절한다', async () => {
  const room = allianceRoom(1);
  assert.equal(chatTopic(room), '/topic/chat/alliances/1');
  assert.equal(chatSendDestination(room), '/app/chat/alliance');
  assert.equal(chatRoomId(room), 'ALLIANCE#1');
  const allianceMessage = message(1, { roomId: 'ALLIANCE#1', universityName: '다른대학교' });
  assert.deepEqual(parseChatMessage(JSON.stringify(allianceMessage), room), allianceMessage);
  assert.throws(() => parseChatMessage(JSON.stringify(message(1)), room), /서버 응답을 읽지/);
  assert.throws(() => parseChatMessage(JSON.stringify(allianceMessage), universityRoom(1)), /서버 응답을 읽지/);
  const calls: string[] = [];
  const client = createApiClient({ fetch: async (url) => { calls.push(String(url)); return success({ items: [allianceMessage], hasNext: false, nextCursor: null }); } });
  assert.deepEqual((await getChatHistory(client, room)).items, [allianceMessage]);
  assert.equal(calls[0], '/api/v1/chat/alliances/1/messages?size=20');
  await assert.rejects(getChatHistory(createApiClient({ fetch: async () => success(page([1])) }), room), /서버 응답을 읽지/);
});

test('공통 금칙어 목록을 조회하고 잘못된 목록은 거절한다', async () => {
  const client = createApiClient({ fetch: async (url) => { assert.equal(url, '/api/v1/forbidden-words'); return success({ words: ['예시'] }); } });
  assert.deepEqual(await getForbiddenWords(client), ['예시']);
  await assert.rejects(getForbiddenWords(createApiClient({ fetch: async () => success({ words: [1] }) })), /서버 응답을 읽지/);
});

test('채팅 계약 센서: 경로·스키마·페이지·오류 변경 시 어댑터와 UI를 함께 검토한다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/', import.meta.url);
  const read = (path: string) => readFileSync(new URL(path, root), 'utf8');
  const fix = 'SCRUM-133·182: frontend/src/api/chat.ts, chatConnection.ts, alliances.ts(getMyAlliance)와 채팅 UI를 서버 계약에 맞게 갱신하세요.';
  const history = read('domain/chat/controller/ChatHistoryController.java');
  assert.match(history, /@GetMapping\("\/universities\/\{universityId\}\/messages"\)/, fix);
  assert.match(history, /@GetMapping\("\/alliances\/\{allianceId\}\/messages"\)/, fix);
  const send = read('domain/chat/controller/ChatMessageController.java');
  assert.match(send, /@MessageMapping\("\/chat\/university"\)/, fix);
  assert.match(send, /@MessageMapping\("\/chat\/alliance"\)/, fix);
  const destinations = read('domain/chat/websocket/ChatDestinations.java');
  assert.match(destinations, /UNIVERSITY_TOPIC_PREFIX = "\/topic\/chat\/universities\/"/, fix);
  assert.match(destinations, /ALLIANCE_TOPIC_PREFIX = "\/topic\/chat\/alliances\/"/, fix);
  const roomId = read('domain/chat/entity/ChatRoomId.java');
  assert.match(roomId, /UNIVERSITY_PREFIX = "UNIV#"/, fix);
  assert.match(roomId, /ALLIANCE_PREFIX = "ALLIANCE#"/, fix);
  assert.match(read('domain/alliance/controller/AllianceController.java'), /@GetMapping\("\/me"\)/, fix);
  assert.match(read('domain/chat/service/ChatHistoryService.java'), new RegExp(`MAX_PAGE_SIZE = ${CHAT_PAGE_SIZE};`), fix);
  const dto = read('domain/chat/dto/ChatMessageResponse.java');
  for (const field of ['messageId', 'roomId', 'userId', 'nickname', 'universityName', 'content', 'createdAt']) assert.match(dto, new RegExp(`\\b${field}\\b`), fix);
  assert.match(read('domain/forbiddenword/dto/ForbiddenWordsResponse.java'), /List<String> words/, fix);
});

test('실제 채팅은 지연 로드하고 API 미리보기는 샘플 메시지·가짜 읽지 않은 수를 숨긴다', () => {
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  const panel = readFileSync(new URL('../src/components/GameSchoolPanel.tsx', import.meta.url), 'utf8');
  assert.match(panel, /lazy\(\(\) => import\('\.\/ApiChatBottomSheet'\)/);
  assert.match(panel, /<LazyApiChat schoolOnly/);
  assert.match(app, /<LazyApiChat/);
  assert.match(app, /unreadCount=\{API_MODE === 'api' \? 0 : 12\}/);
  assert.match(app, /latestMessage=\{API_MODE === 'api' \? '열어서 대화하기'/);
});
