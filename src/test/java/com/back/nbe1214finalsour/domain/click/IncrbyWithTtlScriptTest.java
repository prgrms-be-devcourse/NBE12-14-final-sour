package com.back.nbe1214finalsour.domain.click;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code incrby-with-ttl.lua}를 실제 Redis(Testcontainers)에 돌려 확인한다.
 * 단위 테스트({@code ClickCountLimiterTest})는 이 스크립트를 목으로 가리므로 여기서만 검증된다.
 *
 * <p>순위 키는 실제 날짜와 겹치지 않도록 지난 날짜({@link #DAY})를 쓰고 통째로 지운다.
 */
@IntegrationTest
class IncrbyWithTtlScriptTest {

    private static final RedisScript<Long> INCRBY_WITH_TTL =
            RedisScript.of(new ClassPathResource("redis/incrby-with-ttl.lua"), Long.class);

    private static final int WINDOW_SECONDS = 60;
    private static final int MAX_CLICKS_PER_WINDOW = 900;
    private static final int ABUSE_RANK_SECONDS = 60 * 60 * 72;
    private static final String DAY = "20000101";

    private static final long USER_ID = 900_401L;
    private static final long OTHER_USER_ID = 900_402L;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redis.delete(List.of(
                RedisKey.RATELIMIT_CLICK.of(USER_ID),
                RedisKey.RATELIMIT_CLICK.of(OTHER_USER_ID),
                RedisKey.RANK_USER_ABUSE.of(DAY)));
    }

    @Test
    @DisplayName("INCRBY 결과를 반환하고, 한도 키에 60초 만료를 건다")
    void returnsCountAndSetsWindowTtl() {
        assertThat(check(USER_ID, 20)).isEqualTo(20L);
        assertThat(check(USER_ID, 15)).isEqualTo(35L);

        assertThat(redis.getExpire(RedisKey.RATELIMIT_CLICK.of(USER_ID)))
                .isPositive().isLessThanOrEqualTo(WINDOW_SECONDS);
    }

    @Test
    @DisplayName("합계가 정확히 900이면 부정행위로 기록하지 않는다 (900까지 허용)")
    void doesNotRecordAtLimit() {
        redis.opsForValue().set(RedisKey.RATELIMIT_CLICK.of(USER_ID), "880");

        assertThat(check(USER_ID, 20)).isEqualTo(900L);

        assertThat(abuseCount(USER_ID)).isNull();
    }

    @Test
    @DisplayName("900을 처음 넘는 요청에서 부정행위 1회를 기록하고 순위 키에 72시간 만료를 건다")
    void recordsOnceWhenCrossingLimit() {
        redis.opsForValue().set(RedisKey.RATELIMIT_CLICK.of(USER_ID), "890");

        assertThat(check(USER_ID, 20)).isEqualTo(910L);

        assertThat(abuseCount(USER_ID)).isEqualTo(1.0);
        assertThat(redis.getExpire(RedisKey.RANK_USER_ABUSE.of(DAY)))
                .isPositive().isLessThanOrEqualTo(ABUSE_RANK_SECONDS);
    }

    @Test
    @DisplayName("이미 넘은 창에서 다시 요청해도 횟수는 늘지 않는다 (창마다 1회)")
    void doesNotRecordAgainInSameWindow() {
        redis.opsForValue().set(RedisKey.RATELIMIT_CLICK.of(USER_ID), "890");

        check(USER_ID, 20);
        check(USER_ID, 20);
        check(USER_ID, 20);

        assertThat(abuseCount(USER_ID)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("창이 바뀐 뒤 다시 넘으면 횟수가 누적된다")
    void accumulatesAcrossWindows() {
        redis.opsForValue().set(RedisKey.RATELIMIT_CLICK.of(USER_ID), "890");
        check(USER_ID, 20);

        // 한도 키가 만료된 상황을 흉내 낸다
        redis.delete(RedisKey.RATELIMIT_CLICK.of(USER_ID));
        redis.opsForValue().set(RedisKey.RATELIMIT_CLICK.of(USER_ID), "890");
        check(USER_ID, 20);

        assertThat(abuseCount(USER_ID)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("이미 만료가 걸린 순위 키는 다시 기록해도 만료를 늘리지 않는다")
    void doesNotExtendAbuseRankTtl() {
        redis.opsForValue().set(RedisKey.RATELIMIT_CLICK.of(USER_ID), "890");
        check(USER_ID, 20);
        redis.expire(RedisKey.RANK_USER_ABUSE.of(DAY), Duration.ofSeconds(100));

        redis.opsForValue().set(RedisKey.RATELIMIT_CLICK.of(OTHER_USER_ID), "890");
        check(OTHER_USER_ID, 20);

        assertThat(redis.getExpire(RedisKey.RANK_USER_ABUSE.of(DAY)))
                .isPositive().isLessThanOrEqualTo(100L);
    }

    @Test
    @DisplayName("회원마다 따로 기록되고, member는 평문 userId다")
    void recordsPerUser() {
        redis.opsForValue().set(RedisKey.RATELIMIT_CLICK.of(USER_ID), "890");
        redis.opsForValue().set(RedisKey.RATELIMIT_CLICK.of(OTHER_USER_ID), "890");

        check(USER_ID, 20);
        check(OTHER_USER_ID, 20);

        assertThat(redis.opsForZSet().range(RedisKey.RANK_USER_ABUSE.of(DAY), 0, -1))
                .containsExactlyInAnyOrder(String.valueOf(USER_ID), String.valueOf(OTHER_USER_ID));
        assertThat(abuseCount(USER_ID)).isEqualTo(1.0);
        assertThat(abuseCount(OTHER_USER_ID)).isEqualTo(1.0);
    }

    private Long check(long userId, int clickCount) {
        return redis.execute(
                INCRBY_WITH_TTL,
                List.of(RedisKey.RATELIMIT_CLICK.of(userId),
                        RedisKey.RANK_USER_ABUSE.of(DAY)),
                String.valueOf(WINDOW_SECONDS),
                String.valueOf(clickCount),
                String.valueOf(MAX_CLICKS_PER_WINDOW),
                String.valueOf(ABUSE_RANK_SECONDS),
                String.valueOf(userId));
    }

    private Double abuseCount(long userId) {
        return redis.opsForZSet().score(RedisKey.RANK_USER_ABUSE.of(DAY), String.valueOf(userId));
    }
}
