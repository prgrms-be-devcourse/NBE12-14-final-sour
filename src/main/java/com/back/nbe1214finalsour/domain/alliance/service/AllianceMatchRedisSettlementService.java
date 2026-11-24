package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AllianceMatchRedisSettlementService {

    private static final RedisScript<Long> SETTLE_SCRIPT =
            RedisScript.of(
                    new ClassPathResource("redis/settle-alliance-match.lua"),
                    Long.class
            );

    private final StringRedisTemplate redisTemplate;
    private final UniversityCache universityCache;

    public boolean settleOnce(
            long matchId,
            long loserA,
            long loserB,
            long winnerA,
            long winnerB,
            long contributionA,
            long contributionB
    ) {
        // 입력값 검증
        if (matchId <= 0 || loserA <= 0 || loserB <= 0
                || winnerA <= 0 || winnerB <= 0) {
            throw new IllegalArgumentException("정산 대상 ID가 올바르지 않습니다.");
        }

        if (new HashSet<>(List.of(loserA, loserB, winnerA, winnerB)).size() != 4) {
            throw new IllegalArgumentException("정산 대상 학교는 서로 달라야 합니다.");
        }

        if (contributionA < 0 || contributionB < 0) {
            throw new IllegalArgumentException("대결 기여도는 음수일 수 없습니다.");
        }

        // Redis Lua에서 점수 차감·지급·중복 여부를 함께 처리
        Long result = redisTemplate.execute(
                SETTLE_SCRIPT,
                List.of(
                        RedisKey.RANK_UNIV.of(),
                        RedisKey.DIRTY_UNIV.of(),
                        "match:" + matchId + ":settled",
                        RedisKey.RANK_UNIV_HIDDEN.of()
                ),
                String.valueOf(loserA),
                String.valueOf(loserB),
                String.valueOf(winnerA),
                String.valueOf(winnerB),
                String.valueOf(contributionA),
                String.valueOf(contributionB),
                // 숨김 학교도 대결에 참여하지만, 점수는 공개 학교 순위에 넣지 않는다 (SCRUM-238)
                hiddenFlag(loserA),
                hiddenFlag(loserB),
                hiddenFlag(winnerA),
                hiddenFlag(winnerB)
        );

        if (result == null) {
            throw new IllegalStateException("Redis 정산 결과를 확인할 수 없습니다.");
        }

        return result == 1L;
    }

    private String hiddenFlag(long universityId) {
        return universityCache.isHidden(universityId) ? "1" : "0";
    }
}
