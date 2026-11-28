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

import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code apply-attack.lua}의 초별 학교 공격 수(공격 파티클 연출용)를 실제 Redis(Testcontainers)로 확인한다.
 * 피해량·공격 점수는 {@code AttackIntegrationTest}에서 API로 검증한다.
 *
 * <p>초별 키는 공격한 학교(내 학교) 기준이다. {@code rank:univ}·{@code dirty:*}·{@code score:user:attack}은
 * 공유 키라서 DEL 하지 않고 이 테스트가 넣은 member·field만 지운다.
 */
@IntegrationTest
class ApplyAttackScriptTest {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> APPLY_ATTACK =
            RedisScript.of(new ClassPathResource("redis/apply-attack.lua"), List.class);

    private static final long UNIVERSITY_ID = 900_401L;
    private static final long TARGET_UNIVERSITY_ID = 900_402L;
    private static final long USER_ID = 900_403L;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redis.opsForZSet().remove(RedisKey.RANK_UNIV.of(), String.valueOf(TARGET_UNIVERSITY_ID));
        redis.opsForSet().remove(RedisKey.DIRTY_UNIV.of(), String.valueOf(TARGET_UNIVERSITY_ID));
        redis.opsForSet().remove(RedisKey.DIRTY_USER.of(), String.valueOf(USER_ID));
        redis.opsForHash().delete(RedisKey.SCORE_USER_ATTACK.of(), String.valueOf(USER_ID));

        // 초별 키는 TTL 5초라 같은 초에 돌아간 다른 테스트의 값이 남는다. 창을 넉넉히 지운다
        long nowSec = Instant.now().getEpochSecond();
        redis.delete(LongStream.rangeClosed(nowSec - 8, nowSec + 2)
                .boxed()
                .flatMap(sec -> Stream.of(
                        RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, sec),
                        RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, sec),
                        RedisKey.ASSISTS_UNIV_SECOND.of(UNIVERSITY_ID, sec)))
                .toList());
    }

    @Test
    @DisplayName("이번 초 학교 공격 수를 clickCount만큼 올리고 5초 만료를 건다")
    void incrementsCurrentSecondAttacksWithTtl() {
        long nowSec = Instant.now().getEpochSecond();
        String currentSecondKey = RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec);
        givenTargetScore(1_000);

        apply(nowSec, 30, 10);
        apply(nowSec, 6, 2);

        assertThat(redis.opsForValue().get(currentSecondKey)).isEqualTo("12");
        assertThat(redis.getExpire(currentSecondKey)).isPositive().isLessThanOrEqualTo(5L);
    }

    @Test
    @DisplayName("피해가 0이어도(대상 점수 없음) 공격 수는 센다")
    void countsAttacksEvenWithoutDamage() {
        long nowSec = Instant.now().getEpochSecond();

        List<String> result = apply(nowSec, 30, 10);

        assertThat(result.get(0)).isEqualTo("0");
        assertThat(redis.opsForValue().get(RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec))).isEqualTo("10");
    }

    @Test
    @DisplayName("공격 수는 내 학교 키에만 쌓이고 클릭 수 키는 건드리지 않는다")
    void doesNotTouchClickCounts() {
        long nowSec = Instant.now().getEpochSecond();
        givenTargetScore(1_000);

        apply(nowSec, 30, 10);

        assertThat(redis.hasKey(RedisKey.ATTACKS_UNIV_SECOND.of(TARGET_UNIVERSITY_ID, nowSec))).isFalse();
        assertThat(redis.hasKey(RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec))).isFalse();
    }

    @Test
    @DisplayName("직전 1초 학교 공격 수와 클릭 수를 5·6번째로 반환하고, 값이 없으면 0을 반환한다")
    void returnsPreviousSecondAttacksAndClicks() {
        long nowSec = Instant.now().getEpochSecond();
        givenTargetScore(1_000);

        // 직전 초에 기록이 없을 때
        List<String> empty = apply(nowSec, 30, 10);
        assertThat(empty).hasSize(7);
        assertThat(empty.subList(4, 6)).containsExactly("0", "0");

        // 직전 초에 공격 7회, 클릭 4회가 기록되어 있을 때
        redis.opsForValue().set(RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1), "7");
        redis.opsForValue().set(RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1), "4");
        assertThat(apply(nowSec, 30, 10).subList(4, 6)).containsExactly("7", "4");
    }

    @Test
    @DisplayName("이번 초에 센 공격 수는 이번 응답의 직전 1초 값에 들어가지 않는다")
    void currentSecondIsNotReportedAsPreviousSecond() {
        long nowSec = Instant.now().getEpochSecond();
        givenTargetScore(1_000);

        apply(nowSec, 30, 10);
        List<String> result = apply(nowSec, 30, 10);

        assertThat(result.get(4)).isEqualTo("0");
        // 다음 초에 공격하면 이번 초 공격 수가 직전 1초로 보인다
        assertThat(apply(nowSec + 1, 30, 1).get(4)).isEqualTo("20");
    }

    @Test
    @DisplayName("직전 1초 학교 지원 수를 7번째로 반환하고, 값이 없으면 0을 반환한다 (공격·클릭 수와 섞이지 않는다)")
    void returnsPreviousSecondAssists() {
        long nowSec = Instant.now().getEpochSecond();
        givenTargetScore(1_000);

        assertThat(apply(nowSec, 30, 10).get(6)).isEqualTo("0");

        redis.opsForValue().set(RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1), "7");
        redis.opsForValue().set(RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1), "4");
        redis.opsForValue().set(RedisKey.ASSISTS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1), "9");
        assertThat(apply(nowSec, 30, 10).subList(4, 7)).containsExactly("7", "4", "9");
    }

    @Test
    @DisplayName("공격은 지원 수를 올리지 않는다")
    void attackDoesNotCountAsAssist() {
        long nowSec = Instant.now().getEpochSecond();
        givenTargetScore(1_000);

        apply(nowSec, 30, 10);

        assertThat(redis.hasKey(RedisKey.ASSISTS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec))).isFalse();
    }

    private void givenTargetScore(double score) {
        redis.opsForZSet().add(RedisKey.RANK_UNIV.of(), String.valueOf(TARGET_UNIVERSITY_ID), score);
    }

    @SuppressWarnings("unchecked")
    private List<String> apply(long nowSec, long requestedDamage, int clickCount) {
        return redis.execute(
                APPLY_ATTACK,
                List.of(
                        RedisKey.RANK_UNIV.of(),
                        RedisKey.DIRTY_UNIV.of(),
                        RedisKey.SCORE_USER_ATTACK.of(),
                        RedisKey.DIRTY_USER.of(),
                        RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec),
                        RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1),
                        RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1),
                        RedisKey.ASSISTS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1)),
                String.valueOf(TARGET_UNIVERSITY_ID),
                String.valueOf(requestedDamage),
                String.valueOf(USER_ID),
                String.valueOf(clickCount));
    }
}
