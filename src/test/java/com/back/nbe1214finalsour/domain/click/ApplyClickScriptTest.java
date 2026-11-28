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
 * {@code apply-click.lua}를 실제 Redis(Testcontainers)에 돌려 확인한다.
 * 단위 테스트({@code ClickServiceTest})는 이 스크립트를 목으로 가리므로 여기서만 검증된다.
 *
 * <p>{@code rank:univ}와 {@code dirty:*}는 여러 팀이 공유하는 키라서 DEL 하지 않고
 * 이 테스트가 넣은 member만 지운다.
 */
@IntegrationTest
class ApplyClickScriptTest {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> APPLY_CLICK =
            RedisScript.of(new ClassPathResource("redis/apply-click.lua"), List.class);

    private static final long UNIVERSITY_ID = 900_101L;
    private static final long OTHER_UNIVERSITY_ID = 900_102L;
    private static final long DEPARTMENT_ID = 900_201L;
    private static final long USER_ID = 900_301L;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redis.opsForZSet().remove(RedisKey.RANK_UNIV.of(), univ(UNIVERSITY_ID), univ(OTHER_UNIVERSITY_ID));
        redis.opsForSet().remove(RedisKey.DIRTY_UNIV.of(), univ(UNIVERSITY_ID));
        redis.opsForSet().remove(RedisKey.DIRTY_DEPT.of(), String.valueOf(DEPARTMENT_ID));
        redis.opsForSet().remove(RedisKey.DIRTY_USER.of(), String.valueOf(USER_ID));
        redis.delete(List.of(
                RedisKey.RANK_DEPT.of(UNIVERSITY_ID),
                RedisKey.RANK_USER.of(UNIVERSITY_ID)));

        // 초별 클릭 키는 TTL 5초라 같은 초에 돌아간 다른 테스트의 값이 남는다. 창을 넉넉히 지운다
        long nowSec = Instant.now().getEpochSecond();
        List<String> secondKeys = LongStream.rangeClosed(nowSec - 8, nowSec + 2)
                .boxed()
                .flatMap(sec -> Stream.of(
                        RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, sec),
                        RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, sec),
                        RedisKey.ASSISTS_UNIV_SECOND.of(UNIVERSITY_ID, sec)))
                .toList();
        redis.delete(secondKeys);
    }

    @Test
    @DisplayName("한 번 실행하면 학교·학과·개인 점수가 각각 인정 점수만큼 올라간다")
    void appliesEarnedScoreToAllThreeZSets() {
        long nowSec = Instant.now().getEpochSecond();

        List<String> result = apply(nowSec, 45, 3);

        // 순위는 공유 키(rank:univ)의 다른 학교에 좌우되므로 여기서는 보지 않는다 (universityRankStartsAtOne에서 검증)
        assertThat(result).hasSize(7);
        assertThat(result.subList(0, 3)).containsExactly("45", "45", "45");
        assertThat(result.get(4)).isEqualTo("0");
        assertThat(univScore()).isEqualTo(45.0);
        assertThat(deptScore()).isEqualTo(45.0);
        assertThat(userScore()).isEqualTo(45.0);
    }

    @Test
    @DisplayName("여러 번 실행하면 점수가 누적된다 (ZINCRBY)")
    void accumulatesScoreAcrossCalls() {
        long nowSec = Instant.now().getEpochSecond();

        apply(nowSec, 45, 3);
        List<String> result = apply(nowSec, 10, 1);

        assertThat(result.subList(0, 3)).containsExactly("55", "55", "55");
        assertThat(univScore()).isEqualTo(55.0);
        assertThat(userScore()).isEqualTo(55.0);
    }

    @Test
    @DisplayName("1분 배치가 읽을 dirty 집합에 학교·학과·회원 ID를 넣는다")
    void marksDirtyTargets() {
        apply(Instant.now().getEpochSecond(), 45, 3);

        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_UNIV.of(), univ(UNIVERSITY_ID))).isTrue();
        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_DEPT.of(), String.valueOf(DEPARTMENT_ID))).isTrue();
        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_USER.of(), String.valueOf(USER_ID))).isTrue();
    }

    @Test
    @DisplayName("member는 따옴표 없는 평문 숫자로 저장된다 (지갑·배치와 호환)")
    void storesMembersAsPlainNumbers() {
        apply(Instant.now().getEpochSecond(), 45, 3);

        assertThat(redis.opsForZSet().range(RedisKey.RANK_USER.of(UNIVERSITY_ID), 0, -1))
                .containsExactly(String.valueOf(USER_ID));
        assertThat(redis.opsForSet().members(RedisKey.DIRTY_DEPT.of()))
                .contains(String.valueOf(DEPARTMENT_ID));
    }

    @Test
    @DisplayName("학교 순위는 점수 높은 순으로 1부터 센다")
    void universityRankStartsAtOne() {
        long nowSec = Instant.now().getEpochSecond();
        // 더 높은 점수의 학교를 먼저 넣어 2위가 되도록 한다
        redis.opsForZSet().add(RedisKey.RANK_UNIV.of(), univ(OTHER_UNIVERSITY_ID), 1_000_000);

        List<String> result = apply(nowSec, 45, 3);

        assertThat(result.get(3)).isEqualTo("2");
    }

    @Test
    @DisplayName("이번 초 클릭 수를 clickCount만큼 올리고 5초 만료를 건다")
    void incrementsCurrentSecondClicksWithTtl() {
        long nowSec = Instant.now().getEpochSecond();
        String currentSecondKey = RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec);

        apply(nowSec, 45, 3);
        apply(nowSec, 45, 2);

        assertThat(redis.opsForValue().get(currentSecondKey)).isEqualTo("5");
        assertThat(redis.getExpire(currentSecondKey)).isPositive().isLessThanOrEqualTo(5L);

    }

    @Test
    @DisplayName("직전 1초 클릭 수를 그대로 반환하고, 값이 없으면 0을 반환한다")
    void returnsPreviousSecondClicks() {
        long nowSec = Instant.now().getEpochSecond();
        String previousSecondKey = RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1);

        // 직전 초에 기록이 없을 때
        assertThat(apply(nowSec, 45, 3).get(4)).isEqualTo("0");

        // 직전 초에 7회가 기록되어 있을 때
        redis.opsForValue().set(previousSecondKey, "7");
        assertThat(apply(nowSec, 45, 3).get(4)).isEqualTo("7");

    }

    @Test
    @DisplayName("직전 1초 학교 공격 수를 6번째로 반환하고, 값이 없으면 0을 반환한다 (클릭 수와 섞이지 않는다)")
    void returnsPreviousSecondAttacks() {
        long nowSec = Instant.now().getEpochSecond();

        // 직전 초에 공격 기록이 없을 때
        assertThat(apply(nowSec, 45, 3).get(5)).isEqualTo("0");

        // 직전 초에 공격 9회, 클릭 7회가 기록되어 있을 때
        redis.opsForValue().set(RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1), "9");
        redis.opsForValue().set(RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1), "7");
        List<String> result = apply(nowSec, 45, 3);

        assertThat(result.subList(4, 6)).containsExactly("7", "9");
    }

    @Test
    @DisplayName("직전 1초 학교 지원 수를 7번째로 반환하고, 값이 없으면 0을 반환한다 (클릭·공격 수와 섞이지 않는다)")
    void returnsPreviousSecondAssists() {
        long nowSec = Instant.now().getEpochSecond();

        assertThat(apply(nowSec, 45, 3).get(6)).isEqualTo("0");

        redis.opsForValue().set(RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1), "7");
        redis.opsForValue().set(RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1), "9");
        redis.opsForValue().set(RedisKey.ASSISTS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1), "5");
        assertThat(apply(nowSec, 45, 3).subList(4, 7)).containsExactly("7", "9", "5");
    }

    @Test
    @DisplayName("클릭은 지원 수를 올리지 않는다")
    void clickDoesNotCountAsAssist() {
        long nowSec = Instant.now().getEpochSecond();

        apply(nowSec, 45, 3);

        assertThat(redis.hasKey(RedisKey.ASSISTS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec))).isFalse();
    }

    @Test
    @DisplayName("클릭은 공격 수를 올리지 않는다")
    void clickDoesNotCountAsAttack() {
        long nowSec = Instant.now().getEpochSecond();

        apply(nowSec, 45, 3);

        assertThat(redis.hasKey(RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec))).isFalse();
    }

    @SuppressWarnings("unchecked")
    private List<String> apply(long nowSec, int earnedScore, int clickCount) {
        return redis.execute(
                APPLY_CLICK,
                List.of(
                        RedisKey.RANK_UNIV.of(),
                        RedisKey.RANK_DEPT.of(UNIVERSITY_ID),
                        RedisKey.RANK_USER.of(UNIVERSITY_ID),
                        RedisKey.DIRTY_UNIV.of(),
                        RedisKey.DIRTY_DEPT.of(),
                        RedisKey.DIRTY_USER.of(),
                        RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec),
                        RedisKey.CLICKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1),
                        // 연합·대결 점수는 ClickIntegrationTest에서 본다. 여기서는 없음(0, "")으로 건너뛴다
                        RedisKey.ALLIANCE_SCORE.of(0),
                        RedisKey.MATCH_SCORE.of(0),
                        RedisKey.DIRTY_ALLIANCE.of(),
                        RedisKey.DIRTY_MATCH.of(),
                        RedisKey.ATTACKS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1),
                        RedisKey.ASSISTS_UNIV_SECOND.of(UNIVERSITY_ID, nowSec - 1)),
                String.valueOf(UNIVERSITY_ID),
                String.valueOf(DEPARTMENT_ID),
                String.valueOf(USER_ID),
                String.valueOf(earnedScore),
                String.valueOf(clickCount),
                "0",
                "");
    }

    private static String univ(long universityId) {
        return String.valueOf(universityId);
    }

    private Double univScore() {
        return redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), univ(UNIVERSITY_ID));
    }

    private Double deptScore() {
        return redis.opsForZSet().score(RedisKey.RANK_DEPT.of(UNIVERSITY_ID), String.valueOf(DEPARTMENT_ID));
    }

    private Double userScore() {
        return redis.opsForZSet().score(RedisKey.RANK_USER.of(UNIVERSITY_ID), String.valueOf(USER_ID));
    }
}
