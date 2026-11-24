package com.back.nbe1214finalsour.global.turnstile;

import org.jspecify.annotations.Nullable;

/**
 * {@code turnstile.enabled=false}일 때 쓰는 구현. Cloudflare를 부르지 않고 항상 통과시킨다.
 * 호출하는 쪽은 {@link #enabled()}가 false면 애초에 확인을 요구하지 않아야 한다.
 */
final class DisabledTurnstileVerifier implements TurnstileVerifier {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public TurnstileResult verify(@Nullable String token, String expectedAction, @Nullable String clientIp) {
        return TurnstileResult.PASSED;
    }
}
