// Mock click checks (M5-A, docs/261108-2051-m5_failure_retry_click_guard_plan.md 4 M5-A, D1·D2).
// Planning doc 10장 #4 asks the server to check click counts and intervals. The mockup judges each click
// right away in the browser; this is a demo of the rejected path, not abuse protection.

export interface ClickGuardRules {
  /** Window for the count check. */
  windowMs: number;
  /** More accepted clicks than this inside one window is rejected. */
  maxPerWindow: number;
  /** A click closer than this to the last accepted one is rejected. */
  minIntervalMs: number;
  /** After a rejection every click is refused for this long, then clicks count again. */
  blockMs: number;
}

/** Mock values only (D2), not a service policy. */
export const MOCK_CLICK_RULES: ClickGuardRules = {
  windowMs: 1000,
  maxPerWindow: 20,
  minIntervalMs: 30,
  blockMs: 3000,
};

export type ClickRejectReason = 'rate' | 'interval' | 'blocked' | 'forced';

export interface ClickGuardState {
  /** Times of accepted clicks inside the current window, oldest first. */
  recent: readonly number[];
  blockedUntil: number;
}

export const INITIAL_CLICK_GUARD: ClickGuardState = { recent: [], blockedUntil: 0 };

export type ClickJudgement =
  | { accepted: true; state: ClickGuardState }
  | { accepted: false; reason: ClickRejectReason; state: ClickGuardState };

/**
 * Judge one click. `forced` is the demo switch for a server rejection: the click is refused and nothing
 * else changes, so turning the switch off returns to normal at once.
 */
export function judgeClick(state: ClickGuardState, now: number, rules: ClickGuardRules = MOCK_CLICK_RULES, forced = false): ClickJudgement {
  if (forced) return { accepted: false, reason: 'forced', state };
  if (now < state.blockedUntil) return { accepted: false, reason: 'blocked', state };
  const recent = state.recent.filter((time) => time > now - rules.windowMs);
  const last = recent[recent.length - 1];
  const reject = (reason: ClickRejectReason): ClickJudgement => ({
    accepted: false,
    reason,
    state: { recent: [], blockedUntil: now + rules.blockMs },
  });
  if (last !== undefined && now - last < rules.minIntervalMs) return reject('interval');
  if (recent.length + 1 > rules.maxPerWindow) return reject('rate');
  return { accepted: true, state: { recent: [...recent, now], blockedUntil: 0 } };
}

export function blockedSeconds(state: ClickGuardState, now: number): number {
  return Math.max(0, Math.ceil((state.blockedUntil - now) / 1000));
}

export function describeClickRejection(reason: ClickRejectReason, seconds: number): string {
  if (reason === 'forced') return '[시연] 서버가 클릭을 거절했습니다. 점수에 반영하지 않았어요. 시연 토글을 끄면 정상으로 돌아옵니다.';
  const cause = reason === 'interval' ? '클릭 간격이 너무 짧아' : reason === 'rate' ? '1초에 너무 많이 클릭해' : '잠시 클릭이 제한돼';
  return `${cause} 반영하지 않았습니다. ${seconds}초 후 다시 클릭할 수 있어요. (목업 판정)`;
}

// ---------------------------------------------------------------------------------------------------------
// Keyboard: a key click counts only on a full press and release (user instruction 2026-11-08).
// Holding the key fires repeated keydown events; those never count, and the release of a held key counts once.
// ---------------------------------------------------------------------------------------------------------

export const CLICK_KEYS: readonly string[] = ['Enter', ' '];

export interface KeyPressGate {
  /** The key that went down first and is still held, or null. */
  heldKey: string | null;
}

export const INITIAL_KEY_GATE: KeyPressGate = { heldKey: null };

/** keydown: arms the gate on a fresh press. Repeats and a second key while one is held do nothing. */
export function keyPressDown(gate: KeyPressGate, key: string, repeat: boolean): KeyPressGate {
  if (!CLICK_KEYS.includes(key) || repeat || gate.heldKey !== null) return gate;
  return { heldKey: key };
}

/** keyup: the release of the armed key is one click. */
export function keyPressUp(gate: KeyPressGate, key: string): { gate: KeyPressGate; click: boolean } {
  if (gate.heldKey === null || gate.heldKey !== key) return { gate, click: false };
  return { gate: INITIAL_KEY_GATE, click: true };
}
