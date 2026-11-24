package com.back.nbe1214finalsour.domain.click;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.click.scheduler.ScoreFlushScheduler;
import com.back.nbe1214finalsour.domain.click.service.ScoreFlushService;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 서버가 여러 대일 때 1분 배치를 한 서버만 돌리는 Redis 락을 확인한다.
 *
 * <p>테스트 설정에서는 스케줄러 빈이 꺼져 있으므로({@code scheduling.enabled=false}) 직접 만들어 {@code run()}을 부른다.
 */
@IntegrationTest
class ScoreFlushSchedulerIntegrationTest {

    private static final String LOCK_KEY = RedisKey.LOCK_SCORE_FLUSH.of();
    private static final String DIRTY_KEY = RedisKey.DIRTY_UNIV.of();
    /** DB에 없는 학교 ID. Redis 점수도 없으므로 배치는 DB를 건드리지 않고 dirty만 비운다 */
    private static final String UNKNOWN_UNIV_ID = "999999999";

    @Autowired
    private ScoreFlushService scoreFlushService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanUpRedis() {
        stringRedisTemplate.delete(List.of(LOCK_KEY, DIRTY_KEY, DIRTY_KEY + ":flushing"));
    }

    @Test
    @DisplayName("다른 서버가 락을 잡고 있으면 배치를 건너뛰고 그 락도 건드리지 않는다")
    void skipsWhenAnotherServerHoldsLock() {
        // given
        stringRedisTemplate.opsForValue().set(LOCK_KEY, "other-server", Duration.ofMinutes(1));
        stringRedisTemplate.opsForSet().add(DIRTY_KEY, UNKNOWN_UNIV_ID);

        // when
        new ScoreFlushScheduler(scoreFlushService, stringRedisTemplate).run();

        // then
        assertThat(stringRedisTemplate.opsForSet().members(DIRTY_KEY)).containsExactly(UNKNOWN_UNIV_ID);
        assertThat(stringRedisTemplate.opsForValue().get(LOCK_KEY)).isEqualTo("other-server");
    }

    @Test
    @DisplayName("락이 비어 있으면 배치를 돌리고 끝나면 락을 푼다")
    void runsFlushAndReleasesLock() {
        // given
        stringRedisTemplate.opsForSet().add(DIRTY_KEY, UNKNOWN_UNIV_ID);

        // when
        new ScoreFlushScheduler(scoreFlushService, stringRedisTemplate).run();

        // then
        assertThat(stringRedisTemplate.hasKey(DIRTY_KEY)).isFalse();
        assertThat(stringRedisTemplate.hasKey(LOCK_KEY)).isFalse();
    }

    @Test
    @DisplayName("배치가 실패해도 락을 풀어 다음 회차가 돌 수 있게 한다")
    void releasesLockWhenFlushFails() {
        // given
        ScoreFlushService failingService = mock(ScoreFlushService.class);
        doThrow(new IllegalStateException("DB 장애")).when(failingService).flush();

        // when
        new ScoreFlushScheduler(failingService, stringRedisTemplate).run();

        // then
        assertThat(stringRedisTemplate.hasKey(LOCK_KEY)).isFalse();
    }

    @Test
    @DisplayName("배치 중에 내 락이 만료되어 다른 서버가 잡았다면 그 서버의 락은 풀지 않는다")
    void doesNotReleaseLockTakenOverByAnotherServer() {
        // given: 배치가 TTL보다 오래 걸려 내 락이 만료되고, 그사이 다른 서버가 락을 잡은 상황
        ScoreFlushService slowService = mock(ScoreFlushService.class);
        doAnswer(invocation -> {
            stringRedisTemplate.opsForValue().set(LOCK_KEY, "other-server", Duration.ofMinutes(1));
            return null;
        }).when(slowService).flush();

        // when
        new ScoreFlushScheduler(slowService, stringRedisTemplate).run();

        // then
        assertThat(stringRedisTemplate.opsForValue().get(LOCK_KEY)).isEqualTo("other-server");
    }
}
