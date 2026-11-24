// TanStack Query defaults (SCRUM-147, plan decision D-4).

import { QueryClient } from '@tanstack/react-query';
import { isApiError } from './errors';

export const QUERY_DEFAULTS = {
  /** Retries after the first failure, so a query tries 3 times at most (TanStack's own default is 3 retries). */
  maxRetries: 2,
  /**
   * How long fetched data counts as fresh. TanStack's default 0 refetches on every mount; 10 s keeps tab switches
   * from refetching. Screens with live numbers (rankings) set their own value.
   */
  staleTimeMs: 10_000,
} as const;

/**
 * Retry only what may succeed by itself: no response, an unreadable response or a 5xx. A 4xx answer (bad input,
 * no permission, rate limit) comes back the same, and an injected failure must stay visible so the test tool can
 * show the failure screen.
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= QUERY_DEFAULTS.maxRetries) return false;
  if (!isApiError(error)) return true;
  if (error.kind === 'injected') return false;
  return error.status === null || error.status >= 500;
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: shouldRetryQuery, staleTime: QUERY_DEFAULTS.staleTimeMs },
      // Changes (clicks, purchases) are not idempotent; a failed one is retried by the user, not by the library.
      mutations: { retry: false },
    },
  });
}
