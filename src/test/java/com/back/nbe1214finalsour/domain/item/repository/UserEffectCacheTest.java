package com.back.nbe1214finalsour.domain.item.repository;

import com.back.nbe1214finalsour.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
public class UserEffectCacheTest {
    private static final Long USER_ID = 900_20L;
    private static final String KEY = "user:" + USER_ID + ":effect";

    @Autowired
    private UserEffectCache userEffectCache;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        redisTemplate.delete(KEY);
    }

    @Test
    @DisplayName("효과 키가 없으면(구매 안 함·만료) empty")
    void find_noEffect() {
        assertThat(this.userEffectCache.find(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("필드가 빠진 깨진 효과는 empty (배율 없이 잘못된 효과를 보여 주지 않음)")
    void find_brokenEffect() {
        this.redisTemplate.opsForHash().put(KEY, "multiplier", "2.0");   // itemId·expiredAt 없음

        assertThat(this.userEffectCache.find(USER_ID)).isEmpty();
    }
}
