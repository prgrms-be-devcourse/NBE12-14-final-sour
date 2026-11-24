package com.back.nbe1214finalsour.global.turnstile;

import org.jspecify.annotations.Nullable;

/**
 * 브라우저가 받은 Turnstile 토큰을 서버에서 확인한다 (SCRUM-117).
 * 어뷰징이 의심될 때만 부른다. 정상 요청 경로에서는 부르지 않는다 (2026-11-17 회의 결정).
 */
public interface TurnstileVerifier {

    /**
     * 확인 기능이 켜져 있는지 ({@code turnstile.enabled}). 꺼져 있으면 호출하는 쪽은 의심 표시를 남기거나
     * 토큰을 요구하지 않고 지금과 똑같이 동작해야 한다.
     */
    boolean enabled();

    /**
     * @param token          위젯이 준 토큰. 위젯을 못 불러온 경우 비어 있을 수 있다 (계획 P6: 그래도 Cloudflare에 묻는다)
     * @param expectedAction 위젯에 넣은 action (예: {@code abuse_click}). 다른 화면에서 받은 토큰을 막는다
     * @param clientIp       요청한 사용자의 IP. 없으면 보내지 않는다
     */
    TurnstileResult verify(@Nullable String token, String expectedAction, @Nullable String clientIp);
}
