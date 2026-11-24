package com.back.nbe1214finalsour.domain.support.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.turnstile.TurnstileResult;
import com.back.nbe1214finalsour.global.turnstile.TurnstileVerifier;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 비회원 문의 의심 구간의 봇 확인 (SCRUM-119, 계획 P3·P4·P5·P6). 기준: 같은 IP 1시간 3번째부터, 한도 5건.
 * 실제 Redis·HTTP 흐름은 {@code SupportGuestBotCheckIntegrationTest}가 본다.
 */
@ExtendWith(MockitoExtension.class)
class SupportBotCheckTest {

    private static final String IP = "203.0.113.7";

    @Mock
    private SupportRateLimiter rateLimiter;
    @Mock
    private TurnstileVerifier turnstileVerifier;

    private SupportBotCheck botCheck;

    @BeforeEach
    void setUp() {
        botCheck = new SupportBotCheck(rateLimiter, turnstileVerifier,
                new SupportRateLimitProperties(5, Duration.ofHours(1)), new SupportBotCheckProperties(3));
    }

    @Test
    @DisplayName("Turnstile이 꺼져 있으면 건수를 읽지도 않는다 (지금과 동작이 같다)")
    void disabledDoesNothing() {
        given(turnstileVerifier.enabled()).willReturn(false);

        botCheck.checkGuest(IP, null);

        verifyNoInteractions(rateLimiter);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L})
    @DisplayName("1~2번째 문의(이미 0~1건)는 토큰 없이 통과한다")
    void belowThresholdPasses(long count) {
        given(turnstileVerifier.enabled()).willReturn(true);
        given(rateLimiter.guestCount(IP)).willReturn(count);

        botCheck.checkGuest(IP, null);

        verify(turnstileVerifier, never()).verify(any(), anyString(), any());
    }

    @Test
    @DisplayName("3번째 문의부터 토큰 필드가 없으면 ABUSE_VERIFICATION_REQUIRED (프론트가 위젯을 띄우는 신호)")
    void missingTokenRequired() {
        given(turnstileVerifier.enabled()).willReturn(true);
        given(rateLimiter.guestCount(IP)).willReturn(2L);

        assertThatThrownBy(() -> botCheck.checkGuest(IP, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ABUSE_VERIFICATION_REQUIRED);
        verify(turnstileVerifier, never()).verify(any(), anyString(), any());
    }

    @Test
    @DisplayName("Cloudflare가 거절하면 ABUSE_VERIFICATION_FAILED")
    void rejectedFails() {
        given(turnstileVerifier.enabled()).willReturn(true);
        given(rateLimiter.guestCount(IP)).willReturn(3L);
        given(turnstileVerifier.verify("bad", SupportBotCheck.GUEST_ACTION, IP)).willReturn(TurnstileResult.REJECTED);

        assertThatThrownBy(() -> botCheck.checkGuest(IP, "bad"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ABUSE_VERIFICATION_FAILED);
    }

    @Test
    @DisplayName("통과면 계속한다")
    void passedContinues() {
        given(turnstileVerifier.enabled()).willReturn(true);
        given(rateLimiter.guestCount(IP)).willReturn(2L);
        given(turnstileVerifier.verify("ok", SupportBotCheck.GUEST_ACTION, IP)).willReturn(TurnstileResult.PASSED);

        assertThatCode(() -> botCheck.checkGuest(IP, "ok")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("빈 문자열 토큰은 위젯을 못 불러왔다는 뜻이다. 그래도 Cloudflare에 묻고, 닿지 못하면 통과시킨다 (P6)")
    void emptyTokenStillVerifiedAndFailOpen() {
        given(turnstileVerifier.enabled()).willReturn(true);
        given(rateLimiter.guestCount(IP)).willReturn(2L);
        given(turnstileVerifier.verify("", SupportBotCheck.GUEST_ACTION, IP)).willReturn(TurnstileResult.UNAVAILABLE);

        assertThatCode(() -> botCheck.checkGuest(IP, "")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이미 한도(5건)를 채웠으면 확인을 건너뛴다. 뒤의 한도 검사가 지금처럼 429를 낸다")
    void atLimitSkipsVerification() {
        given(turnstileVerifier.enabled()).willReturn(true);
        given(rateLimiter.guestCount(IP)).willReturn(5L);

        botCheck.checkGuest(IP, null);

        verify(turnstileVerifier, never()).verify(any(), anyString(), any());
    }
}
