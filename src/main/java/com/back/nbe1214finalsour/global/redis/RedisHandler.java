package com.back.nbe1214finalsour.global.redis;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisHashCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RedisHandler {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    // String 설정
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    // String TTL 설정
    public void setTtl(String key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    // Hash 설정
    public void setHash(String key, String hashKey, Object value) {
        redisTemplate.opsForHash().put(key, hashKey, value);
    }

    // Hash TTL 설정, 이미 데이터가 저장되어 있어야 함
    public void setHashFieldTtl(String key, Map<String, Object> hashKeyValue, Duration ttl) {
        redisTemplate.opsForHash().putAndExpire(
                key, hashKeyValue, RedisHashCommands.HashFieldSetOption.UPSERT, Expiration.from(ttl)
        );
    }

    // String 값 조회
    public <T> Optional<T> get(String key, Class<T> type) {
        Object object = redisTemplate.opsForValue().get(key);
        return Optional.ofNullable(object).map(o -> objectMapper.convertValue(o, type));
    }

    // Hash 값 조회
    public <T> Optional<T> getHash(String key, String hashKey, Class<T> type) {
        Object object = redisTemplate.opsForHash().get(key, hashKey);
        return Optional.ofNullable(object).map(o -> objectMapper.convertValue(o, type));
    }

    // 값이 없으면 0부터 시작, +1 증가
    public long increment(String key) {
        Long value = redisTemplate.opsForValue().increment(key);
        return value != null ? value : 0L;
    }

    // increment + 유효 기한 설정
    public long incrementWithTtl(String key, Duration ttl) {
        long value = increment(key);
        if (value == 1L) {
            redisTemplate.expire(key, ttl);
        }
        return value;
    }

    // hash 값 증가, delta -> 증가할 값
    public long incrementHash(String key, String hashKey, long delta) {
        Long value = redisTemplate.opsForHash().increment(key, hashKey, delta);
        return value != null ? value : 0L;
    }

    // SET NX(값이 없으면) + TTL
    // Increment 대상으로 쓰면 안됩니다.
    public boolean setIfAbsent(String key, Object value, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, value, ttl));
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    public boolean expire(String key, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.expire(key, ttl));
    }

    // 점수 설정(ZADD)
    public void addRank(String key, long member, double score) {
        redisTemplate.opsForZSet().add(key, member, score);
    }

    // 점수 누적(ZINCRBY)
    public double incrementRankScore(String key, long member, double delta) {
        Double score = redisTemplate.opsForZSet().incrementScore(key, member, delta);
        return score != null ? score : 0.0;
    }

    // 점수 조회
    public OptionalDouble getRankScore(String key, long member) {
        Double score = redisTemplate.opsForZSet().score(key, member);
        return score != null ? OptionalDouble.of(score) : OptionalDouble.empty();
    }

    // 순위 조회(0부터, 점수 높은 순)
    public OptionalLong getRankPosition(String key, long member) {
        Long rank = redisTemplate.opsForZSet().reverseRank(key, member);
        return rank != null ? OptionalLong.of(rank) : OptionalLong.empty();
    }

    // 상위 count개 조회(점수 높은 순)
    public List<RankEntry> getTopRank(String key, long count) {
        if (count <= 0) {
            return List.of();
        }

        Set<TypedTuple<Object>> tuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, count - 1);
        if (tuples == null) {
            return List.of();
        }

        return tuples.stream()
                .map(tuple -> new RankEntry(
                        toMemberId(tuple.getValue()),
                        tuple.getScore() != null ? tuple.getScore() : 0.0
                ))
                .toList();
    }

    // 상위 순위에서 특정 구간 조회(0부터, 점수 높은 순). 제외 회원 보충에 사용.
    public List<RankEntry> getRankRange(String key, long start, long count) {
        if (start < 0 || count <= 0) {
            return List.of();
        }
        Set<TypedTuple<Object>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(key, start, start + count - 1);
        if (tuples == null) {
            return List.of();
        }
        return tuples.stream()
                .map(tuple -> new RankEntry(
                        toMemberId(tuple.getValue()),
                        tuple.getScore() != null ? tuple.getScore() : 0.0
                ))
                .toList();
    }

    // 멤버 제거(ZREM)
    public boolean removeRank(String key, long member) {
        Long removed = redisTemplate.opsForZSet().remove(key, member);
        return removed != null && removed > 0L;
    }

    // 변경 ID 추가(SADD)
    public boolean addDirty(String key, long member) {
        Long added = redisTemplate.opsForSet().add(key, member);
        return added != null && added > 0L;
    }

    // 변경 ID 전체 조회(SMEMBERS)
    public List<Long> getDirtyMembers(String key) {
        Set<Object> members = redisTemplate.opsForSet().members(key);
        if (members == null) {
            return List.of();
        }

        return members.stream().map(RedisHandler::toMemberId).toList();
    }

    // 변경 ID 제거(SREM)
    public boolean removeDirty(String key, long member) {
        Long removed = redisTemplate.opsForSet().remove(key, member);
        return removed != null && removed > 0L;
    }

    // 평문 숫자로 저장된 member만 허용
    private static long toMemberId(Object member) {
        if (member instanceof Number number) {
            return number.longValue();
        }
        throw new BusinessException(
                ErrorCode.COMMON_REDIS_MEMBER_NOT_NUMERIC,
                "member가 평문 숫자가 아닙니다. 다른 직렬화 방식으로 쓴 값이 섞였습니다. 값: " + member
        );
    }

    public record RankEntry(long member, double score) {}
}
