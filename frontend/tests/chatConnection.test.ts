import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Client, FrameImpl, ReconnectionTimeMode, type messageCallbackType } from '@stomp/stompjs';
import { createApiClient } from '../src/api/client';
import { chatSocketUrl, createChatConnection, type ChatConnectionStatus } from '../src/api/chatConnection';
import { CHAT_ERROR_DESTINATION, allianceRoom, chatTopic, universityRoom, type ChatRoom } from '../src/api/chat';
import { flush, message, success } from './helpers/chat';

const frame = (body: unknown) => Object.assign(new FrameImpl({ command: 'MESSAGE', body: JSON.stringify(body) }), {
  ack() {}, nack() {}, // Real MESSAGE callbacks include acknowledgement methods even with automatic acknowledgement.
});
function setup(fetch?: typeof globalThis.fetch, room: ChatRoom = universityRoom(1), sharedApi?: ReturnType<typeof createApiClient>) {
  const api = sharedApi ?? createApiClient({ fetch });
  if (!sharedApi) api.session.signIn('old');
  const statuses: ChatConnectionStatus[] = [], errors: unknown[] = [], messages: unknown[] = [], published: unknown[] = [];
  const subscriptions = new Map<string, messageCallbackType>();
  let ready = 0, activates = 0, deactivates = 0, online = true;
  let socket!: Client;
  const connection = createChatConnection(api, room, { onStatus: (value) => statuses.push(value), onReady: () => ready++,
    onError: (value) => errors.push(value), onMessage: (value) => messages.push(value) }, {
    origin: 'https://campus.example/screen', createClient: (config) => {
      socket = new Client(config);
      socket.activate = () => { activates++; };
      socket.deactivate = async () => { deactivates++; };
      socket.subscribe = (destination, callback) => { subscriptions.set(destination, callback); return { id: destination, unsubscribe() {} }; };
      socket.publish = (value) => { published.push(value); };
      Object.defineProperty(socket, 'connected', { get: () => online });
      return socket;
    },
  });
  return { api, socket, connection, statuses, errors, messages, published, subscriptions,
    counts: () => ({ ready, activates, deactivates }), offline: () => { online = false; } };
}

test('same origin ws/wss, 토큰은 CONNECT 헤더만, 백오프·heartbeat는 라이브러리에 맡긴다', async () => {
  assert.equal(chatSocketUrl('http://localhost:3000/path'), 'ws://localhost:3000/ws');
  const f = setup();
  assert.equal(f.socket.brokerURL, 'wss://campus.example/ws');
  assert.equal(f.socket.reconnectTimeMode, ReconnectionTimeMode.EXPONENTIAL);
  assert.equal(f.socket.reconnectDelay, 1000);
  assert.equal(f.socket.maxReconnectDelay, 30000);
  assert.equal(f.socket.heartbeatIncoming, 10000);
  assert.equal(f.socket.heartbeatOutgoing, 10000);
  await f.socket.beforeConnect(f.socket);
  assert.deepEqual(f.socket.connectHeaders, { Authorization: 'Bearer old' });
  f.api.session.signIn('fresh');
  await f.socket.beforeConnect(f.socket);
  assert.deepEqual(f.socket.connectHeaders, { Authorization: 'Bearer fresh' });
  f.socket.onConnect(frame({}));
  assert.deepEqual([...f.subscriptions.keys()], [CHAT_ERROR_DESTINATION, chatTopic(universityRoom(1))]);
  assert.equal(f.counts().ready, 1);
  f.connection.send('서버로');
  assert.deepEqual(f.published, [{ destination: '/app/chat/university', headers: { 'content-type': 'application/json' }, body: '{"content":"서버로"}' }]);
  f.connection.stop();
});

test('서버 메시지만 수신하고, 잘못된 방·프레임은 오류로 처리한다', () => {
  const f = setup();
  f.socket.onConnect(frame({}));
  const subscription = f.subscriptions.get(chatTopic(universityRoom(1)))!;
  subscription(frame(message(1)));
  subscription(frame(message(2, { roomId: 'UNIV#2' })));
  assert.deepEqual(f.messages, [message(1)]);
  assert.equal(f.errors.length, 1);
  f.subscriptions.get(CHAT_ERROR_DESTINATION)!(frame({ code: 'CHAT_TOO_FREQUENT', message: '잠시 뒤 보내세요' }));
  assert.equal(f.errors.length, 2);
  assert.equal(f.statuses.at(-1), 'connected');
  f.offline();
  f.socket.onWebSocketClose(new Event('close') as CloseEvent);
  assert.equal(f.statuses.at(-1), 'reconnecting');
  assert.equal(f.connection.send('재전송하지 않음'), false);
  assert.deepEqual(f.published, []);
  f.connection.stop();
  subscription(frame(message(3)));
  f.socket.onConnect(frame({}));
  assert.deepEqual(f.messages, [message(1)]);
  assert.equal(f.counts().ready, 1);
});

test('연합 방은 연합 토픽을 구독하고 방 ID 없이 연합 전송 목적지로 보낸다', () => {
  const f = setup(undefined, allianceRoom(7));
  f.socket.onConnect(frame({}));
  assert.deepEqual([...f.subscriptions.keys()], [CHAT_ERROR_DESTINATION, '/topic/chat/alliances/7']);
  const subscription = f.subscriptions.get('/topic/chat/alliances/7')!;
  subscription(frame(message(1, { roomId: 'ALLIANCE#7' })));
  subscription(frame(message(2, { roomId: 'UNIV#7' })));
  assert.deepEqual(f.messages, [message(1, { roomId: 'ALLIANCE#7' })]);
  assert.equal(f.errors.length, 1);
  f.connection.send('연합에게');
  assert.deepEqual(f.published, [{ destination: '/app/chat/alliance', headers: { 'content-type': 'application/json' }, body: '{"content":"연합에게"}' }]);
  f.connection.stop();
});

test('만료 CONNECT는 공통 single-flight 재발급 후 새 토큰으로 다시 연결한다', async () => {
  let calls = 0;
  const f = setup(async (url) => { assert.equal(url, '/api/v1/auth/reissue'); calls++; return success({ accessToken: 'new', expiresInSeconds: 3600 }); });
  await f.socket.beforeConnect(f.socket);
  f.socket.onStompError(frame({ code: 'COMMON_EXPIRED_TOKEN', message: '만료' }));
  await flush();
  assert.equal(calls, 1);
  assert.equal(f.counts().activates, 2);
  await f.socket.beforeConnect(f.socket);
  assert.equal(f.socket.connectHeaders.Authorization, 'Bearer new');
  f.connection.stop();
});

test('HTTP가 이미 갱신한 토큰이면 다시 재발급하지 않으며, 닫힌 패널을 살리지 않는다', async () => {
  let calls = 0;
  const f = setup(async () => { calls++; return success({ accessToken: 'new', expiresInSeconds: 3600 }); });
  await f.socket.beforeConnect(f.socket);
  f.api.session.signIn('new');
  f.socket.onStompError(frame({ code: 'COMMON_EXPIRED_TOKEN', message: '만료' }));
  await flush();
  assert.equal(calls, 0);
  assert.equal(f.counts().activates, 2);
  f.connection.stop();
  const before = f.statuses.length;
  await f.socket.beforeConnect(f.socket);
  assert.equal(f.statuses.length, before);
});

test('같은 토큰 반복 거부·치명적 인가 오류는 자동 연결 루프를 멈춘다', async () => {
  const f = setup(async () => success({ accessToken: 'old', expiresInSeconds: 3600 }));
  await f.socket.beforeConnect(f.socket);
  f.socket.onStompError(frame({ code: 'COMMON_EXPIRED_TOKEN', message: '만료' }));
  await flush();
  await f.socket.beforeConnect(f.socket);
  f.socket.onStompError(frame({ code: 'COMMON_EXPIRED_TOKEN', message: '만료' }));
  await flush();
  assert.equal(f.statuses.at(-1), 'blocked');
  assert.equal(f.counts().activates, 2);
  f.connection.stop();
  const forbidden = setup();
  forbidden.socket.onStompError(frame({ code: 'CHAT_NOT_MEMBER', message: '다른 학교' }));
  assert.equal(forbidden.statuses.at(-1), 'blocked');
  assert.equal(forbidden.counts().activates, 1);
  forbidden.connection.stop();
});

test('오류 큐의 방 권한 상실은 해당 소켓만 멈추고 일시적 오류는 연결을 유지한다', () => {
  const alliance = setup(undefined, allianceRoom(7));
  const school = setup(undefined, universityRoom(1), alliance.api);
  for (const room of [alliance, school]) room.socket.onConnect(frame({}));
  const errors = alliance.subscriptions.get(CHAT_ERROR_DESTINATION)!;
  errors(frame({ code: 'COMMON_INTERNAL_ERROR', message: '일시적 오류' }));
  assert.equal(alliance.statuses.at(-1), 'connected');
  assert.equal(alliance.counts().deactivates, 0);

  errors(frame({ code: 'CHAT_NOT_MEMBER', message: '채팅방 권한 없음' }));
  alliance.socket.onWebSocketClose(new Event('close') as CloseEvent);
  assert.equal(alliance.statuses.at(-1), 'blocked');
  assert.equal(alliance.counts().deactivates, 1);
  assert.equal(alliance.counts().activates, 1);
  assert.equal(alliance.errors.length, 2);
  assert.equal(alliance.connection.send('차단됨'), false);
  assert.equal(alliance.api.session.getState().status, 'signed-in');
  assert.equal(school.statuses.at(-1), 'connected');
  assert.equal(school.connection.send('교내 채팅 정상'), true);
});

test('사용자 큐의 정지·ERROR 프레임의 세션 교체는 기존 로그인 세션을 종료한다', () => {
  for (const code of ['USER_SUSPENDED', 'AUTH_SESSION_REPLACED']) {
    const f = setup();
    f.socket.onConnect(frame({}));
    if (code === 'USER_SUSPENDED') f.subscriptions.get(CHAT_ERROR_DESTINATION)!(frame({ code, message: '다시 로그인' }));
    else f.socket.onStompError(frame({ code, message: '다시 로그인' }));
    assert.equal(f.api.session.getState().status, 'signed-out');
    assert.equal(f.statuses.at(-1), 'blocked');
    assert.equal(f.connection.send('안 됨'), false);
    f.connection.stop();
  }
});
