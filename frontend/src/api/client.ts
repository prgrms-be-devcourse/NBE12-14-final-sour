// The one HTTP client every API call goes through (SCRUM-147, SCRUM-148). Plan:
// .agents/01-PLAN/261118-1021-frontend_prototype_migration_plan.md §5 단계 0-3·0-4. It calls VITE_API_ORIGIN
// when set, otherwise the same origin (Vite proxy), unwraps ApiResponse, turns every failure into ApiError, sends the access
// token and reissues it once when it expires.

import { ApiError, CLIENT_ERROR_CODES, isApiError, parseFieldErrors } from './errors';
import { createApiFaults, type ApiFaults } from './faults';
import { createAuthSession, sessionEndReason, type AuthSession } from './session';
import { API_ORIGIN } from './origin';

/** Every endpoint is under /api/v1 (decision 2026-11-12); paths passed to the client start after it. */
export const API_BASE_PATH = '/api/v1';
/** ApiResponse.SUCCESS_CODE */
export const SUCCESS_CODE = 'SUCCESS';
/** Bad Gateway, Service Unavailable, Gateway Timeout: answered by a proxy in front of the backend. */
const GATEWAY_STATUSES: ReadonlySet<number> = new Set([502, 503, 504]);
/** The access token expired, or its session was lost (AccessTokenAuthenticator): reissue and try once more. */
export const EXPIRED_TOKEN_CODE = 'COMMON_EXPIRED_TOKEN';
/** AuthController.reissue: reads the refreshToken cookie, answers ReissueResponse and a new cookie. */
export const REISSUE_PATH = '/auth/reissue';

export type QueryValue = string | number | boolean | null | undefined;
export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface ApiRequestOptions {
  method?: HttpMethod;
  /** Added as ?key=value; null and undefined values are left out. */
  query?: Readonly<Record<string, QueryValue>>;
  /** FormData goes as multipart (the logo item, ItemController); anything else as JSON. */
  body?: unknown;
  headers?: Readonly<Record<string, string>>;
  signal?: AbortSignal;
  /**
   * Leave the session out: no Authorization header, no reissue, and a session-ending code does not end the session.
   * For the auth endpoints themselves (login, signup, email code), whose errors belong to their form.
   */
  anonymous?: boolean;
}

/** ReissueResponse */
export interface ReissueResponse {
  accessToken: string;
  expiresInSeconds: number;
}

export interface ApiClient {
  /**
   * Resolves to ApiResponse.data. The type is the caller's claim: the body is not validated at run time, so each
   * domain module declares the response type from the backend DTO it calls.
   */
  request<T>(path: string, options?: ApiRequestOptions): Promise<T>;
  get<T>(path: string, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T>;
  /**
   * Gets a new access token with the refresh cookie and signs the session in. Calls made while one is running
   * share it, so the used-once refresh token is sent only once. Resolves to the new token.
   */
  reissue(): Promise<string>;
  /** The test tool's delay and failure injection for this client. */
  readonly faults: ApiFaults;
  readonly session: AuthSession;
}

export interface ApiClientOptions {
  fetch?: typeof fetch;
  baseUrl?: string;
  faults?: ApiFaults;
  session?: AuthSession;
}

interface ApiResponseBody {
  code: string;
  message: string;
  data: unknown;
}

function isApiResponseBody(body: unknown): body is ApiResponseBody {
  return typeof body === 'object' && body !== null
    && typeof (body as ApiResponseBody).code === 'string'
    && typeof (body as ApiResponseBody).message === 'string';
}

export function buildUrl(baseUrl: string, path: string, query?: Readonly<Record<string, QueryValue>>): string {
  if (!path.startsWith('/')) throw new Error(`API 경로는 /로 시작해야 합니다: ${path}`);
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== null && value !== undefined) params.append(key, String(value));
  }
  const search = params.toString();
  return `${baseUrl}${path}${search ? `?${search}` : ''}`;
}

function encodeBody(body: unknown): { body?: BodyInit; contentType?: string } {
  if (body === undefined) return {};
  // The browser sets the multipart boundary itself, so no Content-Type here.
  if (body instanceof FormData) return { body };
  return { body: JSON.stringify(body), contentType: 'application/json' };
}

async function readBody(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/** Waits for a promise shared with other callers; aborting stops this caller's wait, not the shared work. */
function awaitShared<T>(promise: Promise<T>, signal?: AbortSignal): Promise<T> {
  if (!signal) return promise;
  if (signal.aborted) return Promise.reject(signal.reason);
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => reject(signal.reason);
    signal.addEventListener('abort', onAbort, { once: true });
    promise.then(resolve, reject).finally(() => signal.removeEventListener('abort', onAbort));
  });
}

export function createApiClient(options: ApiClientOptions = {}): ApiClient {
  const doFetch = options.fetch ?? ((input, init) => globalThis.fetch(input, init));
  const baseUrl = options.baseUrl ?? `${API_ORIGIN}${API_BASE_PATH}`;
  const faults = options.faults ?? createApiFaults();
  const session = options.session ?? createAuthSession();
  let reissuing: Promise<string> | null = null;

  /** One HTTP exchange: sends `accessToken` when given, returns data or throws ApiError. */
  async function send<T>(url: string, method: HttpMethod, body: unknown, signal: AbortSignal | undefined,
    accessToken: string | null, extraHeaders?: Readonly<Record<string, string>>): Promise<T> {
    const encoded = encodeBody(body);
    const headers: Record<string, string> = { Accept: 'application/json' };
    for (const [name, value] of Object.entries(extraHeaders ?? {})) {
      if (!['accept', 'content-type', 'authorization'].includes(name.toLowerCase())) headers[name] = value;
    }
    if (encoded.contentType) headers['Content-Type'] = encoded.contentType;
    if (accessToken) headers.Authorization = `Bearer ${accessToken}`;

    let response: Response;
    try {
      // Include the refresh cookie even when the API is on another origin of the same site (api.<domain>).
      response = await doFetch(url, { method, headers, body: encoded.body, signal, credentials: 'include' });
    } catch (error) {
      if (signal?.aborted) throw signal.reason;
      throw new ApiError({ kind: 'network', status: null, code: CLIENT_ERROR_CODES.network, cause: error });
    }

    // 204 has no body by definition; the backend always sends ApiResponse otherwise.
    if (response.status === 204) return undefined as T;
    const parsed = await readBody(response);
    if (!isApiResponseBody(parsed)) {
      // A gateway status without our body means the proxy could not reach the backend: the Vite proxy answers an
      // empty 502 when the backend is down (checked 2026-11-18). Tell the user it is a connection problem.
      if (GATEWAY_STATUSES.has(response.status)) {
        throw new ApiError({ kind: 'network', status: response.status, code: CLIENT_ERROR_CODES.network });
      }
      throw new ApiError({ kind: 'invalid-response', status: response.status, code: CLIENT_ERROR_CODES.invalidResponse });
    }
    if (response.ok && parsed.code === SUCCESS_CODE) return parsed.data as T;
    throw new ApiError({
      kind: 'http',
      status: response.status,
      code: parsed.code,
      serverMessage: parsed.message,
      fieldErrors: parseFieldErrors(parsed.data),
    });
  }

  /** Ends a signed-in session when the error says it is over, then rethrows the error. */
  function endSessionIfOver(error: unknown): never {
    if (isApiError(error) && error.kind === 'http' && sessionEndReason(error.code)) session.end(error);
    throw error;
  }

  function reissue(): Promise<string> {
    if (!reissuing) {
      const url = buildUrl(baseUrl, REISSUE_PATH);
      reissuing = send<ReissueResponse>(url, 'POST', undefined, undefined, null)
        .then(({ accessToken }) => {
          session.signIn(accessToken);
          return accessToken;
        }, endSessionIfOver)
        .finally(() => { reissuing = null; });
    }
    return reissuing;
  }

  async function request<T>(path: string, { method = 'GET', query, body, headers, signal, anonymous = false }: ApiRequestOptions = {}): Promise<T> {
    const url = buildUrl(baseUrl, path, query);
    await faults.beforeRequest(signal, { method, path });
    if (anonymous) return send<T>(url, method, body, signal, null, headers);

    const sentToken = session.getAccessToken();
    try {
      return await send<T>(url, method, body, signal, sentToken, headers);
    } catch (error) {
      if (!(isApiError(error) && error.code === EXPIRED_TOKEN_CODE && sentToken)) endSessionIfOver(error);
    }

    // The token expired. If another request already replaced it, use the new one; otherwise reissue (shared).
    const current = session.getAccessToken();
    const nextToken = current && current !== sentToken ? current : await awaitShared(reissue(), signal);
    try {
      return await send<T>(url, method, body, signal, nextToken, headers);
    } catch (error) {
      // Only one retry: a second COMMON_EXPIRED_TOKEN goes to the caller instead of reissuing in a loop.
      return endSessionIfOver(error);
    }
  }

  return {
    request,
    get: (path, getOptions) => request(path, { ...getOptions, method: 'GET' }),
    reissue,
    faults,
    session,
  };
}
