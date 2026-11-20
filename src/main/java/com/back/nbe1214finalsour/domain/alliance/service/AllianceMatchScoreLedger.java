package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class AllianceMatchScoreLedger {
    private static final RedisScript<List> LEDGER = RedisScript.of(
            new ClassPathResource("redis/read-alliance-match-score.lua"), List.class);
    private final AllianceMatchRepository matches;
    private final StringRedisTemplate redis;

    public AllianceMatchScoreLedger(AllianceMatchRepository matches, StringRedisTemplate redis) {
        this.matches = matches;
        this.redis = redis;
    }

    public enum State { PREPARING, LIVE, FINALIZING, SETTLEMENT_PENDING, FINAL, UNAVAILABLE }
    public record Score(State state, Long aScore, Long bScore) {
        public Score(State state) { this(state, null, null); }
    }

    public Score read(AllianceMatch match, OffsetDateTime now) {
        return switch (match.getStatus()) {
            case FINISHED -> new Score(State.FINAL, match.getAScore(), match.getBScore());
            case IN_PROGRESS -> {
                if (match.getScoreState() == AllianceMatch.ScoreState.READY) {
                    try {
                        yield execute(match, "read");
                    } catch (DataAccessException e) {
                        yield new Score(State.UNAVAILABLE);
                    }
                }
                yield new Score(match.getScoreState() == AllianceMatch.ScoreState.PENDING && now.isBefore(match.getEndedAt())
                        ? State.PREPARING : State.UNAVAILABLE);
            }
            default -> null;
        };
    }

    public boolean finalizeScore(long matchId) {
        return matches.findById(matchId)
                .filter(match -> match.getStatus() == AllianceMatchStatus.IN_PROGRESS
                        && match.getDeletedAt() == null && match.getScoreState() == AllianceMatch.ScoreState.READY)
                .map(match -> execute(match, "finalize").state() == State.SETTLEMENT_PENDING).orElse(false);
    }

    private Score execute(AllianceMatch match, String operation) {
        long id = match.getId();
        List<?> values = redis.execute(LEDGER, List.of(RedisKey.MATCH_SCORE.of(id), RedisKey.MATCH_FINAL_SNAPSHOT.of(id)), operation,
                AllianceMatchScorePreparation.epochMicros(match.getStartedAt()),
                AllianceMatchScorePreparation.epochMicros(match.getEndedAt()),
                match.getAUniversity1Id().toString(), match.getAUniversity2Id().toString(),
                match.getBUniversity1Id().toString(), match.getBUniversity2Id().toString());
        if (values == null || values.isEmpty() || "EARLY".equals(values.getFirst())) return new Score(State.UNAVAILABLE);
        State state = State.valueOf((String) values.getFirst());
        if (values.size() == 3) return new Score(state, Long.valueOf((String) values.get(1)), Long.valueOf((String) values.get(2)));
        return new Score(state);
    }

}
