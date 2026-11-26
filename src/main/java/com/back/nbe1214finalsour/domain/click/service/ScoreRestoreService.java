package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreRestoreService {

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;

    public boolean isReady() {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKey.REDIS_READY.of()));
    }

    public void restoreIfNeeded() {
        if (isReady()) return;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKey.RANK_UNIV.of()))) {
            log.warn("[ScoreRestore] 표시 키가 없지만 점수가 있음. 복구 없이 표시만 남김");
        } else {
            log.error("[ScoreRestore] Redis 점수가 비어 있음. DB에서 복구 시작");
            restore();
            log.warn("[ScoreRestore] 복구 완료");
        }
        stringRedisTemplate.opsForValue().set(RedisKey.REDIS_READY.of(), OffsetDateTime.now().toString());
    }

    private void restore() {
        // 학교 점수
        Map<Boolean, Set<ZSetOperations.TypedTuple<String>>> univs = new HashMap<>();
        jdbcTemplate.query("select id, total_score, hidden from university", row -> {
                    univs.computeIfAbsent(row.getBoolean("hidden"), k -> new HashSet<>())
                            .add(ZSetOperations.TypedTuple.of(
                                    row.getString("id"),
                                    (double) row.getLong("total_score"))
                            );
                }
        );
        zadd(RedisKey.RANK_UNIV_HIDDEN.of(), univs.get(true));
        zadd(RedisKey.RANK_UNIV.of(), univs.get(false));

        // 학과 점수
        Map<Long, Set<ZSetOperations.TypedTuple<String>>> depts = new HashMap<>();
        jdbcTemplate.query("select id, university_id, total_score from department", row -> {
            depts.computeIfAbsent(row.getLong("university_id"), k -> new HashSet<>())
                    .add(ZSetOperations.TypedTuple.of(
                            row.getString("id"),
                            (double) row.getLong("total_score"))
                    );
        });
        depts.forEach((univId, tuples) -> zadd(RedisKey.RANK_DEPT.of(univId), tuples));

        // 개인 기여도
        Map<Long, Set<ZSetOperations.TypedTuple<String>>> users = new HashMap<>();
        Map<String, String> attacks = new HashMap<>();
        jdbcTemplate.query("select id, university_id, total_contrib_score, total_attack_score from users where deleted_at is null", row -> {
            String id = row.getString("id");
            users.computeIfAbsent(row.getLong("university_id"), k -> new HashSet<>())
                    .add(ZSetOperations.TypedTuple.of(
                            id, (double) row.getLong("total_contrib_score")
                    ));
            long attack = row.getLong("total_attack_score");
            if (attack > 0) {
                attacks.put(id, String.valueOf(attack));
            }
        });
        users.forEach((univId, tuples) -> zadd(RedisKey.RANK_USER.of(univId), tuples));
        if (!attacks.isEmpty()) {
            stringRedisTemplate.opsForHash().putAll(RedisKey.SCORE_USER_ATTACK.of(), attacks);
        }

        // 연합 점수
        Map<Long, Map<String, String>> allianceScores = new HashMap<>();
        jdbcTemplate.query("""
                select au.alliance_id, au.university_id, au.total_score 
                from alliance_university au join alliance a on a.id = au.alliance_id
                where a.status in ('ACTIVE', 'IN_MATCH')
                    and a.deleted_at is null
                    and au.status = 'ACCEPTED'
                    and au.deleted_at is null
                """, row -> {
            allianceScores.computeIfAbsent(row.getLong("alliance_id"), k -> new HashMap<>())
                            .put(row.getString("university_id"), String.valueOf(row.getLong("total_score")));
        });
        allianceScores.forEach((allianceId, byUniv) ->
                stringRedisTemplate.opsForHash().putAll(RedisKey.ALLIANCE_SCORE.of(allianceId), byUniv));

        // 대결 점수
        jdbcTemplate.query("select id, a_score, b_score from alliance_match where status = 'IN_PROGRESS' and deleted_at is null", row -> {
            stringRedisTemplate.opsForHash().putAll(RedisKey.MATCH_SCORE.of(row.getLong("id")),
                    Map.of("A", String.valueOf(row.getLong("a_score")),
                            "B", String.valueOf(row.getLong("b_score"))));
        });

        // 물약 효과
        List<Effect> effects = jdbcTemplate.query("""
                select distinct on (ui.user_id) ui.id, ui.user_id, ui.item_id, i.multiplier, ui.expired_at
                from user_item ui join item i on i.id = ui.item_id
                where i.type = 'MULTIPLIER' and ui.expired_at > now()
                order by ui.user_id, ui.id desc
                """, (row, n) -> new Effect(
                row.getLong("user_id"),
                row.getString("item_id"),
                row.getDouble("multiplier"),
                row.getObject("expired_at", OffsetDateTime.class).toInstant().toEpochMilli(),
                row.getString("id")));

        stringRedisTemplate.executePipelined(new SessionCallback<Object>()
            {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (Effect effect : effects) {
                        String key = RedisKey.USER_EFFECT.of(effect.userId);
                        operations.opsForHash().putAll(key,
                                Map.of(
                                        "itemId", effect.itemId,
                                        "multiplier", String.valueOf(effect.multiplier),
                                        "expiredAt", String.valueOf(effect.expiredAt),
                                        "purchaseId", effect.purchaseId
                                ));
                        operations.expireAt(key, Instant.ofEpochMilli(effect.expiredAt));
                    }
                    return null;
                }
        });
    }

    record Effect(long userId, String itemId, double multiplier, long expiredAt, String purchaseId) {}

    private void zadd(String key, Set<ZSetOperations.TypedTuple<String>> tuples) {
        if (tuples != null && !tuples.isEmpty()) {
            stringRedisTemplate.opsForZSet().add(key, tuples);
        }
    }
}
