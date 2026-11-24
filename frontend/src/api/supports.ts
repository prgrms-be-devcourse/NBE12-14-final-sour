// Guest inquiry, POST /api/v1/supports (SupportController.create, SCRUM-164). Sent without a token: a token that the
// server cannot accept is a 401 there, and this form is the guest one. Order on the server: SupportBotCheck (from the
// third inquiry per IP in an hour, when Turnstile is on) before SupportRateLimiter (5 per IP per hour, then 429).

import type { ApiClient } from './client';
import { isApiError } from './errors';

/** SupportCreateRequest without the member-only attachment. */
export interface GuestSupportRequest {
  recipientEmail: string;
  title: string;
  content: string;
  /** Undefined sends no field (the server then asks for a check); '' means the widget did not load (plan P6). */
  turnstileToken?: string;
}

/** SupportCreateResponse. */
export type SupportStatus = 'RECEIVED' | 'ANSWERED';
export interface SupportCreated {
  supportId: number;
  status: SupportStatus;
}
export type MemberSupportRequest = Pick<GuestSupportRequest, 'recipientEmail' | 'title' | 'content'>;
export interface SupportListItem {
  supportId: number;
  title: string;
  status: SupportStatus;
  createdAt: string;
}
export interface SupportListResponse {
  items: SupportListItem[];
  page: number;
  size: number;
  totalCount: number;
  hasNext: boolean;
}
export interface SupportDetail extends SupportListItem {
  content: string;
  attachFileUrl: string | null;
  answer: { title: string; content: string } | null;
}
export const supportKeys = {
  all: ['supports', 'member'] as const,
  lists: (userId: number) => ['supports', 'member', userId, 'list'] as const,
  list: (userId: number, page: number) => ['supports', 'member', userId, 'list', page] as const,
  detail: (userId: number, supportId: number) => ['supports', 'member', userId, 'detail', supportId] as const,
};
/** Never fall back to the guest endpoint if a member's session has ended. No upload contract exists yet. */
export function createMemberSupport(client: ApiClient, request: MemberSupportRequest, signal?: AbortSignal): Promise<SupportCreated> {
  if (client.session.getState().status !== 'signed-in') return Promise.reject(new Error('로그인이 필요합니다.'));
  const { recipientEmail, title, content } = request;
  return client.request('/supports', { method: 'POST', body: { recipientEmail: recipientEmail.trim(), title, content }, signal });
}
export function getMySupports(client: ApiClient, page = 0, signal?: AbortSignal): Promise<SupportListResponse> {
  return client.get('/supports/me', { query: { page, size: 20 }, signal });
}
export function getSupportDetail(client: ApiClient, supportId: number, signal?: AbortSignal): Promise<SupportDetail> {
  return client.get(`/supports/${supportId}`, { signal });
}
/** Do not turn a server-supplied attachment into an executable URL or embed arbitrary content. */
export function safeSupportAttachment(url: string | null): string | null {
  if (!url) return null;
  try {
    const parsed = new URL(url);
    return (parsed.protocol === 'https:' || parsed.protocol === 'http:') && !parsed.username && !parsed.password ? parsed.href : null;
  } catch { return null; }
}

export function createGuestSupport(client: ApiClient, request: GuestSupportRequest, signal?: AbortSignal): Promise<SupportCreated> {
  const { turnstileToken, ...form } = request;
  const body = turnstileToken === undefined ? form : { ...form, turnstileToken };
  return client.request<SupportCreated>('/supports', { method: 'POST', body, signal, anonymous: true });
}

export type GuestSupportError = 'verification-required' | 'verification-failed' | 'too-many' | 'other';

export function classifyGuestSupportError(error: unknown): GuestSupportError {
  if (!isApiError(error)) return 'other';
  if (error.status === 403 && error.code === 'ABUSE_VERIFICATION_REQUIRED') return 'verification-required';
  if (error.status === 403 && error.code === 'ABUSE_VERIFICATION_FAILED') return 'verification-failed';
  if (error.status === 429) return 'too-many';
  return 'other';
}
