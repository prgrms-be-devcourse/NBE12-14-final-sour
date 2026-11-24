package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceMatchRedisSettlementService;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AllianceMatchRedisSettlementIntegrationTest {

    @Autowired
    private AllianceMatchRedisSettlementService settlementService;

    @Autowired
    private StringRedisTemplate redis;

    private final List<String> usedUniversityIds = new ArrayList<>();
    private Long matchId;

    @Test
    @DisplayName("같은 대결의 보상 정산은 한 번만 적용한다")
    void settleOnce_preventsDuplicateReward() {
        // given: 테스트 전용 학교 ID
        long base = ThreadLocalRandom.current()
                .nextLong(1_000_000_000L, 2_000_000_000L);

        matchId = base;

        long loserA = base + 1;
        long loserB = base + 2;
        long winnerA = base + 3;
        long winnerB = base + 4;

        // given: 학교별 현재 점수
        setScore(loserA, 70);
        setScore(loserB, 500);
        setScore(winnerA, 5);
        setScore(winnerB, 10);

        // when: 첫 번째 정산
        boolean first = settlementService.settleOnce(
                matchId,
                loserA,
                loserB,
                winnerA,
                winnerB,
                100L,
                200L
        );

        // when: 동일한 대결을 다시 정산
        boolean second = settlementService.settleOnce(
                matchId,
                loserA,
                loserB,
                winnerA,
                winnerB,
                100L,
                200L
        );

        // then: 첫 번째만 실제 점수 반영
        assertThat(first).isTrue();
        assertThat(second).isFalse();

        // then: 점수가 중복 차감되거나 지급되지 않음
        assertThat(scoreOf(loserA)).isEqualTo(0.0);
        assertThat(scoreOf(loserB)).isEqualTo(300.0);
        assertThat(scoreOf(winnerA)).isEqualTo(140.0);
        assertThat(scoreOf(winnerB)).isEqualTo(145.0);
    }

    private void setScore(long universityId, double score) {
        String id = String.valueOf(universityId);
        usedUniversityIds.add(id);

        redis.opsForZSet().add(
                RedisKey.RANK_UNIV.of(), id, score
        );
    }

    private Double scoreOf(long universityId) {
        return redis.opsForZSet().score(
                RedisKey.RANK_UNIV.of(),
                String.valueOf(universityId)
        );
    }

    @AfterEach
    void cleanUp() {
        for (String id : usedUniversityIds) {
            redis.opsForZSet().remove(RedisKey.RANK_UNIV.of(), id);
            redis.opsForSet().remove(RedisKey.DIRTY_UNIV.of(), id);
        }

        if (matchId != null) {
            redis.delete("match:" + matchId + ":settled");
        }
    }

    @Test
    @DisplayName("동일한 대결을 동시에 정산해도 보상은 한 번만 지급된다")
    void settleOnce_concurrentRequestsApplyOnlyOnce() throws Exception {
        // given: 테스트 전용 학교 ID
        long base = ThreadLocalRandom.current()
                .nextLong(1_000_000_000L, 2_000_000_000L);

        matchId = base;

        long loserA = base + 1;
        long loserB = base + 2;
        long winnerA = base + 3;
        long winnerB = base + 4;

        setScore(loserA, 70);
        setScore(loserB, 500);
        setScore(winnerA, 5);
        setScore(winnerB, 10);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Callable<Boolean> task = () -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 정산 시작 실패");
                }

                return settlementService.settleOnce(
                        matchId,
                        loserA,
                        loserB,
                        winnerA,
                        winnerB,
                        100L,
                        200L
                );
            };

            // when: 동일한 대결을 두 스레드에서 동시에 정산
            Future<Boolean> first = executor.submit(task);
            Future<Boolean> second = executor.submit(task);

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successCount =
                    (first.get(30, TimeUnit.SECONDS) ? 1 : 0)
                            + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);

            // then: 한 번만 정산 성공
            assertThat(successCount).isEqualTo(1);

            // then: 보상은 한 번만 반영
            assertThat(scoreOf(loserA)).isEqualTo(0.0);
            assertThat(scoreOf(loserB)).isEqualTo(300.0);
            assertThat(scoreOf(winnerA)).isEqualTo(140.0);
            assertThat(scoreOf(winnerB)).isEqualTo(145.0);

        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                    .isTrue();
        }
    }
}