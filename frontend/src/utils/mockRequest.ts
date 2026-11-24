// Mock request layer (M5-B, docs/261108-2051-m5_failure_retry_click_guard_plan.md D3).
// A fixed demo mode decides how the next requests end; nothing is random. A request changes state only when
// it succeeds, so a failed one can be retried without being applied twice. This is not server idempotency.

export type MockRequestMode = 'normal' | 'delay' | 'fail-once' | 'fail';

export const MOCK_REQUEST_MODES: readonly { mode: MockRequestMode; label: string }[] = [
  { mode: 'normal', label: '정상' },
  { mode: 'delay', label: '지연 1.5초' },
  { mode: 'fail-once', label: '1회 실패' },
  { mode: 'fail', label: '계속 실패' },
];

/** Mock values only. Failures also wait a moment so the pending state can be seen. */
export const MOCK_REQUEST_RULES = { delayMs: 1500, failDelayMs: 600 };

/** How long a request in this mode waits before it settles. 0 means it settles at once. */
export function requestWaitMs(mode: MockRequestMode, rules = MOCK_REQUEST_RULES): number {
  if (mode === 'normal') return 0;
  return mode === 'delay' ? rules.delayMs : rules.failDelayMs;
}

/**
 * How a request settles in the mode current at that moment. 'fail-once' fails one request and then
 * returns to normal, so the retry succeeds.
 */
export function settleRequest(mode: MockRequestMode): { ok: boolean; nextMode: MockRequestMode } {
  if (mode === 'fail') return { ok: false, nextMode: mode };
  if (mode === 'fail-once') return { ok: false, nextMode: 'normal' };
  return { ok: true, nextMode: mode };
}

export const REQUEST_FAILED_MESSAGE = '[시연] 네트워크 오류로 요청이 실패했습니다. 입력은 그대로 있어요. 다시 시도해 주세요.';
