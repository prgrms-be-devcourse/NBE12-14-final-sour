package com.back.nbe1214finalsour.domain.abuse.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 클릭 어뷰징 의심 표시와 봇 확인 (SCRUM-118, 계획 P2·P5·P7·P10·P14). Redis와 Turnstile은 목이다.
 * 실제 Redis 동작과 HTTP 흐름은 {@code AbuseVerificationIntegrationTest}에서 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class AbuseVerificationServiceTest {

    private static final Long USER_ID = 7L;
    private static final String FLAG_KEY = "abuse:verify:user:7";
    private static final String IP = "203.0.113.7";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private TurnstileVerifier turnstileVerifier;

    private final AbuseVerificationProperties properties =
            new AbuseVerificationProperties(Duration.ofHours(24), 10, Duration.ofMinutes(10));

    private AbuseVerificationService service;

    @BeforeEach
    void setUp() {
        service = new AbuseVerificationService(redis, turnstileVerifier, properties);
    }

    @Nested
    @DisplayName("Turnstile이 꺼져 있으면 지금과 동작이 같다 (P10)")
    class Disabled {

        @BeforeEach
        void disabled() {
            given(turnstileVerifier.enabled()).willReturn(false);
        }

        @Test
        @DisplayName("표시를 남기지도, 확인하지도 않는다")
        void noRedisAccess() {
            service.flag(USER_ID);
            service.requireNotFlagged(USER_ID);
            service.verify(USER_ID, "token", IP);

            verifyNoInteractions(redis);
            verify(turnstileVerifier, never()).verify(any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("켜져 있을 때")
    class Enabled {

        @BeforeEach
        void enabled() {
            given(turnstileVerifier.enabled()).willReturn(true);
        }

        @Test
        @DisplayName("한도를 넘으면 abuse:verify:user:{userId}에 24시간 표시를 남긴다")
        void flagSetsKeyWithTtl() {
            given(redis.opsForValue()).willReturn(valueOperations);

            service.flag(USER_ID);

            verify(valueOperations).set(FLAG_KEY, "1", Duration.ofHours(24));
        }

        @Test
        @DisplayName("표시가 있으면 ABUSE_VERIFICATION_REQUIRED")
        void flaggedUserIsBlocked() {
            given(redis.hasKey(FLAG_KEY)).willReturn(true);

            assertThatThrownBy(() -> service.requireNotFlagged(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.ABUSE_VERIFICATION_REQUIRED);
        }

        @Test
        @DisplayName("표시가 없으면 통과한다")
        void unflaggedUserPasses() {
            given(redis.hasKey(FLAG_KEY)).willReturn(false);

            assertThatCode(() -> service.requireNotFlagged(USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("확인 통과면 표시와 클릭 한도 카운터를 지운다 (바로 다시 429·표시되지 않게)")
        void passedClearsFlagAndCounters() {
            givenAttempt(1L);
            given(turnstileVerifier.verify("token", AbuseVerificationService.CLICK_ACTION, IP))
                    .willReturn(TurnstileResult.PASSED);
            given(redis.delete(FLAG_KEY)).willReturn(true);

            service.verify(USER_ID, "token", IP);

            verify(redis).delete(List.of("ratelimit:action:7", "ratelimit:click:7"));
        }

        @Test
        @DisplayName("Cloudflare에 닿지 못하면 통과로 보고 표시를 지운다 (fail-open, P5)")
        void unavailableClearsFlag() {
            givenAttempt(1L);
            given(turnstileVerifier.verify(null, AbuseVerificationService.CLICK_ACTION, IP))
                    .willReturn(TurnstileResult.UNAVAILABLE);
            given(redis.delete(FLAG_KEY)).willReturn(true);

            service.verify(USER_ID, null, IP);

            verify(redis).delete(List.of("ratelimit:action:7", "ratelimit:click:7"));
        }

        @Test
        @DisplayName("표시가 없을 때 통과해도 204로 끝나고 한도 카운터는 그대로다 (확인으로 한도를 초기화하지 못하게)")
        void passedWithoutFlagKeepsCounters() {
            givenAttempt(1L);
            given(turnstileVerifier.verify("token", AbuseVerificationService.CLICK_ACTION, IP))
                    .willReturn(TurnstileResult.PASSED);
            given(redis.delete(FLAG_KEY)).willReturn(false);

            service.verify(USER_ID, "token", IP);

            verify(redis, never()).delete(anyList());
        }

        @Test
        @DisplayName("Cloudflare가 거절하면 ABUSE_VERIFICATION_FAILED이고 표시를 남긴다")
        void rejectedKeepsFlag() {
            givenAttempt(1L);
            given(turnstileVerifier.verify("bad", AbuseVerificationService.CLICK_ACTION, IP))
                    .willReturn(TurnstileResult.REJECTED);

            assertThatThrownBy(() -> service.verify(USER_ID, "bad", IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.ABUSE_VERIFICATION_FAILED);
            verify(redis, never()).delete(anyString());
        }

        @Test
        @DisplayName("확인 시도가 10분에 10번을 넘으면 Cloudflare를 부르지 않고 429 (P7)")
        void tooManyAttempts() {
            givenAttempt(11L);

            assertThatThrownBy(() -> service.verify(USER_ID, "token", IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.COMMON_TOO_MANY_REQUESTS);
            verify(turnstileVerifier, never()).verify(any(), anyString(), any());
        }

        private void givenAttempt(long count) {
            given(redis.<Long>execute(any(), anyList(), any())).willReturn(count);
        }
    }
}
