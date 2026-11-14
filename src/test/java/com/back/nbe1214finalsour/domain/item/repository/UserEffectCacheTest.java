package com.back.nbe1214finalsour.domain.item.repository;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
public class UserEffectCacheTest {
    private static final Long USER_ID = 900_20L;
    private static final String KEY = "user:" + USER_ID + ":effect";

    @Autowired
    private UserEffectCache userEffectCache;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisHandler redisHandler;


    @AfterEach
    void tearDown() {
        redisTemplate.delete(KEY);
    }

    // PEXPIREAT은 지난 시각이면 키를 바로 지우므로, 통합 테스트는 실제 현재 시각 기준 미래로 잡는다
    private OffsetDateTime oneHourLater() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS);
    }

    @Test
    @DisplayName("효과를 적용하면 itemId·multiplier·expiredAt(epoch ms)이 저장되고 만료 시각에 맞춰 TTL이 걸린다")
    void apply_savesEffectWithExpiry() {
        OffsetDateTime expiredAt = oneHourLater();

        this.userEffectCache.apply(USER_ID, 2L, 4.0, expiredAt);

        Map<Object, Object> effect = redisTemplate.opsForHash().entries(KEY);
        assertThat(effect)
                .containsEntry("itemId", "2")
                .containsEntry("multiplier", "4.0")
                .containsEntry("expiredAt", String.valueOf(expiredAt.toInstant().toEpochMilli()))
                .hasSize(3);

        // 남은 시간이 약 1시간 (테스트 실행 시간 감안해 범위로 확인)
        assertThat(this.redisTemplate.getExpire(KEY)).isBetween(3590L, 3600L);
    }

    @Test
    @DisplayName("이미 효과가 있으면 지우고 새 효과만 남긴다 (회원당 효과 1개)")
    void apply_overwritesExistingEffect() {
        // given: 기존 효과 + 새 효과에는 없는 필드
        this.redisTemplate.opsForHash().putAll(KEY, Map.of("itemId", "1", "multiplier", "2.0", "bonus", "5"));

        // when
        this.userEffectCache.apply(USER_ID, 2L, 4.0, oneHourLater());

        // then: 예전 필드(bonus)까지 사라지고 새 값만 남음
        assertThat(this.redisTemplate.opsForHash().entries(KEY))
                .containsEntry("itemId", "2")
                .containsEntry("multiplier", "4.0")
                .doesNotContainKey("bonus");
    }

    @Test
    @DisplayName("저장한 배율을 클릭 엔진(ClickService)과 같은 방식인 RedisHandler로 읽을 수 있다")
    void apply_readableByClickEngine() {
        this.userEffectCache.apply(USER_ID, 2L, 4.0, oneHourLater());

        assertThat(this.redisHandler.getHash(KEY, "multiplier", Double.class)).contains(4.0);
    }
}
