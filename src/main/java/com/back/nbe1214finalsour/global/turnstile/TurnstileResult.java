package com.back.nbe1214finalsour.global.turnstile;

/**
 * Turnstile 확인 결과 (SCRUM-117, 계획 P5).
 *
 * <p>호출하는 쪽은 {@link #PASSED}와 {@link #UNAVAILABLE}을 통과시키고(fail-open, 2026-11-17 회의 결정),
 * {@link #REJECTED}만 {@code 403 ABUSE_VERIFICATION_FAILED}로 막는다.
 */
public enum TurnstileResult {

    /** Cloudflare가 토큰을 인정했고 action·hostname도 맞다 */
    PASSED,

    /** Cloudflare가 답했는데 실패다. 가짜·만료·재사용 토큰, 다른 화면의 action, 허용하지 않은 hostname, secret 설정 오류 */
    REJECTED,

    /** Cloudflare에 닿지 못했다. 연결 실패·시간 초과·5xx·429·{@code internal-error}·알 수 없는 응답 (재시도 1번 뒤) */
    UNAVAILABLE
}
