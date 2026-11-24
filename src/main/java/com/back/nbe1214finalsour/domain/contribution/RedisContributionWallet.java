package com.back.nbe1214finalsour.domain.contribution;

import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisContributionWallet implements ContributionWallet {

    private final StringRedisTemplate redisTemplate;

    @Override
    public long findBalance(Long userId, Long universityId) {
        Double score = redisTemplate.opsForZSet()
                .score(RedisKey.RANK_USER.of(universityId), String.valueOf(userId));
        return score == null ? 0L : score.longValue();
    }
}
