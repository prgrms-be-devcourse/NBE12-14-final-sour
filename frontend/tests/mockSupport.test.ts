import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  judgeGuestSupport,
  MOCK_SUPPORT_RULES,
  normalizeSupportForm,
  validateSupportForm,
} from '../src/utils/mockSupport';

// Guest inquiry rules of the mock server (SCRUM-122). They mirror the backend: SupportBotCheck (verify-from 3)
// runs before SupportRateLimiter (5 per IP per hour), plan .agents/01-PLAN/261117-0848-turnstile_bot_verification_plan.md §3-3.

test('the mock rules match the backend defaults', () => {
  assert.deepEqual(MOCK_SUPPORT_RULES, { verifyFrom: 3, maxPerWindow: 5 });
});

test('the first two inquiries need no token', () => {
  assert.equal(judgeGuestSupport(0, undefined), 'accepted');
  assert.equal(judgeGuestSupport(1, undefined), 'accepted');
});

test('the third to fifth inquiries need the token field', () => {
  for (const sent of [2, 3, 4]) {
    assert.equal(judgeGuestSupport(sent, undefined), 'verification-required', `sent=${sent}`);
    assert.equal(judgeGuestSupport(sent, 'XXXX.DUMMY.TOKEN.XXXX'), 'accepted', `sent=${sent}`);
  }
});

test('an empty token means the widget did not load and is still sent for checking', () => {
  // The mock server accepts it, as the real server asks Cloudflare and lets it through only when Cloudflare is down.
  assert.equal(judgeGuestSupport(2, ''), 'accepted');
});

test('the sixth inquiry is refused by the rate limit, token or not', () => {
  assert.equal(judgeGuestSupport(5, undefined), 'too-many');
  assert.equal(judgeGuestSupport(5, 'XXXX.DUMMY.TOKEN.XXXX'), 'too-many');
});

test('other rules can be passed', () => {
  assert.equal(judgeGuestSupport(0, undefined, { verifyFrom: 1, maxPerWindow: 2 }), 'verification-required');
  assert.equal(judgeGuestSupport(2, 'token', { verifyFrom: 1, maxPerWindow: 2 }), 'too-many');
});

const valid = { recipientEmail: 'guest@example.com', title: '결제 문의', content: '결제가 두 번 됐어요.' };

test('a filled form is valid', () => {
  assert.equal(validateSupportForm(valid), null);
});

test('the form follows the server limits of SupportCreateRequest', () => {
  assert.match(validateSupportForm({ ...valid, recipientEmail: '  ' }) ?? '', /받을 이메일을 입력/);
  assert.match(validateSupportForm({ ...valid, recipientEmail: 'guest.example.com' }) ?? '', /형식/);
  assert.match(validateSupportForm({ ...valid, recipientEmail: `${'a'.repeat(250)}@b.cd` }) ?? '', /254자/);
  assert.match(validateSupportForm({ ...valid, title: ' ' }) ?? '', /제목을 입력/);
  assert.match(validateSupportForm({ ...valid, title: 'a'.repeat(101) }) ?? '', /100자/);
  assert.equal(validateSupportForm({ ...valid, title: 'a'.repeat(100) }), null);
  assert.match(validateSupportForm({ ...valid, content: '\n' }) ?? '', /본문을 입력/);
  assert.match(validateSupportForm({ ...valid, content: 'a'.repeat(5001) }) ?? '', /5,000자/);
});

test('the email is trimmed before checking and sending, as the server strips it', () => {
  assert.equal(validateSupportForm({ ...valid, recipientEmail: '  guest@example.com  ' }), null);
  assert.deepEqual(normalizeSupportForm({ ...valid, recipientEmail: '  guest@example.com  ' }), valid);
});
