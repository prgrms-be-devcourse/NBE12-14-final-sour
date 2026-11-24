// Guest inquiry of the mock server (SCRUM-122). The real endpoint is POST /api/v1/supports; this follows its order:
// SupportBotCheck (from the third inquiry in an hour, a token is required) runs before SupportRateLimiter
// (5 per IP per hour, the sixth is 429). Plan .agents/01-PLAN/261117-0848-turnstile_bot_verification_plan.md §3-3.
// [목업] The mock counts this page's inquiries instead of an IP, has no one-hour window (the demo resets it) and
// accepts any token, as only the server can ask Cloudflare.

export interface SupportRules {
  /** The nth inquiry in the window from which a token is required (support.bot-check.verify-from). */
  verifyFrom: number;
  /** Inquiries allowed in the window (support.rate-limit.max-requests). */
  maxPerWindow: number;
}

export const MOCK_SUPPORT_RULES: SupportRules = { verifyFrom: 3, maxPerWindow: 5 };

/** accepted 201, verification-required 403 ABUSE_VERIFICATION_REQUIRED, too-many 429 COMMON_TOO_MANY_REQUESTS. */
export type GuestSupportVerdict = 'accepted' | 'verification-required' | 'too-many';

/**
 * How the server answers a guest inquiry after `sentCount` accepted ones. `token` undefined means the request has no
 * `turnstileToken` field; an empty string means the widget did not load and is still sent for checking (plan P6).
 */
export function judgeGuestSupport(sentCount: number, token: string | undefined, rules: SupportRules = MOCK_SUPPORT_RULES): GuestSupportVerdict {
  // The server skips the bot check once the limit is used up; the rate limit then refuses the request.
  if (sentCount >= rules.maxPerWindow) return 'too-many';
  if (sentCount + 1 >= rules.verifyFrom && token === undefined) return 'verification-required';
  return 'accepted';
}

export interface SupportForm {
  recipientEmail: string;
  title: string;
  content: string;
}

/** Limits of SupportCreateRequest (@NotBlank, @Email, @Size). */
export const SUPPORT_FORM_LIMITS = { recipientEmail: 254, title: 100, content: 5000 } as const;

/** The server strips the email before checking it; title and content are sent as typed. */
export function normalizeSupportForm(form: SupportForm): SupportForm {
  return { ...form, recipientEmail: form.recipientEmail.trim() };
}

/** The first problem with the form, or null. Checked before sending so the server's 400 is not the usual answer. */
export function validateSupportForm(form: SupportForm): string | null {
  const email = form.recipientEmail.trim();
  if (email === '') return '받을 이메일을 입력해 주세요.';
  if (email.length > SUPPORT_FORM_LIMITS.recipientEmail) return '받을 이메일은 254자 이하로 입력해 주세요.';
  if (!/^[^\s@]+@[^\s@]+$/.test(email)) return '받을 이메일 형식이 올바르지 않아요.';
  if (form.title.trim() === '') return '제목을 입력해 주세요.';
  if (form.title.length > SUPPORT_FORM_LIMITS.title) return '제목은 100자 이하로 입력해 주세요.';
  if (form.content.trim() === '') return '본문을 입력해 주세요.';
  if (form.content.length > SUPPORT_FORM_LIMITS.content) return '본문은 5,000자 이하로 입력해 주세요.';
  return null;
}

export const SUPPORT_MESSAGES = {
  accepted: '문의가 접수되었어요. 답변은 받을 이메일로 보내 드려요.',
  verificationRequired: '같은 곳에서 문의가 이어져 자동 입력 방지 확인이 필요해요. 확인을 마치면 같은 내용을 바로 다시 보내요.',
  tooMany: '같은 곳에서 1시간에 5건까지 문의할 수 있어요. 잠시 후 다시 시도해 주세요.',
  network: '[시연] 네트워크 오류로 문의를 보내지 못했습니다. 다시 시도해 주세요.',
} as const;
