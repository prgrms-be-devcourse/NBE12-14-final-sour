package com.back.nbe1214finalsour.domain.clicker.repository;

import com.back.nbe1214finalsour.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;

@IntegrationTest
public class UserClickerLevelCacheTest {

    private static final Long USER_ID = 900_10L;
    private static final String KEY = "user:" + USER_ID + ":clicker";

    @Autowired
    private UserClickerLevelCache userClickerLevelCache;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UserClickerLevelStore userClickerLevelStore;

    @AfterEach
    public void tearDown() {
        redisTemplate.delete(KEY);
    }

    private String cachedLevel(){
        return redisTemplate.opsForValue().get(KEY);
    }

    @Test
    @DisplayName("레벨 키가 없으면 DB 레벨을 Redis에 저장하고 그 값을 반환한다")
    void loadIfAbsent_whenEmpty() {
        // given
        willReturn(2L).given(userClickerLevelStore).findLevel(USER_ID);

        // when
        long level = userClickerLevelCache.loadIfAbsent(USER_ID);

        // then
        assertThat(level).isEqualTo(2L);
        assertThat(cachedLevel()).isEqualTo("2");
    }

    @Test
    @DisplayName("레벨 키가 있으면 DB를 읽지 않고 Redis 값을 반환하며 덮어쓰지 않는다")
    void loadIfAbsent_whenPresent() {
        // given
        redisTemplate.opsForValue().set(KEY, "3");
        willReturn(2L).given(userClickerLevelStore).findLevel(USER_ID);

        // when
        long level = userClickerLevelCache.loadIfAbsent(USER_ID);

        // then
        assertThat(level).isEqualTo(3L);
        assertThat(cachedLevel()).isEqualTo("3");
        then(userClickerLevelStore).should(never()).findLevel(anyLong());
    }

    @Test
    @DisplayName("DB를 읽는 사이 다른 요청이 키를 먼저 채우면 DB 값이 아니라 그 값을 반환한다")
    void loadIfAbsent_whenFilledConcurrently() {
        // given
        willAnswer(invocation -> {
            redisTemplate.opsForValue().set(KEY, "3");
            return 2L;
        }).given(userClickerLevelStore).findLevel(USER_ID);

        // when
        long level = userClickerLevelCache.loadIfAbsent(USER_ID);

        // then
        assertThat(level).isEqualTo(3L);
        assertThat(cachedLevel()).isEqualTo("3");
    }

    @Test
    @DisplayName("레벨 키가 있으면 그 레벨을 반환한다")
    void find_whenPresent() {
        // given
        redisTemplate.opsForValue().set(KEY, "3");

        // when
        Optional<Long> level = userClickerLevelCache.find(USER_ID);

        // then
        assertThat(level).contains(3L);
    }

    @Test
    @DisplayName("레벨 키가 없으면 빈 값을 반환하고 키를 만들지 않는다")
    void find_whenEmpty() {
        // given (키 없음)

        // when
        Optional<Long> level = userClickerLevelCache.find(USER_ID);

        // then
        assertThat(level).isEmpty();
        assertThat(redisTemplate.hasKey(KEY)).isFalse();
    }
}
