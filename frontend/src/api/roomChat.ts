// An open sheet owns one controller. Stop aborts HTTP and disconnects STOMP; no state or message queue survives logout.
import type { ApiClient } from './client';
import { errorMessage } from './errors';
import { getChatHistory, mergeChatMessages, validateChatContent, type ChatMessage, type ChatRoom } from './chat';
import { createChatConnection, type ChatConnection, type ChatConnectionFactory, type ChatConnectionStatus } from './chatConnection';

export interface ChatSnapshot {
  connection: ChatConnectionStatus;
  messages: readonly ChatMessage[];
  initialized: boolean;
  syncing: boolean;
  loadingOlder: boolean;
  hasOlder: boolean;
  cursor: number | null;
  error: string | null;
  historyError: string | null;
}

/** One room (university or alliance). Each room owns its own socket, history and recovery boundary. */
export function createRoomChat(api: ApiClient, room: ChatRoom, connect: ChatConnectionFactory = createChatConnection) {
  let state: ChatSnapshot = { connection: 'connecting', messages: [], initialized: false, syncing: false, loadingOlder: false,
    hasOlder: false, cursor: null, error: null, historyError: null };
  const listeners = new Set<() => void>();
  let running = false;
  let generation = 0;
  let socketVersion = 0;
  let recoveryBoundary: number | null = null;
  let pending: AbortController | null = null;
  let connection: ChatConnection | null = null;
  let unsubscribe: (() => void) | null = null;
  const patch = (next: Partial<ChatSnapshot>) => { state = { ...state, ...next }; for (const listener of listeners) listener(); };
  const cancel = () => { generation++; pending?.abort(); pending = null; };
  const active = (version: number) => running && version === generation;

  async function refresh() {
    if (!running || state.connection !== 'connected' || state.syncing) return;
    cancel();
    const version = generation;
    const request = new AbortController();
    pending = request;
    // Only a completed synchronization advances this boundary. New live/partially recovered messages cannot hide a gap.
    const boundary = recoveryBoundary;
    const firstLoad = !state.initialized;
    patch({ syncing: true, loadingOlder: false, historyError: null });
    try {
      let cursor: number | undefined;
      const seenCursors = new Set<number>();
      do {
        const page = await getChatHistory(api, room, cursor, request.signal);
        if (!active(version)) return;
        const overlaps = page.items.some((item) => item.messageId === boundary);
        patch({ messages: mergeChatMessages(state.messages, page.items), ...(firstLoad ? { cursor: page.nextCursor, hasOlder: page.hasNext } : {}) });
        if (firstLoad || overlaps || !page.hasNext) break;
        // A corrupt multi-page cursor cycle must not create an unbounded recovery loop.
        if (!page.nextCursor || seenCursors.has(page.nextCursor)) throw new Error('repeated chat cursor');
        seenCursors.add(page.nextCursor);
        cursor = page.nextCursor;
      } while (active(version));
      if (active(version)) {
        recoveryBoundary = state.messages.at(-1)?.messageId ?? null;
        patch({ initialized: true });
      }
    } catch (error) {
      if (active(version) && !request.signal.aborted) patch({ historyError: errorMessage(error) });
    } finally {
      if (active(version)) { pending = null; patch({ syncing: false }); }
    }
  }

  async function loadOlder() {
    if (!running || state.connection !== 'connected' || state.syncing || state.loadingOlder || !state.hasOlder || !state.cursor) return;
    const version = generation;
    const request = new AbortController();
    pending = request;
    patch({ loadingOlder: true, historyError: null });
    try {
      const page = await getChatHistory(api, room, state.cursor, request.signal);
      if (active(version)) patch({ messages: mergeChatMessages(state.messages, page.items), cursor: page.nextCursor, hasOlder: page.hasNext });
    } catch (error) {
      if (active(version) && !request.signal.aborted) patch({ historyError: errorMessage(error) });
    } finally {
      if (active(version)) { pending = null; patch({ loadingOlder: false }); }
    }
  }

  function stop() {
    running = false;
    socketVersion++;
    cancel();
    connection?.stop();
    connection = null;
    unsubscribe?.();
    unsubscribe = null;
  }
  function start() {
    if (running || !api.session.getAccessToken()) return;
    running = true;
    patch({ connection: 'connecting', syncing: false, loadingOlder: false, error: null });
    unsubscribe = api.session.subscribe(() => { if (!api.session.getAccessToken()) stop(); });
    const version = socketVersion;
    const liveSocket = () => running && version === socketVersion;
    connection = connect(api, room, {
      onStatus(status) {
        if (!liveSocket()) return;
        if (status !== 'connected') {
          cancel();
          patch({ connection: status, syncing: false, loadingOlder: false });
        } else patch({ connection: status, error: null });
      },
      onReady() { if (liveSocket()) void refresh(); },
      onMessage(message) { if (liveSocket()) patch({ messages: mergeChatMessages(state.messages, [message]) }); },
      onError(error) { if (liveSocket()) patch({ error: errorMessage(error) }); },
    });
  }
  return {
    getSnapshot: () => state,
    subscribe(listener: () => void) { listeners.add(listener); return () => listeners.delete(listener); },
    start, stop, refresh, loadOlder,
    reconnect() { stop(); start(); },
    send(input: string): boolean {
      const validated = validateChatContent(input);
      if ('problem' in validated) { patch({ error: validated.problem }); return false; }
      if (!running || state.connection !== 'connected' || !state.initialized || state.syncing) { patch({ error: '채팅이 연결되고 이력을 확인한 뒤 보내 주세요.' }); return false; }
      patch({ error: null });
      if (!connection?.send(validated.content)) { if (!state.error) patch({ error: '메시지를 보내지 못했어요. 연결을 확인해 주세요.' }); return false; }
      // Do not echo the unmasked draft or replay an uncertain send on reconnect. Only a server message creates a bubble.
      return true;
    },
  };
}
export type RoomChat = ReturnType<typeof createRoomChat>;
