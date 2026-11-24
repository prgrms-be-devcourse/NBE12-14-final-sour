package com.back.nbe1214finalsour.global.redis;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 Redis를 사용해 기여자 보충 조회의 member 직렬화 호환성을 검증한다. */
@IntegrationTest
class RedisRankRangeCompatibilityIntegrationTest {

    // 다른 테스트의 공유 rank:* 키를 지우지 않도록 전용 키를 사용한다.
    private static final String RANK_KEY = "test:university:rank-range:compatibility";

    @Autowired
    private RedisHandler redisHandler;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanUp() {
        stringRedisTemplate.delete(RANK_KEY);
    }

    @Test
    @DisplayName("RedisHandler의 Long member는 평문 숫자로 저장되고 다음 순위 구간을 읽을 수 있다")
    void longMembersCanBeReadByRankRange() {
        redisHandler.addRank(RANK_KEY, 101L, 30.0);
        redisHandler.addRank(RANK_KEY, 102L, 20.0);
        redisHandler.addRank(RANK_KEY, 103L, 10.0);

        assertThat(stringRedisTemplate.opsForZSet().score(RANK_KEY, "102")).isEqualTo(20.0);
        assertThat(redisHandler.getRankRange(RANK_KEY, 1, 2))
                .containsExactly(
                        new RedisHandler.RankEntry(102L, 20.0),
                        new RedisHandler.RankEntry(103L, 10.0)
                );
    }

    @Test
    @DisplayName("StringRedisTemplate의 평문 숫자 member를 RedisHandler가 순위 구간에서 읽는다")
    void plainMembersFromStringTemplateCanBeReadByRankRange() {
        stringRedisTemplate.opsForZSet().add(RANK_KEY, "201", 30.0);
        stringRedisTemplate.opsForZSet().add(RANK_KEY, "202", 20.0);

        assertThat(redisHandler.getRankRange(RANK_KEY, 0, 2))
                .containsExactly(
                        new RedisHandler.RankEntry(201L, 30.0),
                        new RedisHandler.RankEntry(202L, 20.0)
                );
    }

    @Test
    @DisplayName("JSON 문자열 member가 섞이면 순위 구간 조회에서 명시적으로 실패한다")
    void quotedStringMemberIsRejectedByRankRange() {
        redisTemplate.opsForZSet().add(RANK_KEY, "301", 10.0);

        assertThat(stringRedisTemplate.opsForZSet().range(RANK_KEY, 0, -1))
                .containsExactly("\"301\"");
        assertThatThrownBy(() -> redisHandler.getRankRange(RANK_KEY, 0, 1))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COMMON_REDIS_MEMBER_NOT_NUMERIC);
    }
}
