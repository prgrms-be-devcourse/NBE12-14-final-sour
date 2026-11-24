import { Client, ReconnectionTimeMode, type StompConfig } from '@stomp/stompjs';
import type { ApiClient } from './client';
import { EXPIRED_TOKEN_CODE } from './client';
import { ApiError, CLIENT_ERROR_CODES } from './errors';
import { sessionEndReason } from './session';
import { CHAT_ERROR_DESTINATION, chatSendDestination, chatTopic, parseChatFault, parseChatMessage, type ChatMessage, type ChatRoom } from './chat';

export type ChatConnectionStatus = 'connecting' | 'connected' | 'reconnecting' | 'blocked';
export interface ChatCallbacks {
  onStatus(status: ChatConnectionStatus): void;
  onReady(): void;
  onMessage(message: ChatMessage): void;
  onError(error: unknown): void;
}
export interface ChatConnection { send(content: string): boolean; stop(): void }
export type ChatConnectionFactory = (api: ApiClient, room: ChatRoom, callbacks: ChatCallbacks) => ChatConnection;
type StompPort = Pick<Client, 'activate' | 'deactivate' | 'publish' | 'subscribe' | 'connected' | 'connectHeaders'>;

export function chatSocketUrl(origin: string): string {
  const url = new URL('/ws', origin);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.href;
}

/** No raw frames/debug logs. The library owns framing, heartbeats and exponential network reconnects. */
export function createChatConnection(api: ApiClient, room: ChatRoom, callbacks: ChatCallbacks,
  options: { origin?: string; createClient?: (config: StompConfig) => StompPort } = {}): ChatConnection {
  let stopped = false;
  let halted = false;
  let rejectedToken: string | null = null;
  const live = () => !stopped && !halted;
  const halt = () => { halted = true; callbacks.onStatus('blocked'); void socket.deactivate({ force: true }); };
  const report = (body: string) => {
    const fault = parseChatFault(body);
    const error = new ApiError({ kind: 'http', status: null, code: fault.code, serverMessage: fault.message });
    if (sessionEndReason(fault.code)) { api.session.end(error); halt(); }
    callbacks.onError(error);
    return fault;
  };
  const socket = (options.createClient ?? ((config) => new Client(config)))({
    brokerURL: chatSocketUrl(options.origin ?? window.location.href),
    heartbeatIncoming: 10_000, heartbeatOutgoing: 10_000, connectionTimeout: 10_000,
    reconnectDelay: 1_000, maxReconnectDelay: 30_000, reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    debug: () => {}, logRawCommunication: false,
    beforeConnect: () => {
      const token = api.session.getAccessToken();
      if (stopped) { void socket.deactivate({ force: true }); return; }
      if (!live() || !token) { halt(); return; }
      socket.connectHeaders = { Authorization: `Bearer ${token}` };
    },
    onConnect: () => {
      if (!live()) return;
      socket.subscribe(CHAT_ERROR_DESTINATION, (frame) => {
        if (!live()) return;
        const fault = report(frame.body);
        if (fault.code === 'CHAT_NOT_MEMBER') halt();
      });
      socket.subscribe(chatTopic(room), (frame) => {
        if (!live()) return;
        try { callbacks.onMessage(parseChatMessage(frame.body, room)); } catch (error) { callbacks.onError(error); }
      });
      callbacks.onStatus('connected');
      // Subscribe first; history then merges with any messages arriving while HTTP is in flight.
      callbacks.onReady();
    },
    onWebSocketClose: () => { if (live()) callbacks.onStatus('reconnecting'); },
    onStompError: (frame) => {
      if (!live()) return;
      const fault = parseChatFault(frame.body);
      const token = socket.connectHeaders.Authorization?.replace(/^Bearer /, '') ?? null;
      if (fault.code !== EXPIRED_TOKEN_CODE || !token || token === rejectedToken) { report(frame.body); halt(); return; }
      // Stop auto reconnect before refreshing. A repeated rejection of this token must not cause a refresh loop.
      rejectedToken = token;
      halted = true;
      callbacks.onStatus('reconnecting');
      void socket.deactivate({ force: true }).then(async () => {
        if (stopped) return;
        if (api.session.getAccessToken() === token) await api.reissue(); // shared with concurrent HTTP expiry handling
        if (stopped || !api.session.getAccessToken()) return;
        halted = false;
        socket.activate();
      }).catch((error: unknown) => { if (!stopped) { callbacks.onError(error); halt(); } });
    },
  });
  callbacks.onStatus('connecting');
  socket.activate();
  return {
    send(content) {
      if (!live() || !socket.connected) return false;
      try { socket.publish({ destination: chatSendDestination(room), headers: { 'content-type': 'application/json' }, body: JSON.stringify({ content }) }); return true; }
      catch {
        callbacks.onError(new ApiError({ kind: 'network', status: null, code: CLIENT_ERROR_CODES.network }));
        return false;
      }
    },
    stop() { stopped = true; void socket.deactivate({ force: true }); },
  };
}
