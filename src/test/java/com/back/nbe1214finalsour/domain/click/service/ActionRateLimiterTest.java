package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 한도는 10초에 12회다. Lua({@code incr-with-ttl.lua})가 INCR한 뒤의 값을 받아
 * 12를 넘겼을 때만 막는다.
 */
@ExtendWith(MockitoExtension.class)
class ActionRateLimiterTest {

    private static final Long USER_ID = 1L;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private ActionRateLimiter actionRateLimiter;

    @ParameterizedTest
    @ValueSource(longs = {1L, 11L, 12L})
    @DisplayName("INCR 결과가 12 이하면 통과한다 (12회째 요청까지 허용)")
    void check_allowsUpToLimit(long count) {
        given(stringRedisTemplate.<Long>execute(any(), anyList(), any())).willReturn(count);

        assertThatCode(() -> actionRateLimiter.check(USER_ID)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {13L, 100L})
    @DisplayName("INCR 결과가 12를 넘으면 CLICK_TOO_FREQUENT 예외")
    void check_rejectsOverLimit(long count) {
        given(stringRedisTemplate.<Long>execute(any(), anyList(), any())).willReturn(count);

        assertThatThrownBy(() -> actionRateLimiter.check(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLICK_TOO_FREQUENT);
    }

    @Test
    @DisplayName("스크립트 결과가 null이면 막지 않는다 (한도 판단을 포기하고 통과시킨다)")
    void check_allowsWhenScriptReturnsNull() {
        given(stringRedisTemplate.<Long>execute(any(), anyList(), any())).willReturn(null);

        assertThatCode(() -> actionRateLimiter.check(USER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ratelimit:action:{userId} 키 하나와 TTL 10초를 스크립트에 넘긴다")
    void check_passesActionKeyAndWindowSeconds() {
        given(stringRedisTemplate.<Long>execute(any(), anyList(), any())).willReturn(1L);

        actionRateLimiter.check(USER_ID);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.captor();
        verify(stringRedisTemplate).execute(any(), keysCaptor.capture(), argsCaptor.capture());

        assertThat(keysCaptor.getValue()).containsExactly("ratelimit:action:1");
        // Lua의 ARGV[1]은 EXPIRE 초다. 문자열로 넘어간다
        assertThat(argsCaptor.getAllValues()).containsExactly("10");
    }
}
