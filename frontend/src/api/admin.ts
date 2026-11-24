// Admin screens (SCRUM-171, plan .agents/01-PLAN/261119-1023-scrum172_admin_entry.md). The server guards every
// /api/v1/admin/** call with hasRole(ADMIN) read from the access token's `role` claim (SecurityConfig, JwtProvider):
// an anonymous call is 401, a member's is 403 COMMON_FORBIDDEN. GET /users/me reads the role from the database, so
// right after a member is made an admin the menu shows while the token still says USER until the next login.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useApiClient } from './ApiProvider';
import type { ApiClient } from './client';
import { isApiError } from './errors';
import { useIsAdmin, useMyInfo, userKeys } from './users';
import type { SupportDetail, SupportStatus } from './supports';

/** ApiAccessDeniedHandler's answer to a signed-in member without the role. */
export const ADMIN_FORBIDDEN_CODE = 'COMMON_FORBIDDEN';

export function isAdminForbidden(error: unknown): boolean {
  return isApiError(error) && error.status === 403 && error.code === ADMIN_FORBIDDEN_CODE;
}

export const adminKeys = {
  /** Admin data belongs to whoever is signed in and is reset on sign-out (ApiSessionWatcher). */
  all: ['admin'] as const,
  /** Everything an answer can change: the pending count, every list page and every detail. */
  supports: (adminId: number) => ['admin', adminId, 'supports'] as const,
  pendingSupports: (adminId: number) => ['admin', adminId, 'supports', 'pending-count'] as const,
  supportList: (adminId: number, filter: AdminSupportFilter, page: number) => ['admin', adminId, 'supports', 'list', filter, page] as const,
  supportDetail: (adminId: number, supportId: number) => ['admin', adminId, 'supports', 'detail', supportId] as const,
  abuseRankings: (adminId: number, page: number) => ['admin', adminId, 'abuse-rankings', page] as const,
};

/** AdminSupportListItem. `recipientEmail` is the decrypted address the answer mail goes to. */
export interface AdminSupportListItem {
  supportId: number;
  title: string;
  recipientEmail: string;
  isMember: boolean;
  status: SupportStatus;
  createdAt: string;
}

/** AdminSupportListResponse, newest first. */
export interface AdminSupportPage {
  items: AdminSupportListItem[];
  page: number;
  size: number;
  totalCount: number;
  hasNext: boolean;
}

/** AdminSupportDetailResponse (SCRUM-173): the member detail plus the recipient and whether a member wrote it. */
export interface AdminSupportDetail extends SupportDetail {
  recipientEmail: string;
  isMember: boolean;
}

/** SupportAnswerRequest: both fields @NotBlank, at most 100 and 5,000 characters. */
export interface SupportAnswerRequest {
  answerTitle: string;
  answerContent: string;
}
export interface SupportAnswerResponse {
  supportId: number;
  status: SupportStatus;
}

/** `ALL` sends no status, which the server reads as every inquiry. */
export type AdminSupportFilter = SupportStatus | 'ALL';
export const ADMIN_SUPPORT_PAGE_SIZE = 20;
export const SUPPORT_ANSWER_LIMITS = { answerTitle: 100, answerContent: 5000 } as const;
/** Another admin answered first (the server's conditional UPDATE lets only one answer through). */
export const SUPPORT_ALREADY_ANSWERED_CODE = 'SUPPORT_ALREADY_ANSWERED';
export const SUPPORT_NOT_FOUND_CODE = 'SUPPORT_NOT_FOUND';

export function getAdminSupports(client: ApiClient, filter: AdminSupportFilter, page: number, signal?: AbortSignal): Promise<AdminSupportPage> {
  const status = filter === 'ALL' ? undefined : filter;
  return client.get('/admin/supports', { query: { status, page, size: ADMIN_SUPPORT_PAGE_SIZE }, signal });
}

export function getAdminSupportDetail(client: ApiClient, supportId: number, signal?: AbortSignal): Promise<AdminSupportDetail> {
  return client.get(`/admin/supports/${supportId}`, { signal });
}

/** Not idempotent, but a repeat is a 409 SUPPORT_ALREADY_ANSWERED rather than a second answer or mail. */
export function answerAdminSupport(client: ApiClient, supportId: number, request: SupportAnswerRequest, signal?: AbortSignal): Promise<SupportAnswerResponse> {
  return client.request(`/admin/supports/${supportId}/answer`, { method: 'POST', body: request, signal });
}

/** The same checks as SupportAnswerRequest's @NotBlank and @Size; null when the form can be sent. */
export function validateSupportAnswer({ answerTitle, answerContent }: SupportAnswerRequest): string | null {
  if (!answerTitle.trim()) return '답변 제목을 입력해 주세요.';
  if (answerTitle.length > SUPPORT_ANSWER_LIMITS.answerTitle) return '답변 제목은 100자 이하로 입력해 주세요.';
  if (!answerContent.trim()) return '답변 내용을 입력해 주세요.';
  if (answerContent.length > SUPPORT_ANSWER_LIMITS.answerContent) return '답변 내용은 5,000자 이하로 입력해 주세요.';
  return null;
}

const PENDING_STATUS: SupportStatus = 'RECEIVED';

/** How many inquiries wait for an answer: one row of GET /admin/supports?status=RECEIVED, read for its totalCount. */
export async function getPendingSupportCount(client: ApiClient, signal?: AbortSignal): Promise<number> {
  const page = await client.get<AdminSupportPage>('/admin/supports', { query: { status: PENDING_STATUS, page: 0, size: 1 }, signal });
  return page.totalCount;
}

/**
 * Any admin call refused with 403 means the token's role disagrees with the profile: read the profile again so a
 * demoted member loses the menu. Returns whether `error` was that refusal.
 */
export function useRecheckRoleOnForbidden(error: unknown): boolean {
  const queryClient = useQueryClient();
  const forbidden = isAdminForbidden(error);
  useEffect(() => {
    if (forbidden) void queryClient.invalidateQueries({ queryKey: userKeys.me, exact: true });
  }, [forbidden, queryClient]);
  return forbidden;
}

/**
 * The admin home's count, which is also its access check: it runs only while the profile says ADMIN. A 403 means
 * the token disagrees with the profile, so the profile is read again; if it now says USER the menu disappears.
 */
export function useAdminPendingSupportCount() {
  const client = useApiClient();
  const isAdmin = useIsAdmin();
  const adminId = useMyInfo().data?.id ?? 0;
  const query = useQuery({
    queryKey: adminKeys.pendingSupports(adminId),
    queryFn: ({ signal }) => getPendingSupportCount(client, signal),
    enabled: isAdmin,
  });
  const forbidden = useRecheckRoleOnForbidden(query.error);
  // Not spread: TanStack Query re-renders only for the result fields a component reads.
  return { query, forbidden };
}

/** One page of the admin inquiry list. Always read fresh: other admins answer too. */
export function useAdminSupports(adminId: number, filter: AdminSupportFilter, page: number) {
  const client = useApiClient();
  return useQuery({
    queryKey: adminKeys.supportList(adminId, filter, page),
    queryFn: ({ signal }) => getAdminSupports(client, filter, page, signal),
    staleTime: 0,
  });
}

export function useAdminSupportDetail(adminId: number, supportId: number) {
  const client = useApiClient();
  return useQuery({
    queryKey: adminKeys.supportDetail(adminId, supportId),
    queryFn: ({ signal }) => getAdminSupportDetail(client, supportId, signal),
    staleTime: 0,
  });
}

/**
 * Answers one inquiry. On success the detail shows the answer at once; afterwards, and after any failure (a 409 means
 * someone else answered, a network error may hide a success), every admin inquiry query is read again.
 */
export function useAnswerAdminSupport(adminId: number, supportId: number) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: SupportAnswerRequest) => answerAdminSupport(client, supportId, request),
    retry: false,
    onSuccess: (result, request) => {
      queryClient.setQueryData<AdminSupportDetail>(adminKeys.supportDetail(adminId, supportId), (detail) => detail && {
        ...detail, status: result.status, answer: { title: request.answerTitle, content: request.answerContent },
      });
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: adminKeys.supports(adminId) }),
  });
}

// Member status and abuse ranking (SCRUM-174, plan .agents/01-PLAN/261119-1054-scrum174_admin_users_abuse.md).

/** What an admin may set (AdminUserStatusRequest). WITHDRAWN is refused with 400: only the member can leave. */
export type AdminUserStatus = 'ACTIVE' | 'SUSPENDED' | 'BANNED';
export interface AdminUserStatusResponse {
  userId: number;
  status: AdminUserStatus;
}
export const USER_NOT_FOUND_CODE = 'USER_NOT_FOUND';

/**
 * Idempotent: the same status again is a 200 doing the same work. Suspending or banning revokes the member's refresh
 * tokens and login session, so their access token is a 401 on the next call and logging in is 403 USER_SUSPENDED.
 */
export function changeUserStatus(client: ApiClient, userId: number, status: AdminUserStatus, signal?: AbortSignal): Promise<AdminUserStatusResponse> {
  return client.request(`/admin/users/${userId}/status`, { method: 'PATCH', body: { status }, signal });
}

/** The member ID typed by an admin, or the reason it cannot be sent. Changing oneself is a 400 on the server too. */
export function parseUserStatusTarget(input: string, adminId: number): { userId: number } | { problem: string } {
  const text = input.trim();
  if (!text) return { problem: '회원 ID를 입력해 주세요.' };
  if (!/^\d+$/.test(text) || !Number.isSafeInteger(Number(text)) || Number(text) < 1) return { problem: '회원 ID는 1 이상의 정수로 입력해 주세요.' };
  const userId = Number(text);
  if (userId === adminId) return { problem: '내 계정의 상태는 바꿀 수 없어요.' };
  return { userId };
}

export function useChangeUserStatus() {
  const client = useApiClient();
  return useMutation({
    mutationFn: ({ userId, status }: { userId: number; status: AdminUserStatus }) => changeUserStatus(client, userId, status),
    retry: false,
  });
}

/** AbuseRankingResponse.AbuseRankingItem. `nickname` is null when the member row is gone. */
export interface AbuseRankingItem {
  rank: number;
  userId: number;
  nickname: string | null;
  abuseCount: number;
}
/** AbuseRankingResponse: the last 3 days (today included, KST), most abuse first. */
export interface AbuseRankingPage {
  content: AbuseRankingItem[];
  page: number;
  size: number;
  totalElements: number;
  hasNext: boolean;
}
export const ABUSE_RANKING_PAGE_SIZE = 20;

export function getAbuseRankings(client: ApiClient, page: number, signal?: AbortSignal): Promise<AbuseRankingPage> {
  return client.get('/admin/abuse-rankings', { query: { page, size: ABUSE_RANKING_PAGE_SIZE }, signal });
}

export function useAbuseRankings(adminId: number, page: number) {
  const client = useApiClient();
  return useQuery({
    queryKey: adminKeys.abuseRankings(adminId, page),
    queryFn: ({ signal }) => getAbuseRankings(client, page, signal),
    staleTime: 0,
  });
}
