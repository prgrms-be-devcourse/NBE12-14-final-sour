import type { ApiClient } from './client';
import { isApiError } from './errors';

/** AbuseVerificationRequest; a script load failure sends an empty token for the server to judge. */
export interface AbuseVerificationRequest {
  turnstileToken: string;
}

/** Authenticated, 204 with no body. A Turnstile token is single-use: never automatically retry this call. */
export function verifyClickAbuse(client: ApiClient, turnstileToken: string, signal?: AbortSignal): Promise<void> {
  return client.request<void>('/abuse/verification', { method: 'POST', body: { turnstileToken }, signal });
}

export function requiresClickVerification(error: unknown): boolean {
  return isApiError(error) && error.status === 403 && error.code === 'ABUSE_VERIFICATION_REQUIRED';
}

export function isClickLimitError(error: unknown): boolean {
  return isApiError(error) && error.status === 429
    && (error.code === 'CLICK_TOO_FREQUENT' || error.code === 'CLICK_COUNT_EXCEEDED');
}
