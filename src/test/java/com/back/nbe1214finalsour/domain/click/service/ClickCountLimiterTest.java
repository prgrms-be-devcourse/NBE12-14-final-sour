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
 * 한도는 1분에 clickCount 합계 900이다. Lua({@code incrby-with-ttl.lua})가 INCRBY한 뒤의 값을 받아
 * 900을 넘겼을 때만 막는다.
 */
@ExtendWith(MockitoExtension.class)
class ClickCountLimiterTest {

    private static final Long USER_ID = 1L;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private ClickCountLimiter clickCountLimiter;

    @ParameterizedTest
    @ValueSource(longs = {1L, 899L, 900L})
    @DisplayName("INCRBY 결과가 900 이하면 통과한다 (합계 900까지 허용)")
    void check_allowsUpToLimit(long count) {
        given(stringRedisTemplate.<Long>execute(any(), anyList(), any(), any())).willReturn(count);

        assertThatCode(() -> clickCountLimiter.check(USER_ID, 20)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {901L, 2000L})
    @DisplayName("INCRBY 결과가 900을 넘으면 CLICK_COUNT_EXCEEDED 예외")
    void check_rejectsOverLimit(long count) {
        given(stringRedisTemplate.<Long>execute(any(), anyList(), any(), any())).willReturn(count);

        assertThatThrownBy(() -> clickCountLimiter.check(USER_ID, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLICK_COUNT_EXCEEDED);
    }

    @Test
    @DisplayName("스크립트 결과가 null이면 막지 않는다 (한도 판단을 포기하고 통과시킨다)")
    void check_allowsWhenScriptReturnsNull() {
        given(stringRedisTemplate.<Long>execute(any(), anyList(), any(), any())).willReturn(null);

        assertThatCode(() -> clickCountLimiter.check(USER_ID, 20)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ratelimit:click:{userId} 키 하나와 TTL 60초, clickCount를 스크립트에 넘긴다")
    void check_passesClickKeyWindowSecondsAndClickCount() {
        given(stringRedisTemplate.<Long>execute(any(), anyList(), any(), any())).willReturn(20L);

        clickCountLimiter.check(USER_ID, 20);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.captor();
        verify(stringRedisTemplate).execute(any(), keysCaptor.capture(), argsCaptor.capture(), argsCaptor.capture());

        assertThat(keysCaptor.getValue()).containsExactly("ratelimit:click:1");
        // Lua의 ARGV[1]은 EXPIRE 초, ARGV[2]는 INCRBY 증가량이다. 문자열로 넘어간다
        assertThat(argsCaptor.getAllValues()).containsExactly("60", "20");
    }
}
