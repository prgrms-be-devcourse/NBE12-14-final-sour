package com.back.nbe1214finalsour.domain.clicker.repository;

import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;

// 기대 레벨 비교 + 기여도 차감 + 레벨 +1 + dirty 표시를 Lua 한 번으로 처리
// 반환: >=0 잔액, -1 기여도 부족(기록 없음 포함), -2 레벨이 기대값과 다름(키 없음 포함)
@Repository
@RequiredArgsConstructor
public class RedisClickerUpgrader {

    private static final RedisScript<Long> UPGRADE_SCRIPT =
            RedisScript.of(new ClassPathResource("redis/clicker-upgrade.lua"), Long.class);

    private final StringRedisTemplate redisTemplate;

    public long upgrade(Long userId, Long universityId, long expectedLevel, long cost) {
        return redisTemplate.execute(
                UPGRADE_SCRIPT,
                List.of(
                        RedisKey.RANK_USER.of(universityId),
                        RedisKey.DIRTY_USER.of(),
                        RedisKey.USER_CLICKER.of(userId),
                        RedisKey.DIRTY_CLICKER.of()),
                String.valueOf(userId), String.valueOf(cost), String.valueOf(expectedLevel));
    }
}
