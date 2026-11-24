package com.back.nbe1214finalsour.domain.clicker.repository;

import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

// 클릭 엔진이 클릭 세기를 계산할 때 읽는 회원 레벨 (원본은 Redis, DB users.clicker_id는 배치 반영)
@Repository
@RequiredArgsConstructor
public class UserClickerLevelCache {

    private final StringRedisTemplate redisTemplate;
    private final UserClickerLevelStore userClickerLevelStore;

    // 키가 없으면 DB 레벨로 채운다 (SET NX)
    public long loadIfAbsent(Long userId) {
        String key = RedisKey.USER_CLICKER.of(userId);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return Long.parseLong(cached);
        }
        long dbLevel = userClickerLevelStore.findLevel(userId);
        if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(dbLevel)))) {
            return dbLevel;
        }
        return Long.parseLong(redisTemplate.opsForValue().get(key));
    }

    // 읽기 전용 조회. 키가 없어도 채우지 않는다
    public Optional<Long> find(Long userId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(RedisKey.USER_CLICKER.of(userId)))
                .map(Long::valueOf);
    }
}
