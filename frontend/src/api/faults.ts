// Delay and failure injection for the admin test tool (SCRUM-147, SCRUM-175, plan §4-2). It lives in the client, so it changes only
// this browser's requests and never reaches the server. Modes and timings are the mock request layer's
// (utils/mockRequest.ts), so the test tool works the same in api and mock mode.

import { MOCK_REQUEST_RULES, requestWaitMs, settleRequest, type MockRequestMode } from '../utils/mockRequest';
import { ApiError, CLIENT_ERROR_CODES } from './errors';

export type ApiFaultMode = MockRequestMode;

/** What a request is about to call, so a rule can single out one endpoint. */
export interface FaultTarget {
  method: string;
  path: string;
}

/** The click endpoint the click-refusal check answers in place of the server (SCRUM-175). */
export const CLICK_REFUSAL_TARGET: FaultTarget = { method: 'POST', path: '/clicks' };
/** The server's own answer to too many click submissions (ErrorCode.CLICK_TOO_FREQUENT, 429). */
export const CLICK_REFUSAL = { status: 429, code: 'CLICK_TOO_FREQUENT', message: '제출 요청이 너무 많습니다.' } as const;

export interface ApiFaults {
  getMode(): ApiFaultMode;
  setMode(mode: ApiFaultMode): void;
  /** While on, `POST /clicks` ends with the server's 429 CLICK_TOO_FREQUENT without reaching the server. */
  getClickRefusal(): boolean;
  setClickRefusal(on: boolean): void;
  /** Back to normal requests and no click refusal. */
  reset(): void;
  /** Calls the listener after every change, including 'fail-once' returning to normal. */
  subscribe(listener: () => void): () => void;
  /**
   * Runs before each request: waits as the mode says, then throws an `injected` ApiError if the request fails.
   * The outcome is decided when the request starts, so with 'fail-once' only the first of several requests fails.
   * A click submission is then refused while click refusal is on.
   */
  beforeRequest(signal?: AbortSignal, target?: FaultTarget): Promise<void>;
}

export type Sleep = (ms: number, signal?: AbortSignal) => Promise<void>;

/** setTimeout that rejects with the signal's reason when aborted, so a cancelled query stops waiting. */
export const sleep: Sleep = (ms, signal) => new Promise((resolve, reject) => {
  if (signal?.aborted) {
    reject(signal.reason);
    return;
  }
  const onAbort = () => {
    clearTimeout(timer);
    reject(signal!.reason);
  };
  const timer = setTimeout(() => {
    signal?.removeEventListener('abort', onAbort);
    resolve();
  }, ms);
  signal?.addEventListener('abort', onAbort, { once: true });
});

export function createApiFaults(rules = MOCK_REQUEST_RULES, wait: Sleep = sleep): ApiFaults {
  let mode: ApiFaultMode = 'normal';
  let clickRefusal = false;
  const listeners = new Set<() => void>();
  const notify = () => { for (const listener of [...listeners]) listener(); };
  const setMode = (next: ApiFaultMode) => {
    if (next === mode) return;
    mode = next;
    notify();
  };
  const setClickRefusal = (on: boolean) => {
    if (on === clickRefusal) return;
    clickRefusal = on;
    notify();
  };
  return {
    getMode: () => mode,
    setMode,
    getClickRefusal: () => clickRefusal,
    setClickRefusal,
    reset() {
      if (mode === 'normal' && !clickRefusal) return;
      mode = 'normal';
      clickRefusal = false;
      notify();
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    async beforeRequest(signal, target) {
      const waitMs = requestWaitMs(mode, rules);
      const { ok, nextMode } = settleRequest(mode);
      setMode(nextMode);
      if (waitMs > 0) await wait(waitMs, signal);
      if (!ok) throw new ApiError({ kind: 'injected', status: null, code: CLIENT_ERROR_CODES.injected });
      if (clickRefusal && target?.method === CLICK_REFUSAL_TARGET.method && target.path === CLICK_REFUSAL_TARGET.path) {
        throw new ApiError({ kind: 'http', status: CLICK_REFUSAL.status, code: CLICK_REFUSAL.code, serverMessage: CLICK_REFUSAL.message });
      }
    },
  };
}
