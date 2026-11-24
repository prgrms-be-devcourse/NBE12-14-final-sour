package com.back.nbe1214finalsour.domain.support.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 문의 등록 한도 (SCRUM-110). Lua({@code incr-with-ttl.lua})가 INCR한 뒤의 값을 받아 5를 넘겼을 때만 막는다.
 * 키와 만료 시간이 회원·비회원별로 맞게 넘어가는지 확인한다. 실제 Redis 동작은 {@code SupportCreateIntegrationTest}가 본다.
 */
@ExtendWith(MockitoExtension.class)
class SupportRateLimiterTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private EmailHasher hasher;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SupportRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new SupportRateLimiter(redis, hasher, new SupportRateLimitProperties(5, Duration.ofHours(1)));
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 4L, 5L})
    @DisplayName("INCR 결과가 5 이하면 통과한다 (5건째까지 허용)")
    void allowsUpToLimit(long count) {
        given(redis.<Long>execute(any(), anyList(), any())).willReturn(count);

        assertThatCode(() -> limiter.checkMember(7L)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {6L, 100L})
    @DisplayName("INCR 결과가 5를 넘으면 429 COMMON_TOO_MANY_REQUESTS")
    void rejectsOverLimit(long count) {
        given(redis.<Long>execute(any(), anyList(), any())).willReturn(count);

        assertThatThrownBy(() -> limiter.checkMember(7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMON_TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("회원은 user:{userId} 키로 세고 만료는 3600초다")
    void memberKey() {
        given(redis.<Long>execute(any(), anyList(), any())).willReturn(1L);

        limiter.checkMember(7L);

        assertThat(capturedKeys()).containsExactly("ratelimit:support:user:7");
    }

    @Test
    @DisplayName("비회원은 IP 원문이 아니라 해시로 ip:{ipHash} 키를 쓴다")
    void guestKey() {
        given(hasher.hashClientIp("203.0.113.7")).willReturn("abc123");
        given(redis.<Long>execute(any(), anyList(), any())).willReturn(1L);

        limiter.checkGuest("203.0.113.7");

        assertThat(capturedKeys()).containsExactly("ratelimit:support:ip:abc123");
    }

    @Test
    @DisplayName("비회원 현재 건수는 세지 않고 읽기만 한다. 키가 없으면 0 (SCRUM-119)")
    void guestCountReadsWithoutIncrement() {
        given(hasher.hashClientIp("203.0.113.7")).willReturn("abc123");
        given(redis.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("ratelimit:support:ip:abc123")).willReturn("2", (String) null);

        assertThat(limiter.guestCount("203.0.113.7")).isEqualTo(2L);
        assertThat(limiter.guestCount("203.0.113.7")).isZero();
        verify(redis, never()).execute(any(), anyList(), any());
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedKeys() {
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(), keys.capture(), eq("3600"));
        return keys.getValue();
    }
}
