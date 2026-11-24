package com.back.nbe1214finalsour.domain.alliance.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.List;

/**
 * 클릭·점수 엔진이 완성한 불변 대결 정산 스냅샷을 읽는 어댑터.
 *
 * 이 클래스는 클릭을 기록하거나 스냅샷을 생성하지 않는다.
 * 모든 점수 필드와 finalized=1을 단일 HSET으로 기록하는 생산자가 따로 필요하다.
 * 생산자 연동 전에는 alliance.settlement.snapshot.redis.enabled를 켜지 말 것.
 */
@Component
@ConditionalOnProperty(
        prefix = "alliance.settlement.snapshot.redis",
        name = "enabled",
        havingValue = "true"
)
public class RedisAllianceMatchSettlementSnapshotProvider
        implements AllianceMatchSettlementSnapshotProvider {

    private static final long MAX_EXACT_REDIS_INTEGER = 9_007_199_254_740_991L;

    private final StringRedisTemplate redisTemplate;

    public RedisAllianceMatchSettlementSnapshotProvider(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public AllianceMatchSettlementSnapshot loadFinalSnapshot(Long matchId) {
        if (matchId == null || matchId <= 0) {
            throw new IllegalArgumentException("올바른 대결 ID가 필요합니다.");
        }
        String key = "match:" + matchId + ":settlement:snapshot";
        Map<Object, Object> fields = redisTemplate.opsForHash().entries(key);

        if (fields == null || !"1".equals(fields.get("finalized"))) {
            throw new IllegalStateException("최종 확정된 대결 정산 스냅샷이 없습니다: " + matchId);
        }

        long aScore = nonnegative(fields, "aScore");
        long bScore = nonnegative(fields, "bScore");
        long a1 = positive(fields, "aUniversity1Id");
        long a2 = positive(fields, "aUniversity2Id");
        long b1 = positive(fields, "bUniversity1Id");
        long b2 = positive(fields, "bUniversity2Id");
        if (new HashSet<>(List.of(a1, a2, b1, b2)).size() != 4) {
            throw new IllegalStateException("정산 스냅샷에 중복 학교가 있습니다: " + matchId);
        }

        return new AllianceMatchSettlementSnapshot(
                aScore, bScore, a1, a2, b1, b2,
                nonnegative(fields, "aUniversity1Contribution"),
                nonnegative(fields, "aUniversity2Contribution"),
                nonnegative(fields, "bUniversity1Contribution"),
                nonnegative(fields, "bUniversity2Contribution")
        );
    }

    private static long positive(Map<Object, Object> fields, String field) {
        long result = nonnegative(fields, field);
        if (result == 0) {
            throw new IllegalStateException("스냅샷의 학교 ID가 올바르지 않습니다: " + field);
        }
        return result;
    }

    private static long nonnegative(Map<Object, Object> fields, String field) {
        Object value = fields.get(field);
        if (!(value instanceof String text)) {
            throw new IllegalStateException("스냅샷 필드가 없거나 문자열이 아닙니다: " + field);
        }
        try {
            long parsed = Long.parseLong(text);
            if (parsed < 0 || parsed > MAX_EXACT_REDIS_INTEGER) {
                throw new IllegalStateException("스냅샷 숫자가 허용 범위를 벗어났습니다: " + field);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("스냅샷 숫자 형식이 잘못됐습니다: " + field, e);
        }
    }
}
