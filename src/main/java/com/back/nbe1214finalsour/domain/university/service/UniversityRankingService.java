package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UniversityRankingService {

    private final RedisHandler redisHandler;
    private final RedisTemplate<String, Object> redisTemplate;

    public OptionalLong getUniversityRank(Long universityId) {

        String key = RedisKey.RANK_UNIV.of();

        // Redis에서 학교 점수 가져오기
        var score = redisHandler.getRankScore(key, universityId);

        // 학교 점수가 없는 경우
        if (score.isEmpty()) {
            return OptionalLong.empty();
        }

        double currentScore = score.getAsDouble();

        // 자신보다 점수가 높은 학교 수 계산
        Long higherCount = redisTemplate.opsForZSet()
                .count(
                        key,
                        Math.nextUp(currentScore),
                        Double.POSITIVE_INFINITY
                );

        if (higherCount == null) {
            return OptionalLong.empty();
        }

        return OptionalLong.of(higherCount + 1);
    }

    public OptionalDouble getUniversityScore(Long universityId) {
        String key = RedisKey.RANK_UNIV.of();

        return redisHandler.getRankScore(key, universityId);
    }

    public List<RedisHandler.RankEntry> getTopDepartments(Long universityId) {
        String key = RedisKey.RANK_DEPT.of(universityId);

        return redisHandler.getTopRank(key, 3);
    }

    public List<RedisHandler.RankEntry> getTopContributors(Long universityId) {
        String key = RedisKey.RANK_USER.of(universityId);

        return redisHandler.getTopRank(key, 3);
    }

    public List<RedisHandler.RankEntry> getContributorsRange(Long universityId, long start, long count) {
        return redisHandler.getRankRange(RedisKey.RANK_USER.of(universityId), start, count);
    }

    // Redis에서 학교 순위 목록을 구간별로 조회
    public List<RedisHandler.RankEntry> getUniversitiesRange(long start, long count) {
        return redisHandler.getRankRange(
                RedisKey.RANK_UNIV.of(),
                start,
                count
        );
    }

    // Redis 랭킹에 등록된 전체 학교 수 조회
    public long getUniversityCount() {
        Long count = redisTemplate.opsForZSet()
                .zCard(RedisKey.RANK_UNIV.of());

        return count != null ? count : 0L;
    }

    // 1. 특정 학교의 학과 랭킹을 페이지 단위로 조회
    public List<RedisHandler.RankEntry> getDepartmentsRange(
            Long universityId, long start, long count
    ) {
        String key = RedisKey.RANK_DEPT.of(universityId);

        return redisHandler.getRankRange(key, start, count);
    }

    // 2. 특정 학교의 Redis 랭킹에 등록된 학과 수 조회
    public long getDepartmentCount(Long universityId) {
        String key = RedisKey.RANK_DEPT.of(universityId);

        Long count = redisTemplate.opsForZSet().zCard(key);

        return count != null ? count : 0L;
    }

    // 3. 특정 학과의 점수 조회
    public OptionalDouble getDepartmentScore(
            Long universityId, Long departmentId
    ) {
        String key = RedisKey.RANK_DEPT.of(universityId);

        return redisHandler.getRankScore(key, departmentId);
    }

    // 4. 특정 학과의 공동 순위 계산
    public OptionalLong getDepartmentRank(
            Long universityId, Long departmentId
    ) {
        String key = RedisKey.RANK_DEPT.of(universityId);

        var score = redisHandler.getRankScore(key, departmentId);

        if (score.isEmpty()) {
            return OptionalLong.empty();
        }

        Long higherCount = redisTemplate.opsForZSet().count(
                key,
                Math.nextUp(score.getAsDouble()),
                Double.POSITIVE_INFINITY
        );

        if (higherCount == null) {
            return OptionalLong.empty();
        }

        return OptionalLong.of(higherCount + 1);
    }

    // 1. 특정 학교의 개인 랭킹 등록 인원 수 조회
    public long getContributorCount(Long universityId) {
        String key = RedisKey.RANK_USER.of(universityId);

        Long count = redisTemplate.opsForZSet().zCard(key);

        return count != null ? count : 0L;
    }

    // Redis 내 원래 사용자 위치 (0-based, 동점에서도 정렬 위치는 고유)
    public OptionalLong getContributorPosition(Long universityId, Long userId) {
        return redisHandler.getRankPosition(RedisKey.RANK_USER.of(universityId), userId);
    }

    // 2. 특정 사용자의 기여도 점수 조회
    public OptionalDouble getContributorScore(
            Long universityId, Long userId
    ) {
        String key = RedisKey.RANK_USER.of(universityId);

        return redisHandler.getRankScore(key, userId);
    }

    // 3. 특정 사용자의 공동 순위 계산
    public OptionalLong getContributorRank(
            Long universityId, Long userId
    ) {
        String key = RedisKey.RANK_USER.of(universityId);

        var score = redisHandler.getRankScore(key, userId);

        if (score.isEmpty()) {
            return OptionalLong.empty();
        }

        Long higherCount = redisTemplate.opsForZSet().count(
                key,
                Math.nextUp(score.getAsDouble()),
                Double.POSITIVE_INFINITY
        );

        if (higherCount == null) {
            return OptionalLong.empty();
        }

        return OptionalLong.of(higherCount + 1);
    }
}
