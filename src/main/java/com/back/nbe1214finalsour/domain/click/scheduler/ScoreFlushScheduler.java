package com.back.nbe1214finalsour.domain.click.scheduler;

import com.back.nbe1214finalsour.domain.click.service.ScoreFlushService;
import com.back.nbe1214finalsour.domain.click.service.ScoreRestoreService;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true")
public class ScoreFlushScheduler {

    private final ScoreFlushService scoreFlushService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ScoreRestoreService scoreRestoreService;
    private static final RedisScript<Long> RELEASE_LOCK =
            RedisScript.of(new ClassPathResource("redis/release-lock.lua"), Long.class);

    @Scheduled(fixedDelayString = "${scheduling.score-flush-delay}")
    public void run() {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = stringRedisTemplate.opsForValue().
                    setIfAbsent(RedisKey.LOCK_SCORE_FLUSH.of(), token, Duration.ofMinutes(5));
            if (!Boolean.TRUE.equals(acquired)) return;
        } catch (Exception e) {
            log.error("[ScoreFlush] 락 취득 실패", e);
            return;
        }

        try {
            if (!scoreRestoreService.isReady()) {
                scoreRestoreService.restoreIfNeeded();
                return;
            }
            scoreFlushService.flush();
        } catch (Exception e) {
            log.error("[ScoreFlush] 점수 반영 실패, 다음 회차에 재시도", e);
        } finally {
            try {
                // 내가 잡은 락일때만 해제
                stringRedisTemplate.execute(RELEASE_LOCK,
                        List.of(RedisKey.LOCK_SCORE_FLUSH.of()),
                        token);
            } catch (Exception e) {
                log.warn("[ScoreFlush] 락 반납 실패", e);
            }
        }
    }
}
