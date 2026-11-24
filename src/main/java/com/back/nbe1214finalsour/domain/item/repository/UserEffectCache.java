package com.back.nbe1214finalsour.domain.item.repository;

import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

// 클릭 엔진(ClickService)이 배율을 읽는 회원 효과 캐시. 원본 이력은 DB user_item
@Repository
@RequiredArgsConstructor
public class UserEffectCache {

    private final StringRedisTemplate redisTemplate;

    public Optional<UserEffect> find(Long userId) {
        Map<Object, Object> fields = redisTemplate.opsForHash().entries(RedisKey.USER_EFFECT.of(userId));

        Object itemId = fields.get("itemId");
        Object multiplier = fields.get("multiplier");
        Object expiredAt = fields.get("expiredAt");
        if (itemId == null || multiplier == null || expiredAt == null) {
            return Optional.empty();
        }

        return Optional.of(new UserEffect(
                Long.valueOf(itemId.toString()),
                Double.parseDouble(multiplier.toString()),
                Instant.ofEpochMilli(Long.parseLong(expiredAt.toString())).atOffset(ZoneOffset.UTC)
        ));
    }
}
