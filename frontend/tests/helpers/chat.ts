import type { ChatMessage, ChatPage } from '../../src/api/chat';

export const message = (id: number, overrides: Partial<ChatMessage> = {}): ChatMessage => ({
  messageId: id, roomId: 'UNIV#1', userId: 5, nickname: '학생', universityName: '한양대학교',
  content: `메시지 ${id}`, createdAt: '2026-11-19T13:00:00+09:00', ...overrides,
});
export const page = (ids: number[], hasNext = false): ChatPage => ({
  items: ids.map((id) => message(id)), hasNext, nextCursor: hasNext ? message(ids.at(-1)!).messageId : null,
});
export const success = (data: unknown) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status: 200 });
export const flush = async () => { for (let i = 0; i < 5; i++) await new Promise((resolve) => setTimeout(resolve, 0)); };
