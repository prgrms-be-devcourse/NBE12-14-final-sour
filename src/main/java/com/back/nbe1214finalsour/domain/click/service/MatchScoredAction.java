package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchScoreRouting;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchScoreRouting.Route;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

@Component
@ConditionalOnProperty(prefix = "alliance.scoring", name = "enabled", havingValue = "true")
public class MatchScoredAction {
    private static final RedisScript<List> CLICK = script("click");
    private static final RedisScript<List> ASSIST = script("assist");
    private final ClickActionGuard guard;
    private final AllianceMatchScoreRouting routing;
    private final StringRedisTemplate redis;

    public MatchScoredAction(ClickActionGuard guard, AllianceMatchScoreRouting routing, StringRedisTemplate redis) {
        this.guard = guard;
        this.routing = routing;
        this.redis = redis;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public <T> T apply(long userId, long schoolId, String submissionId, int count,
                       BiFunction<Long, Attempt, T> action) {
        List<Route> routes = routing.routesForSubmission(schoolId);
        if (routes.size() > 1) throw new BusinessException(ErrorCode.MATCH_SCORE_NOT_READY);
        String claim = "claim:" + UUID.randomUUID();
        long base = guard.verifyAndGetBaseScore(userId, submissionId, count, claim);
        return action.apply(base, new Attempt(RedisKey.SUBMIT_DEDUPE.of(userId, submissionId), claim,
                routes.isEmpty() ? null : routes.getFirst()));
    }

    record Attempt(String key, String claim, Route route) {}

    List<String> execute(boolean click, Attempt attempt, List<String> keys, String... args) {
        List<String> allKeys = new ArrayList<>(keys);
        allKeys.add(attempt.key());
        Route route = attempt.route();
        if (route != null) allKeys.add(RedisKey.MATCH_SCORE.of(route.matchId()));
        List<String> allArgs = new ArrayList<>(List.of(args));
        allArgs.add(attempt.claim());
        if (route != null) {
            allArgs.add(epochMicros(route.startedAt()));
            allArgs.add(epochMicros(route.endedAt()));
            allArgs.add(route.ready() ? "1" : "0");
            allArgs.addAll(route.roster());
        }
        @SuppressWarnings("unchecked")
        List<String> result = redis.execute(click ? CLICK : ASSIST, allKeys, allArgs.toArray(String[]::new));
        if (result == null || result.isEmpty()) throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR);
        return switch (result.getFirst()) {
            case "DUPLICATE" -> throw new BusinessException(ErrorCode.CLICK_DUPLICATE_SUBMISSION);
            case "NOT_READY" -> throw new BusinessException(ErrorCode.MATCH_SCORE_NOT_READY);
            case "INVALID_SCORES" -> throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR);
            default -> result;
        };
    }

    private static String epochMicros(OffsetDateTime time) {
        return Long.toString(time.toEpochSecond() * 1_000_000L + time.getNano() / 1_000);
    }

    private static RedisScript<List> script(String kind) {
        try {
            return RedisScript.of("local isClick = " + kind.equals("click") + "\nlocal function applyOrdinary()\n"
                    + new ClassPathResource("redis/apply-" + kind + ".lua").getContentAsString(StandardCharsets.UTF_8)
                    + "\nend\n" + new ClassPathResource("redis/apply-alliance-match-score.lua")
                    .getContentAsString(StandardCharsets.UTF_8), List.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
