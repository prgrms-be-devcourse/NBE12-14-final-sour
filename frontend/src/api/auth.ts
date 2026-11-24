// Sign-up and login through the backend. Sign-up (SCRUM-151): email code → verify → sign-up, plus the nickname check.
// Those calls and login are public (SecurityConfig.PUBLIC_POST / PUBLIC_GET), so they go `anonymous`: no token, no
// reissue, and their errors stay with the form. Login, logout and the session restore are SCRUM-152.
// Plan: .agents/01-PLAN/261118-1021-frontend_prototype_migration_plan.md §5 단계 1.

import { useQuery } from '@tanstack/react-query';
import { useApiClient } from './ApiProvider';
import type { ApiClient } from './client';
import { isApiError } from './errors';
import { QUERY_DEFAULTS } from './queryClient';
import { sessionEndReason } from './session';

/** UniversitySummary / DepartmentSummary in the auth responses ({id, name}). */
export interface NamedRef {
  id: number;
  name: string;
}

/** EmailCodeResponse */
export interface EmailCodeResponse {
  university: NamedRef;
  expiresInSeconds: number;
}

/** EmailVerifyResponse. The token goes into the sign-up request; it lives only in memory. */
export interface EmailVerifyResponse {
  signupToken: string;
  university: NamedRef;
  expiresInSeconds: number;
}

/** SignupRequest. The school is not sent: the server takes it from the verified email. */
export interface SignupRequest {
  signupToken: string;
  nickname: string;
  password: string;
  departmentId: number;
}

/** SignupResponse (201). The client logs in next (SCRUM-152). */
export interface SignupResponse {
  userId: number;
  nickname: string;
  university: NamedRef;
  department: NamedRef;
}

/** NicknameAvailabilityResponse */
export interface NicknameAvailability {
  available: boolean;
}

/**
 * Server values the screen shows before the server says them (EmailVerificationService). The code TTL comes in the
 * response; these two do not, so they are copied here and a change on the server must be copied too.
 */
export const SIGNUP_SERVER_RULES = {
  /** EmailVerificationService.COOLDOWN */
  resendCooldownSeconds: 60,
  /** EmailVerificationService.MAX_ATTEMPTS */
  maxCodeAttempts: 5,
} as const;

/** Error codes the sign-up screens handle by name (ErrorCode.java). */
export const SIGNUP_ERROR_CODES = {
  invalidCode: 'AUTH_INVALID_CODE',
  codeExpired: 'AUTH_CODE_EXPIRED',
  attemptsExceeded: 'AUTH_CODE_ATTEMPTS_EXCEEDED',
  cooldown: 'AUTH_CODE_COOLDOWN',
  invalidSignupToken: 'AUTH_INVALID_SIGNUP_TOKEN',
  emailAlreadyRegistered: 'AUTH_EMAIL_ALREADY_REGISTERED',
  nicknameDuplicated: 'USER_NICKNAME_DUPLICATED',
  departmentMismatch: 'USER_DEPARTMENT_MISMATCH',
} as const;

export function requestEmailCode(client: ApiClient, email: string): Promise<EmailCodeResponse> {
  return client.request('/auth/email/code', { method: 'POST', body: { email }, anonymous: true });
}

export function verifyEmailCode(client: ApiClient, email: string, code: string): Promise<EmailVerifyResponse> {
  return client.request('/auth/email/verify', { method: 'POST', body: { email, code }, anonymous: true });
}

export function signup(client: ApiClient, request: SignupRequest): Promise<SignupResponse> {
  return client.request('/auth/signup', { method: 'POST', body: request, anonymous: true });
}

export function checkNicknameAvailability(client: ApiClient, nickname: string): Promise<NicknameAvailability> {
  return client.request('/users/nickname/availability', { query: { nickname }, anonymous: true });
}

// ---------------------------------------------------------------------------------------------------------------
// Login, logout and the session restore (SCRUM-152)
// ---------------------------------------------------------------------------------------------------------------

/** Role.java */
export type UserRole = 'USER' | 'ADMIN';

/** LoginResponse. The refresh token is not in the body: it comes as the HttpOnly `refreshToken` cookie. */
export interface LoginResponse {
  accessToken: string;
  expiresInSeconds: number;
  user: { id: number; nickname: string; universityId: number; role: UserRole };
}

/** POST /auth/login, then signs the session in with the access token (kept in memory only). */
export async function login(client: ApiClient, email: string, password: string): Promise<LoginResponse> {
  const response = await client.request<LoginResponse>('/auth/login', { method: 'POST', body: { email, password }, anonymous: true });
  client.session.signIn(response.accessToken);
  return response;
}

/**
 * POST /auth/logout (needs the token), then signs the session out. The server deletes the refresh token and expires
 * the cookie. When the call fails the session stays: signing out only here would leave the cookie, and the next
 * reload would sign the user back in.
 */
export async function logout(client: ApiClient): Promise<void> {
  await client.request<void>('/auth/logout', { method: 'POST' });
  client.session.signOut();
}

/**
 * Gets the session back after a reload with the refresh cookie (POST /auth/reissue). False when there is nothing to
 * restore (no cookie, used or expired, replaced by another login); other failures, such as no connection, throw.
 */
export async function restoreSession(client: ApiClient): Promise<boolean> {
  try {
    await client.reissue();
    return true;
  } catch (error) {
    if (isApiError(error) && error.kind === 'http' && sessionEndReason(error.code)) return false;
    throw error;
  }
}

export const authKeys = {
  restore: ['auth', 'restore'] as const,
};

/**
 * The session restore, run once per page load: every caller shares one query, so a second component or React's
 * double effect in development sends no second reissue (the refresh token is used once).
 */
export function useSessionRestore() {
  const client = useApiClient();
  return useQuery({
    queryKey: authKeys.restore,
    queryFn: () => restoreSession(client),
    staleTime: Infinity,
    gcTime: Infinity,
    // The refresh token works once. Retry only when a proxy answered for a backend it could not reach, so the
    // request surely never arrived; a lost answer could have used the token already.
    retry: (failureCount, error) => failureCount < QUERY_DEFAULTS.maxRetries
      && isApiError(error) && error.kind === 'network' && error.status !== null,
  });
}

// ---------------------------------------------------------------------------------------------------------------
// Input rules, the same as the backend's, so a wrong value is caught before a request. The server still checks.
// ---------------------------------------------------------------------------------------------------------------

/** EmailVerifyRequest.code */
export const CODE_PATTERN = /^\d{6}$/;

/**
 * @Nickname: 2–12 characters, no space at either end. Length is String.length on both sides (UTF-16 units), so an
 * emoji counts as two. JS `\S` also treats Unicode spaces as spaces, so the client is a little stricter there.
 */
export const NICKNAME_RULE = { min: 2, max: 12, pattern: /^\S(.*\S)?$/ } as const;

/** @Password: 8–64 printable ASCII without spaces, with a letter, a digit and a symbol. */
export const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[^A-Za-z0-9])[\x21-\x7E]{8,64}$/;
export const PASSWORD_RULE_TEXT = '공백 없이 8~64자, 영문·숫자·특수문자를 모두 포함';

/** The problem with a nickname, or null when the server would accept its form. */
export function nicknameProblem(nickname: string): string | null {
  const { length } = nickname;
  if (length < NICKNAME_RULE.min || length > NICKNAME_RULE.max || !NICKNAME_RULE.pattern.test(nickname)) {
    return `닉네임은 앞뒤 공백 없이 ${NICKNAME_RULE.min}~${NICKNAME_RULE.max}자로 입력해 주세요.`;
  }
  return null;
}

export function passwordProblem(password: string, confirm: string): string | null {
  if (!PASSWORD_PATTERN.test(password)) return `비밀번호는 ${PASSWORD_RULE_TEXT}해야 합니다.`;
  if (password !== confirm) return '비밀번호 확인이 일치하지 않습니다.';
  return null;
}
