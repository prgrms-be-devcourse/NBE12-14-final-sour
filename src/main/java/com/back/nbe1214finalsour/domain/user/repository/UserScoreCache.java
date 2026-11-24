package com.back.nbe1214finalsour.domain.user.repository;

import com.back.nbe1214finalsour.global.redis.RedisKey;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 회원 점수의 실시간 값(Redis)을 읽는다. 쓰기는 클릭·공격·클리커 모듈이 한다.
 *
 * <p>DB {@code users.total_contrib_score}·{@code total_attack_score}는 배치가 반영한 값이라 늦을 수 있다.
 * Redis에 아직 값이 없으면(점수를 얻은 적 없는 회원 등) 비어 있는 값을 돌려주고, 호출하는 쪽이 DB 값을 쓴다.
 * {@code RedisContributionWallet}과 같이 {@link StringRedisTemplate}으로 member·field를 문자열 ID로 다룬다.
 */
@Repository
@RequiredArgsConstructor
public class UserScoreCache {

    private final StringRedisTemplate redis;

    /** 기여도: 교내 개인 순위 ZSet({@link RedisKey#RANK_USER})의 score */
    public OptionalLong findContribScore(Long universityId, Long userId) {
        Double score = redis.opsForZSet().score(RedisKey.RANK_USER.of(universityId), String.valueOf(userId));
        return score == null ? OptionalLong.empty() : OptionalLong.of(score.longValue());
    }

    /** 공격 점수: {@link RedisKey#SCORE_USER_ATTACK} Hash의 userId 필드 */
    public OptionalLong findAttackScore(Long userId) {
        Object value = redis.opsForHash().get(RedisKey.SCORE_USER_ATTACK.of(), String.valueOf(userId));
        return value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value.toString()));
    }
}
