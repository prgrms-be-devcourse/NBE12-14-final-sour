import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { createApiClient } from '../src/api/client';
import { isApiError } from '../src/api/errors';
import { isClickLimitError, requiresClickVerification, verifyClickAbuse } from '../src/api/abuse';

for (const token of ['fresh-widget-token', '']) {
  test(`봇 확인은 인증된 POST와 ${token ? '위젯' : '빈'} 토큰만 보내고 본문 없는 204를 수용한다`, async () => {
    const sent: Array<{ path: string; method?: string; body: unknown; auth: string | null; signal?: AbortSignal | null }> = [];
    const client = createApiClient({ fetch: (async (input, init) => {
      sent.push({ path: String(input), method: init?.method, body: JSON.parse(String(init?.body)), auth: new Headers(init?.headers).get('Authorization'), signal: init?.signal });
      return new Response(null, { status: 204 });
    }) as typeof fetch });
    client.session.signIn('member-a');
    const controller = new AbortController();
    assert.equal(await verifyClickAbuse(client, token, controller.signal), undefined);
    assert.deepEqual(sent, [{ path: '/api/v1/abuse/verification', method: 'POST', body: { turnstileToken: token }, auth: 'Bearer member-a', signal: controller.signal }]);
  });
}

for (const [status, code] of [[403, 'ABUSE_VERIFICATION_FAILED'], [429, 'COMMON_TOO_MANY_REQUESTS']] as const) {
  test(`확인 ${status} ${code}는 자동 재시도하거나 클릭 오류로 오인하지 않는다`, async () => {
    let calls = 0;
    const client = createApiClient({ fetch: (async () => {
      calls++;
      return new Response(JSON.stringify({ code, message: '확인 실패', data: null }), { status });
    }) as typeof fetch });
    client.session.signIn('member-a');
    await assert.rejects(verifyClickAbuse(client, 'one-use-token'), (error: unknown) => {
      assert.ok(isApiError(error));
      assert.equal(error.status, status);
      assert.equal(error.code, code);
      assert.equal(isClickLimitError(error), false);
      assert.equal(requiresClickVerification(error), false);
      return true;
    });
    assert.equal(calls, 1);
  });
}

test('봇 확인 통신 실패는 사용한 토큰을 자동 재전송하지 않는다', async () => {
  let calls = 0;
  const client = createApiClient({ fetch: (async () => { calls++; throw new TypeError('offline'); }) as typeof fetch });
  client.session.signIn('member-a');
  await assert.rejects(verifyClickAbuse(client, 'one-use-token'), (error: unknown) => isApiError(error) && error.kind === 'network');
  assert.equal(calls, 1);
});

test('클릭 복구의 Java 계약 센서: 검증 경로·204·토큰 필드·가드 순서 변경 시 복구 정책을 재검토한다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/', import.meta.url);
  const controller = readFileSync(new URL('abuse/controller/AbuseVerificationController.java', root), 'utf8');
  const fix = 'api/abuse.ts의 확인 계약과 clickBatcher의 403/429 복구 정책을 Java 계약에 맞추세요';
  assert.match(controller, /@RequestMapping\("\/api\/v1\/abuse"\)/, fix);
  assert.match(controller, /@PostMapping\("\/verification"\)/, fix);
  assert.match(controller, /ResponseEntity.noContent\(\).build\(\)/, fix);
  const request = readFileSync(new URL('abuse/dto/AbuseVerificationRequest.java', root), 'utf8');
  assert.match(request, /@Nullable @Size\(max = 2048,[^\n]*String turnstileToken\)/, fix);
  const guard = readFileSync(new URL('click/service/ClickActionGuard.java', root), 'utf8');
  const ordered = ['abuseVerificationService.requireNotFlagged(userId)', 'checkDuplicate(userId, submissionId)', 'actionRateLimiter.check(userId)', 'clickCountLimiter.check(userId, clickCount)', 'return loadBaseScore(userId, clickCount, clickType)'];
  const offsets = ordered.map((part) => guard.indexOf(part));
  assert.ok(offsets.every((offset, index) => offset >= 0 && (index === 0 || offset > offsets[index - 1])), fix);
});
