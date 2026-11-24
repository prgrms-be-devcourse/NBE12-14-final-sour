// Errors from the common API client (SCRUM-147). Plan: .agents/01-PLAN/261118-1021-frontend_prototype_migration_plan.md
// §5 단계 0-3. The backend sends ApiResponse{code, message, data}; ErrorCode.java already writes `message` for users,
// so the screen shows it as is. The table below only replaces messages that read like developer notes and adds
// the client's own codes.

/** One entry of `data` when the backend answers COMMON_INVALID_INPUT (FieldErrorDetail.java). */
export interface FieldErrorDetail {
  field: string;
  reason: string;
}

/**
 * Codes the client makes itself when there is no backend code. The CLIENT_ prefix keeps them apart from the
 * backend's domain prefixes (COMMON_, AUTH_, …); tests check that ErrorCode.java does not use them.
 */
export const CLIENT_ERROR_CODES = {
  /** fetch itself failed (network lost, blocked), or a proxy answered 502–504 without our body (backend down). */
  network: 'CLIENT_NETWORK_ERROR',
  /** The body was not ApiResponse JSON, e.g. a proxy error page. */
  invalidResponse: 'CLIENT_INVALID_RESPONSE',
  /** The test tool's failure injection (§4-2), never sent by the server. */
  injected: 'CLIENT_TEST_INJECTED',
} as const;

/**
 * Messages that replace the server's. Every key is a client code or a code in ErrorCode.java (checked by
 * tests/apiClient.test.ts), so a renamed backend code fails the tests instead of silently falling back.
 */
export const ERROR_MESSAGE_OVERRIDES: Readonly<Record<string, string>> = {
  [CLIENT_ERROR_CODES.network]: '서버에 연결하지 못했습니다. 네트워크를 확인하고 다시 시도해 주세요.',
  [CLIENT_ERROR_CODES.invalidResponse]: '서버 응답을 읽지 못했습니다. 잠시 후 다시 시도해 주세요.',
  [CLIENT_ERROR_CODES.injected]: '[테스트 도구] 주입한 실패입니다. 다시 시도해 주세요.',
  // The server's text names the URL path ("요청한 경로"), which users do not see.
  COMMON_NOT_FOUND: '요청한 정보를 찾을 수 없습니다.',
  ATTACK_ALLY_UNIVERSITY: '같은 연합의 학교는 공격할 수 없어요. 지원을 이용해 주세요.',
  // Only a client bug sends these; the server's text describes HTTP details.
  COMMON_METHOD_NOT_ALLOWED: '요청을 처리하지 못했습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.',
  COMMON_UNSUPPORTED_MEDIA_TYPE: '요청을 처리하지 못했습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.',
};

const SERVER_ERROR_FALLBACK = '서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const REQUEST_ERROR_FALLBACK = '요청을 처리하지 못했습니다. 다시 시도해 주세요.';

/** The text a screen shows: the override, else the server's message, else a fallback by HTTP status. */
export function userMessageFor(code: string, serverMessage: string | null, status: number | null): string {
  const override = ERROR_MESSAGE_OVERRIDES[code];
  if (override) return override;
  if (serverMessage && serverMessage.trim()) return serverMessage;
  return status !== null && status >= 500 ? SERVER_ERROR_FALLBACK : REQUEST_ERROR_FALLBACK;
}

/**
 * - `http`: the server answered with an ApiResponse error.
 * - `network`: no answer (fetch rejected, status null) or a proxy's 502–504 without ApiResponse (status kept).
 * - `invalid-response`: an answer that is not ApiResponse JSON.
 * - `injected`: the test tool's failure injection.
 */
export type ApiErrorKind = 'http' | 'network' | 'invalid-response' | 'injected';

export interface ApiErrorInit {
  kind: ApiErrorKind;
  /** HTTP status, or null when there was no response. */
  status: number | null;
  code: string;
  serverMessage?: string | null;
  fieldErrors?: readonly FieldErrorDetail[];
  cause?: unknown;
}

/** Every failure the client throws, except an aborted request, which rethrows the signal's reason. */
export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number | null;
  readonly code: string;
  readonly serverMessage: string | null;
  readonly fieldErrors: readonly FieldErrorDetail[];

  constructor(init: ApiErrorInit) {
    const serverMessage = init.serverMessage ?? null;
    super(userMessageFor(init.code, serverMessage, init.status), init.cause === undefined ? undefined : { cause: init.cause });
    this.name = 'ApiError';
    this.kind = init.kind;
    this.status = init.status;
    this.code = init.code;
    this.serverMessage = serverMessage;
    this.fieldErrors = init.fieldErrors ?? [];
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

/** `data` of a COMMON_INVALID_INPUT answer as field errors; anything else gives an empty list. */
export function parseFieldErrors(data: unknown): FieldErrorDetail[] {
  if (!Array.isArray(data)) return [];
  return data.filter((item): item is FieldErrorDetail =>
    typeof item === 'object' && item !== null
    && typeof (item as FieldErrorDetail).field === 'string'
    && typeof (item as FieldErrorDetail).reason === 'string');
}

/** Field errors keyed by field for a form, keeping the first reason when a field has several. */
export function fieldErrorMap(error: unknown): Record<string, string> {
  const map: Record<string, string> = {};
  if (!isApiError(error)) return map;
  for (const { field, reason } of error.fieldErrors) {
    if (!(field in map)) map[field] = reason;
  }
  return map;
}

/** The text to show for any thrown value, so screens need not check its type. */
export function errorMessage(error: unknown): string {
  return isApiError(error) ? error.message : REQUEST_ERROR_FALLBACK;
}
