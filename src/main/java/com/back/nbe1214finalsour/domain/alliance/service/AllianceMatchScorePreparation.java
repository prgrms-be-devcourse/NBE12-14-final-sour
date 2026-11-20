package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "alliance.scoring", name = "enabled", havingValue = "true")
public class AllianceMatchScorePreparation {
    private static final RedisScript<Long> PREPARE = RedisScript.of(
            new ClassPathResource("redis/prepare-alliance-match-score.lua"), Long.class);

    private final AllianceMatchRepository matches;
    private final StringRedisTemplate redis;

    public AllianceMatchScorePreparation(AllianceMatchRepository matches, StringRedisTemplate redis) {
        this.matches = matches;
        this.redis = redis;
    }

    @Transactional
    public boolean prepare(long matchId) {
        AllianceMatch match = matches.findByIdForUpdate(matchId).orElse(null);
        if (match == null || match.getDeletedAt() != null || match.getStatus() != AllianceMatchStatus.IN_PROGRESS
                || match.getScoreState() != AllianceMatch.ScoreState.PENDING) return false;

        // PENDING has never accepted a submission. If Redis succeeds but the DB commit fails,
        // retry validates the same hash. READY is never initialized again, even after Redis loss.
        Long prepared = redis.execute(PREPARE, List.of(RedisKey.MATCH_SCORE.of(matchId)),
                epochMicros(match.getStartedAt()), epochMicros(match.getEndedAt()),
                match.getAUniversity1Id().toString(), match.getAUniversity2Id().toString(),
                match.getBUniversity1Id().toString(), match.getBUniversity2Id().toString());
        if (Long.valueOf(1L).equals(prepared)) {
            match.markScoreReady();
            return true;
        }
        return false;
    }

    static String epochMicros(OffsetDateTime time) {
        return Long.toString(time.toEpochSecond() * 1_000_000L + time.getNano() / 1_000);
    }
}
