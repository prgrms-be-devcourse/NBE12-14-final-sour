package com.back.nbe1214finalsour.domain.item.repository;

import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

// 클릭 엔진(ClickService)이 배율을 읽는 회원 효과 캐시. 원본 이력은 DB user_item
@Repository
@RequiredArgsConstructor
public class UserEffectCache {

    private static final RedisScript<Long> APPLY_SCRIPT =
            RedisScript.of(new ClassPathResource("redis/user-effect-apply.lua"), Long.class);

    private final StringRedisTemplate redisTemplate;

    // 기존 효과를 지우고 새 효과를 저장, expiredAt 시각에 키가 만료된다
    public void apply(Long userId, Long itemId, double multiplier, OffsetDateTime expiredAt) {
        redisTemplate.execute(
                APPLY_SCRIPT,
                List.of(RedisKey.USER_EFFECT.of(userId)),
                String.valueOf(itemId),
                String.valueOf(multiplier),
                String.valueOf(expiredAt.toInstant().toEpochMilli())
        );
    }
}
