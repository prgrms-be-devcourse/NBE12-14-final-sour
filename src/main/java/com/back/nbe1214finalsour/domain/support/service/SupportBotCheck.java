package com.back.nbe1214finalsour.domain.support.service;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.turnstile.TurnstileResult;
import com.back.nbe1214finalsour.global.turnstile.TurnstileVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 같은 IP에서 비회원 문의가 몰릴 때 봇 확인을 요구한다 (SCRUM-119, 계획 §3-3·P3~P6).
 *
 * <p>현재 IP 건수를 읽기만 하고(INCR 하지 않음) 확인한다. 그래서 확인에 걸린 요청은 비회원 5건 한도를 쓰지 않는다. 읽은 뒤
 * INCR까지 사이에 동시 요청이 끼면 확인 구간 경계가 한두 건 어긋날 수 있지만, 5건 한도는 INCR로 지켜진다.
 *
 * <ul>
 *   <li>Turnstile이 꺼져 있으면 아무것도 하지 않는다 (P10).</li>
 *   <li>이미 한도를 채웠으면 확인하지 않는다. 뒤의 {@link SupportRateLimiter#checkGuest}가 지금처럼 429를 낸다.</li>
 *   <li>토큰 필드가 없으면 403 {@code ABUSE_VERIFICATION_REQUIRED}. 프론트가 위젯을 띄우라는 신호다.</li>
 *   <li>빈 문자열 토큰은 위젯을 불러오지 못했다는 뜻이다. 그래도 Cloudflare에 묻는다. Cloudflare가 답하면 거절이고, 닿지
 *   못하면 통과다 (P6).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(SupportBotCheckProperties.class)
public class SupportBotCheck {

    /** 위젯과 siteverify 응답의 action. 다른 화면의 토큰을 받지 않는다 (P8) */
    public static final String GUEST_ACTION = "support_guest";

    private final SupportRateLimiter rateLimiter;
    private final TurnstileVerifier turnstileVerifier;
    private final SupportRateLimitProperties rateLimitProperties;
    private final SupportBotCheckProperties properties;

    /**
     * @param turnstileToken 요청의 {@code turnstileToken}. 필드가 없으면 {@code null}
     * @throws BusinessException 403 {@code ABUSE_VERIFICATION_REQUIRED} 확인 구간인데 토큰 없음, 403
     *                           {@code ABUSE_VERIFICATION_FAILED} Cloudflare가 거절
     */
    public void checkGuest(String clientIp, @Nullable String turnstileToken) {
        if (!turnstileVerifier.enabled()) {
            return;
        }
        long count = rateLimiter.guestCount(clientIp);
        if (count + 1 < properties.verifyFrom() || count >= rateLimitProperties.maxRequests()) {
            return;
        }
        if (turnstileToken == null) {
            throw new BusinessException(ErrorCode.ABUSE_VERIFICATION_REQUIRED,
                    "비회원 문의 " + (count + 1) + "번째, 토큰 없음");
        }
        TurnstileResult result = turnstileVerifier.verify(turnstileToken, GUEST_ACTION, clientIp);
        if (result == TurnstileResult.REJECTED) {
            throw new BusinessException(ErrorCode.ABUSE_VERIFICATION_FAILED, "비회원 문의 봇 확인 거절");
        }
        if (result == TurnstileResult.UNAVAILABLE) {
            log.warn("Turnstile 연결 불가로 비회원 문의 확인을 건너뜀 (fail-open)");
        }
    }
}
