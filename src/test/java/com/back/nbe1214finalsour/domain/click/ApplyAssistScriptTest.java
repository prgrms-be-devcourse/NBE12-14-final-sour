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
 * {@code apply-assist.lua}의 초별 학교 지원 수(지원 파티클 연출용)를 실제 Redis(Testcontainers)로 확인한다.
 * 지원 점수·연합·대결 점수는 {@code AssistIntegrationTest}에서 API로 검증한다.
 *
 * <p>초별 키는 지원한 학교(내 학교) 기준이다. {@code rank:univ}·{@code dirty:*}는 공유 키라서 DEL 하지 않고
 * 이 테스트가 넣은 member만 지운다.
 */
@IntegrationTest
class ApplyAssistScriptTest {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> APPLY_ASSIST =
            RedisScript.of(new ClassPathResource("redis/apply-assist.lua"), List.class);

    private static final long UNIVERSITY_ID = 900_501L;
    private static final long TARGET_UNIVERSITY_ID = 900_502L;
    private static final long USER_ID = 900_503L;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redis.opsForZSet().remove(RedisKey.RANK_UNIV.of(), String.valueOf(TARGET_UNIVERSITY_ID));
        redis.opsForSet().remove(RedisKey.DIRTY_UNIV.of(), String.valueOf(TARGET_UNIVERSITY_ID));
        redis.opsForSet().remove(RedisKey.DIRTY_USER.of(), String.valueOf(USER_ID));
        redis.delete(RedisKey.RANK_USER.of(UNIVERSITY_ID));

        // 초별 키는 TTL 5초라 같은 초에 돌아간 다른 테스트의 값이 남는다. 창을 넉넉히 지운다
        long nowSec = Instant.now().getEpochSecond();
        redis.delete(LongStream.rangeClosed(nowSec - 8, nowSec + 2)
                .boxed()
                .flatMap(sec -> Stream.of(
                        RedisKey.ASSISTS_UNIV_SECOND.of(UNIVERSITY_ID, sec),
                        RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, sec),
                        RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, sec)))
                .toList());
    }

    @Test
    @DisplayName("이번 초 학교 지원 수를 clickCount만큼 올리고 5초 만료를 건다")
    void incrementsCurrentSecondAssistsWithTtl() {
        long nowSec = Instant.now().getEpochSecond();
        String currentSecondKey = RedisKey.ASSISTS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec);

        apply(nowSec, 10);
        apply(nowSec, 2);

        assertThat(redis.opsForValue().get(currentSecondKey)).isEqualTo("12");
        assertThat(redis.getExpire(currentSecondKey)).isPositive().isLessThanOrEqualTo(5L);
    }

    @Test
    @DisplayName("지원 수는 내 학교 키에만 쌓이고 대상 학교·클릭·공격 수 키는 건드리지 않는다")
    void countsOnlyMySchoolAssists() {
        long nowSec = Instant.now().getEpochSecond();

        apply(nowSec, 10);

        assertThat(redis.hasKey(RedisKey.ASSISTS_UNIV_SECOND.of(TARGET_UNIVERSITY_ID, nowSec))).isFalse();
        assertThat(redis.hasKey(RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec))).isFalse();
        assertThat(redis.hasKey(RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec))).isFalse();
    }

    @Test
    @DisplayName("직전 1초 학교 지원·클릭·공격 수를 4·5·6번째로 반환하고, 값이 없으면 0을 반환한다")
    void returnsPreviousSecondAssistsClicksAndAttacks() {
        long nowSec = Instant.now().getEpochSecond();

        // 직전 초에 기록이 없을 때
        List<String> empty = apply(nowSec, 10);
        assertThat(empty).hasSize(6);
        assertThat(empty.subList(3, 6)).containsExactly("0", "0", "0");

        // 직전 초에 지원 9회, 클릭 4회, 공격 7회가 기록되어 있을 때 (서로 섞이지 않는지 보려고 값을 모두 다르게 둔다)
        redis.opsForValue().set(RedisKey.ASSISTS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1), "9");
        redis.opsForValue().set(RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1), "4");
        redis.opsForValue().set(RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1), "7");
        assertThat(apply(nowSec, 10).subList(3, 6)).containsExactly("9", "4", "7");
    }

    @Test
    @DisplayName("이번 초에 센 지원 수는 이번 응답의 직전 1초 값에 들어가지 않는다")
    void currentSecondIsNotReportedAsPreviousSecond() {
        long nowSec = Instant.now().getEpochSecond();

        apply(nowSec, 10);
        List<String> result = apply(nowSec, 10);

        assertThat(result.get(3)).isEqualTo("0");
        // 다음 초에 지원하면 이번 초 지원 수가 직전 1초로 보인다
        assertThat(apply(nowSec + 1, 1).get(3)).isEqualTo("20");
    }

    @SuppressWarnings("unchecked")
    private List<String> apply(long nowSec, int clickCount) {
        return redis.execute(
                APPLY_ASSIST,
                List.of(
                        RedisKey.RANK_UNIV.of(),
                        RedisKey.RANK_USER.of(UNIVERSITY_ID),
                        RedisKey.DIRTY_UNIV.of(),
                        RedisKey.DIRTY_USER.of(),
                        // 연합·대결 점수는 AssistIntegrationTest에서 본다. 여기서는 없음(0, "")으로 건너뛴다
                        RedisKey.ALLIANCE_SCORE.of(0),
                        RedisKey.MATCH_SCORE.of(0),
                        RedisKey.DIRTY_ALLIANCE.of(),
                        RedisKey.DIRTY_MATCH.of(),
                        RedisKey.ASSISTS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec),
                        RedisKey.ASSISTS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1),
                        RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1),
                        RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1)),
                String.valueOf(TARGET_UNIVERSITY_ID),
                "3",
                String.valueOf(USER_ID),
                "10",
                "0",
                "",
                "0",
                String.valueOf(clickCount));
    }
}
