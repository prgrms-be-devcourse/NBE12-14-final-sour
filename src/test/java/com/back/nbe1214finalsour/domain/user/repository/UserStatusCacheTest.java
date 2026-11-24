package com.back.nbe1214finalsour.domain.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.global.security.jwt.JwtProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class UserStatusCacheTest {

    private static final Long USER_ID = 7L;
    private static final String KEY = "user:7:status";
    /** 설정 기본값(60분)과 다른 값: TTL이 설정을 따르는지 확인한다 */
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private UserStatusCache cache;

    @BeforeEach
    void setUp() {
        given(redis.opsForValue()).willReturn(valueOperations);
        cache = new UserStatusCache(redis,
                new JwtProperties("unused-secret", ACCESS_TOKEN_TTL, Duration.ofDays(30)));
    }

    @Test
    @DisplayName("find: 캐시 값이 있으면 상태로, 없으면 빈 값으로 돌려준다")
    void find() {
        given(valueOperations.get(KEY)).willReturn("SUSPENDED", (String) null);

        assertThat(cache.find(USER_ID)).contains(UserStatus.SUSPENDED);
        assertThat(cache.find(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("fillIfAbsent: 키가 없을 때만 jwt.access-token-ttl을 붙여 저장하고, 저장한 값을 돌려준다")
    void fillIfAbsentStoresWithAccessTokenTtl() {
        given(valueOperations.setIfAbsent(KEY, "ACTIVE", ACCESS_TOKEN_TTL)).willReturn(true);

        assertThat(cache.fillIfAbsent(USER_ID, UserStatus.ACTIVE)).isEqualTo(UserStatus.ACTIVE);
        verify(valueOperations, never()).set(KEY, "ACTIVE", ACCESS_TOKEN_TTL);
    }

    @Test
    @DisplayName("fillIfAbsent: 그사이 다른 쪽(탈퇴 등)이 값을 넣었으면 덮지 않고 그 값을 돌려준다")
    void fillIfAbsentDoesNotOverwrite() {
        given(valueOperations.setIfAbsent(KEY, "ACTIVE", ACCESS_TOKEN_TTL)).willReturn(false);
        given(valueOperations.get(KEY)).willReturn("WITHDRAWN");

        assertThat(cache.fillIfAbsent(USER_ID, UserStatus.ACTIVE)).isEqualTo(UserStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("fillIfAbsent: 저장에 실패했는데 그 사이 만료돼 값이 없으면 DB에서 읽은 값을 돌려준다")
    void fillIfAbsentFallsBackWhenRaceValueExpired() {
        given(valueOperations.setIfAbsent(KEY, "ACTIVE", ACCESS_TOKEN_TTL)).willReturn(false);
        given(valueOperations.get(KEY)).willReturn(null);

        assertThat(cache.fillIfAbsent(USER_ID, UserStatus.ACTIVE)).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("put: 기존 값과 상관없이 jwt.access-token-ttl을 붙여 덮어쓴다")
    void putOverwritesWithAccessTokenTtl() {
        cache.put(USER_ID, UserStatus.WITHDRAWN);

        verify(valueOperations).set(KEY, "WITHDRAWN", ACCESS_TOKEN_TTL);
    }
}
