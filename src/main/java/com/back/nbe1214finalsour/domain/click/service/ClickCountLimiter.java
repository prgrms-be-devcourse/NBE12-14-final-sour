package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ClickCountLimiter {

    private final StringRedisTemplate stringRedisTemplate;

    private static final RedisScript<Long> INCRBY_WITH_TTL =
            RedisScript.of(new ClassPathResource("redis/incrby-with-ttl.lua"), Long.class);

    private static final int MAX_CLICKS_PER_WINDOW = 900;
    private static final int WINDOW_SECONDS = 60;
    private static final int ABUSE_RANK_SECONDS = 60 * 60 * 72;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    public void check(Long userId, int clickCount) {
        String today = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(DAY);
        Long count = stringRedisTemplate.execute(
                INCRBY_WITH_TTL,
                List.of(RedisKey.RATELIMIT_CLICK.of(userId),
                        RedisKey.RANK_USER_ABUSE.of(today)),
                String.valueOf(WINDOW_SECONDS),
                String.valueOf(clickCount),
                String.valueOf(MAX_CLICKS_PER_WINDOW),
                String.valueOf(ABUSE_RANK_SECONDS),
                String.valueOf(userId));

        // TODO: 부정행위 기록을 NoSQL에 저장.

        if (count != null && count > MAX_CLICKS_PER_WINDOW) {
            throw new BusinessException(ErrorCode.CLICK_COUNT_EXCEEDED);
        }
    }
}
