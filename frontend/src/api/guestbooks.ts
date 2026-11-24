// SCRUM-181: server-backed guestbooks and authenticated like mutations.
import { useInfiniteQuery, useMutation, useQueryClient, type InfiniteData } from '@tanstack/react-query';
import { useApiClient } from './ApiProvider';
import type { ApiClient } from './client';
import { ApiError, CLIENT_ERROR_CODES } from './errors';

/** GuestBookListResponse.Item. No author ID/profile image or total count is supplied. */
export interface GuestbookItem {
  id: number;
  content: string;
  likeCount: number;
  likedByMe: boolean;
  author: { nickname: string; universityName: string };
  createdAt: string;
}
export interface GuestbookPage {
  items: GuestbookItem[];
  nextCursor: string | null;
  hasNext: boolean;
}
export interface GuestbookLike {
  guestbookId: number;
  liked: boolean;
  likeCount: number;
}
export const GUESTBOOK_PAGE_SIZE = 20;
export const GUESTBOOK_MAX_LENGTH = 500;

/** Service validation counts Unicode code points, not UTF-16 units (emoji count as one). */
export function validateGuestbookContent(input: string): { content: string } | { problem: string } {
  const content = input.trim();
  if (!content) return { problem: '방명록 내용을 입력해 주세요.' };
  if ([...content].length > GUESTBOOK_MAX_LENGTH) return { problem: '방명록은 500자 이하로 입력해 주세요.' };
  return { content };
}

export function getGuestbooks(client: ApiClient, universityId: number, cursor: string | null, signal?: AbortSignal): Promise<GuestbookPage> {
  // Auth is optional, not anonymous: the server needs the member token for likedByMe.
  return client.get(`/universities/${universityId}/guestbooks`, { query: { size: GUESTBOOK_PAGE_SIZE, cursor: cursor ?? undefined }, signal });
}
export function createGuestbook(client: ApiClient, universityId: number, content: string): Promise<GuestbookItem> {
  return client.request(`/universities/${universityId}/guestbooks`, { method: 'POST', body: { content } });
}
export async function setGuestbookLike(client: ApiClient, guestbookId: number, liked: boolean): Promise<GuestbookLike> {
  const result = await client.request<GuestbookLike>(`/guestbooks/${guestbookId}/likes`, { method: liked ? 'POST' : 'DELETE' });
  if (!result || result.guestbookId !== guestbookId || result.liked !== liked || !Number.isSafeInteger(result.likeCount) || result.likeCount < 0) {
    throw new ApiError({ kind: 'invalid-response', status: null, code: CLIENT_ERROR_CODES.invalidResponse });
  }
  return result;
}
export const guestbookKeys = {
  school: (universityId: number) => ['guestbooks', universityId] as const,
  list: (universityId: number, viewerId: number | null) => ['guestbooks', universityId, viewerId ?? 'guest'] as const,
};

/** Preserve the server's latest-first order; a concurrently inserted/refetched row must not appear twice. */
export function mergeGuestbookPages(pages: readonly GuestbookPage[], created?: GuestbookItem): GuestbookItem[] {
  const rows = new Map<number, GuestbookItem>();
  // Prefer a current list's count/likedByMe over the original creation response.
  for (const page of pages) for (const item of page.items) if (!rows.has(item.id)) rows.set(item.id, item);
  if (created && !rows.has(created.id)) return [created, ...rows.values()];
  return [...rows.values()];
}
export function useGuestbookPages(universityId: number, viewerId: number | null) {
  const client = useApiClient();
  return useInfiniteQuery({
    queryKey: guestbookKeys.list(universityId, viewerId),
    queryFn: ({ pageParam, signal }) => getGuestbooks(client, universityId, pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (last, _pages, cursor, cursors) =>
      last.hasNext && last.nextCursor && last.nextCursor !== cursor && !cursors.includes(last.nextCursor) ? last.nextCursor : undefined,
    enabled: Number.isSafeInteger(universityId) && universityId > 0,
  });
}
export function useSetGuestbookLike(universityId: number, viewerId: number) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  const key = guestbookKeys.list(universityId, viewerId);
  return useMutation({
    mutationFn: ({ id, liked }: { id: number; liked: boolean }) => setGuestbookLike(client, id, liked),
    retry: false,
    onMutate: () => queryClient.cancelQueries({ queryKey: key, exact: true }),
    onSuccess: async (result) => {
      // Also cancel a refresh started while the mutation was pending; an old read must not overwrite this response.
      await queryClient.cancelQueries({ queryKey: key, exact: true });
      queryClient.setQueriesData<InfiniteData<GuestbookPage>>({ queryKey: key, exact: true }, (data) => data && ({
        ...data, pages: data.pages.map((page) => ({ ...page, items: page.items.map((item) => item.id === result.guestbookId
          ? { ...item, likeCount: result.likeCount, likedByMe: result.liked } : item) })),
      }));
      void queryClient.invalidateQueries({ queryKey: guestbookKeys.school(universityId) });
    },
    // Duplicate/missing-like responses and uncertain network results require a fresh personalized read, never replay.
    onError: () => { void queryClient.invalidateQueries({ queryKey: key, exact: true }); },
  });
}
export function useCreateGuestbook(universityId: number) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (content: string) => createGuestbook(client, universityId, content),
    retry: false,
    // All viewers of this school become stale, including the own-school view after another member posts.
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: guestbookKeys.school(universityId) }); },
  });
}
