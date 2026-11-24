// Mock email verification rules (M4-A, docs/261108-2020-m4_onboarding_auth_plan.md 4-1).
// Pure functions: time and the new code are arguments, so tests fix them. This reproduces states on screen;
// it is not the service's server check (TTL, code storage, rate limits and identity checks stay server work).
import type { SchoolId } from '../data/schoolIds.ts';
import { getSchoolDepartments } from '../data/gameRules.ts';
import { resolveEmailDomain } from '../data/mockEmailDomains.ts';

/** Mock values (decision D4). Not service policy. */
export const MOCK_AUTH_RULES = {
  codeTtlMs: 5 * 60_000,
  maxAttempts: 5,
  resendCooldownMs: 60_000,
  nicknameMaxLength: 10,
} as const;

export type AuthStep = 'email' | 'code' | 'profile' | 'done';

export interface CodeChallenge {
  code: string;
  issuedAt: number;
  expiresAt: number;
  /** Wrong codes entered for this code. A new code starts again from 0. */
  attempts: number;
  used: boolean;
}

export interface Verification {
  email: string;
  schoolId: SchoolId;
  verifiedAt: number;
}

export interface EnrolledProfile {
  email: string;
  schoolId: SchoolId;
  departmentId: string;
  department: string;
  nickname: string;
}

export interface MockAuthState {
  step: AuthStep;
  /** Normalized email the current code was sent to. */
  email: string;
  schoolId: SchoolId | null;
  challenge: CodeChallenge | null;
  verification: Verification | null;
  completed: EnrolledProfile | null;
  /**
   * Earliest time a new code may be issued, per normalized email. Kept across email edits and restarts,
   * so editing the email and asking again cannot skip the resend wait (completion criterion 2, 4).
   */
  nextCodeAt: Readonly<Record<string, number>>;
}

export const INITIAL_AUTH_STATE: MockAuthState = {
  step: 'email',
  email: '',
  schoolId: null,
  challenge: null,
  verification: null,
  completed: null,
  nextCodeAt: {},
};

export type AuthError =
  | 'wrong-step'
  | 'invalid-email'
  | 'unsupported-domain'
  | 'ambiguous-domain'
  | 'cooldown'
  | 'no-code'
  | 'incomplete-code'
  | 'already-used'
  | 'expired'
  | 'locked'
  | 'wrong-code'
  | 'not-verified'
  | 'invalid-nickname'
  | 'invalid-department'
  | 'already-completed';

export type AuthResult =
  | { ok: true; state: MockAuthState }
  | { ok: false; error: AuthError; state: MockAuthState };

const fail = (state: MockAuthState, error: AuthError): AuthResult => ({ ok: false, error, state });

export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function randomAuthCode(): string {
  return String(Math.floor(Math.random() * 1_000_000)).padStart(6, '0');
}

/** A code, or a function that makes one. The function is called only when a code is actually issued. */
export type CodeSource = string | (() => string);

function issue(state: MockAuthState, email: string, schoolId: SchoolId, now: number, source: CodeSource): MockAuthState {
  const code = typeof source === 'function' ? source() : source;
  return {
    ...state,
    step: 'code',
    email,
    schoolId,
    challenge: { code, issuedAt: now, expiresAt: now + MOCK_AUTH_RULES.codeTtlMs, attempts: 0, used: false },
    verification: null,
    completed: null,
    nextCodeAt: { ...state.nextCodeAt, [email]: now + MOCK_AUTH_RULES.resendCooldownMs },
  };
}

/** Seconds until a new code may be issued for this email (0 when allowed). */
export function cooldownSeconds(state: MockAuthState, email: string, now: number): number {
  const at = state.nextCodeAt[normalizeEmail(email)] ?? 0;
  return Math.max(0, Math.ceil((at - now) / 1000));
}

export function remainingSeconds(state: MockAuthState, now: number): number {
  if (!state.challenge) return 0;
  return Math.max(0, Math.ceil((state.challenge.expiresAt - now) / 1000));
}

export function attemptsLeft(state: MockAuthState): number {
  return state.challenge ? Math.max(0, MOCK_AUTH_RULES.maxAttempts - state.challenge.attempts) : 0;
}

/** Email step: the school comes from the domain, never from a user choice (plan scenario 1 ③). */
export function requestCode(state: MockAuthState, rawEmail: string, now: number, code: CodeSource): AuthResult {
  if (state.step !== 'email') return fail(state, 'wrong-step');
  const email = normalizeEmail(rawEmail);
  if (!EMAIL_PATTERN.test(email)) return fail(state, 'invalid-email');
  const resolution = resolveEmailDomain(email);
  if (resolution.kind === 'unsupported') return fail(state, 'unsupported-domain');
  if (resolution.kind === 'ambiguous') return fail(state, 'ambiguous-domain');
  if (cooldownSeconds(state, email, now) > 0) return fail(state, 'cooldown');
  return { ok: true, state: issue(state, email, resolution.schoolId, now, code) };
}

/** A new code replaces the old one, which stops working. Attempts are counted again for the new code. */
export function resendCode(state: MockAuthState, now: number, code: CodeSource): AuthResult {
  if (state.step !== 'code' || !state.challenge || !state.schoolId) return fail(state, 'no-code');
  if (cooldownSeconds(state, state.email, now) > 0) return fail(state, 'cooldown');
  return { ok: true, state: issue(state, state.email, state.schoolId, now, code) };
}

export function verifyCode(state: MockAuthState, rawCode: string, now: number): AuthResult {
  const challenge = state.challenge;
  if (!challenge || !state.schoolId) return fail(state, 'no-code');
  if (challenge.used) return fail(state, 'already-used');
  if (now >= challenge.expiresAt) return fail(state, 'expired');
  if (challenge.attempts >= MOCK_AUTH_RULES.maxAttempts) return fail(state, 'locked');
  const code = rawCode.trim();
  if (!/^\d{6}$/.test(code)) return fail(state, 'incomplete-code');
  if (code !== challenge.code) {
    const next = { ...state, challenge: { ...challenge, attempts: challenge.attempts + 1 } };
    return fail(next, next.challenge.attempts >= MOCK_AUTH_RULES.maxAttempts ? 'locked' : 'wrong-code');
  }
  return {
    ok: true,
    state: {
      ...state,
      step: 'profile',
      challenge: { ...challenge, used: true },
      verification: { email: state.email, schoolId: state.schoolId, verifiedAt: now },
    },
  };
}

/** Back to the email step. The code and any verification are dropped and cannot be reused. */
export function editEmail(state: MockAuthState): MockAuthState {
  if (state.step === 'done') return state;
  return { ...INITIAL_AUTH_STATE, email: state.email, nextCodeAt: state.nextCodeAt };
}

/** Start the onboarding again after it was completed (demo re-run). Resend waits are kept. */
export function restartAuth(state: MockAuthState): MockAuthState {
  return { ...INITIAL_AUTH_STATE, nextCodeAt: state.nextCodeAt };
}

export function completeProfile(state: MockAuthState, input: { nickname: string; departmentId: string }): AuthResult {
  if (state.completed) return fail(state, 'already-completed');
  const verification = state.verification;
  // Plan 6-2 step 6: the sign-up email must equal the verified one.
  if (state.step !== 'profile' || !verification || verification.email !== state.email) return fail(state, 'not-verified');
  const nickname = input.nickname.trim();
  if (nickname.length < 1 || nickname.length > MOCK_AUTH_RULES.nicknameMaxLength) return fail(state, 'invalid-nickname');
  const department = getSchoolDepartments(verification.schoolId).find((entry) => entry.id === input.departmentId);
  if (!department) return fail(state, 'invalid-department');
  const completed: EnrolledProfile = {
    email: verification.email,
    schoolId: verification.schoolId,
    departmentId: department.id,
    department: department.name,
    nickname,
  };
  return { ok: true, state: { ...state, step: 'done', completed } };
}

/** Demo control (D4): end the current code's validity now instead of waiting five minutes. */
export function expireCodeForDemo(state: MockAuthState, now: number): MockAuthState {
  if (!state.challenge || state.challenge.used) return state;
  return { ...state, challenge: { ...state.challenge, expiresAt: Math.min(state.challenge.expiresAt, now) } };
}

export function describeAuthError(error: AuthError, state: MockAuthState, now: number): string {
  switch (error) {
    case 'wrong-step': return '지금 단계에서는 할 수 없는 동작입니다.';
    case 'invalid-email': return '이메일 형식이 올바르지 않습니다.';
    case 'unsupported-domain': return '지원하지 않는 학교 이메일 도메인입니다. (목업 지원: hanyang.ac.kr · kaist.ac.kr · pusan.ac.kr)';
    case 'ambiguous-domain': return '여러 학교가 함께 쓰는 도메인이라 소속 학교를 확정할 수 없습니다.';
    case 'cooldown': return `${cooldownSeconds(state, state.email, now)}초 후에 코드를 다시 받을 수 있습니다.`;
    case 'no-code': return '발급된 코드가 없습니다. 이메일부터 입력해 주세요.';
    case 'incomplete-code': return '6자리 숫자를 모두 입력해 주세요.';
    case 'already-used': return '이미 사용한 코드입니다.';
    case 'expired': return '코드 유효 시간이 지났습니다. 코드를 다시 받아 주세요.';
    case 'locked': return '시도 횟수를 모두 사용했습니다. 코드를 다시 받아 주세요.';
    case 'wrong-code': return `코드가 일치하지 않습니다. 남은 시도 ${attemptsLeft(state)}회`;
    case 'not-verified': return '이메일 인증을 먼저 완료해 주세요.';
    case 'invalid-nickname': return `닉네임은 공백을 빼고 1~${MOCK_AUTH_RULES.nicknameMaxLength}자로 입력해 주세요.`;
    case 'invalid-department': return '학과를 선택해 주세요.';
    case 'already-completed': return '이미 입학 처리가 끝났습니다.';
  }
}
