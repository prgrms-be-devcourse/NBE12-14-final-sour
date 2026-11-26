package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.time.ClockConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 순위 변동의 기준이 되는 순위 복사본 (SCRUM-184, 계획 R1–R8).
 *
 * <p>매일 00:00 KST에 학교·학과·개인 순위 ZSET을 그대로 복사하고 날짜를 기록한다. 변동은 "복사본에서의 순위 − 지금 순위"이고,
 * 양수가 상승이다. 기록된 날짜가 오늘이 아니면(자정 실행을 놓친 날) 이틀 전 기준을 "어제 대비"로 보이지 않도록 변동을 계산하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankSnapshotService {

    // 복사본은 매일 덮어쓴다. 사라진 학교의 복사본이 남지 않도록 이틀 뒤 지운다
    static final Duration SNAPSHOT_TTL = Duration.ofDays(2);
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);
    private static final RedisScript<Long> RELEASE_LOCK =
            RedisScript.of(new ClassPathResource("redis/release-lock.lua"), Long.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final UniversityRepository universityRepository;
    private final Clock clock;

    /**
     * 오늘(KST) 복사본이 없으면 지금 순위를 복사하고 오늘 날짜를 기록한다. 자정 실행과 서버 시작 보충이 함께 쓴다.
     * 다른 서버가 만드는 중이거나 이미 만들었으면 false. 락 안에서 날짜를 다시 확인해 하루에 한 번만 만든다.
     * 원본 키가 없으면 ZUNIONSTORE가 복사본을 지우므로, 점수가 없는 학교에는 이전 순위도 없다.
     */
    public boolean snapshotIfMissing() {
        if (isTodaySnapshot()) {
            return false;
        }
        String lockKey = RedisKey.LOCK_RANK_SNAPSHOT.of();
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(this.stringRedisTemplate.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL))) {
            return false;
        }
        try {
            if (isTodaySnapshot()) {
                return false;
            }
            copy(RedisKey.RANK_UNIV.of(), RedisKey.RANK_UNIV_SNAPSHOT.of());
            for (Long universityId : this.universityRepository.findAllIds()) {
                copy(RedisKey.RANK_DEPT.of(universityId), RedisKey.RANK_DEPT_SNAPSHOT.of(universityId));
                copy(RedisKey.RANK_USER.of(universityId), RedisKey.RANK_USER_SNAPSHOT.of(universityId));
            }
            // 날짜는 모두 복사한 뒤에 쓴다. 중간에 실패하면 어제 날짜가 남아 변동을 숨긴다
            this.stringRedisTemplate.opsForValue().set(RedisKey.RANK_SNAPSHOT_DATE.of(), today().toString(), SNAPSHOT_TTL);
            return true;
        } finally {
            try {
                this.stringRedisTemplate.execute(RELEASE_LOCK, List.of(lockKey), token);
            } catch (DataAccessException e) {
                log.warn("[RankSnapshot] 락 반납 실패", e);
            }
        }
    }

    public boolean isTodaySnapshot() {
        return today().toString().equals(this.stringRedisTemplate.opsForValue().get(RedisKey.RANK_SNAPSHOT_DATE.of()));
    }

    /**
     * 복사본에서의 공동 순위 (점수가 더 높은 멤버 수 + 1). 복사본에 없는 멤버는 결과에 없다.
     *
     * @param hidden 순위에서 빼는 멤버 (비공개 회원). 이들보다 점수가 낮으면 그 수만큼 순위를 올린다. 현재 순위 계산과 같다
     * @return 오늘 복사본이 없으면 빈 맵. 날짜 GET 1회 + ZMSCORE 1회 + 파이프라인 ZCOUNT 1회 (Redis 왕복 3회)
     */
    public Map<Long, Long> previousRanks(String snapshotKey, Collection<Long> members, Collection<Long> hidden) {
        if (members.isEmpty() || !isTodaySnapshot()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(members));
        Set<Long> hiddenIds = new LinkedHashSet<>(hidden);
        // 비공개 회원은 조회 대상에서 제외하되, 이전 순위 보정에는 포함한다.
        ids.removeAll(hiddenIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Object> asked = new ArrayList<>(ids);
        asked.addAll(hiddenIds);
        List<Double> scores = this.redisTemplate.opsForZSet().score(snapshotKey, asked.toArray());
        if (scores == null) {
            return Map.of();
        }
        Map<Long, Double> memberScores = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            if (scores.get(i) != null) {
                memberScores.put(ids.get(i), scores.get(i));
            }
        }
        List<Double> hiddenScores = scores.subList(ids.size(), scores.size()).stream()
                .filter(score -> score != null).toList();
        Set<Double> distinct = new LinkedHashSet<>(memberScores.values());
        List<Double> ordered = new ArrayList<>(distinct);
        List<Object> higher = this.redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(RedisOperations operations) {
                for (Double score : ordered) {
                    operations.opsForZSet().count(snapshotKey, Math.nextUp(score), Double.POSITIVE_INFINITY);
                }
                return null;
            }
        });
        Map<Double, Long> rankByScore = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            double score = ordered.get(i);
            long hiddenHigher = hiddenScores.stream().filter(value -> Double.compare(value, score) > 0).count();
            rankByScore.put(score, ((Number) higher.get(i)).longValue() + 1 - hiddenHigher);
        }
        Map<Long, Long> result = new HashMap<>();
        memberScores.forEach((id, score) -> result.put(id, rankByScore.get(score)));
        return result;
    }

    /** 이전 순위 − 지금 순위. 양수는 상승, 이전 순위가 없으면 null */
    public static Long change(Map<Long, Long> previousRanks, long member, long currentRank) {
        Long previous = previousRanks.get(member);
        return previous == null ? null : previous - currentRank;
    }

    private void copy(String source, String target) {
        this.redisTemplate.opsForZSet().unionAndStore(source, List.of(), target);
        this.redisTemplate.expire(target, SNAPSHOT_TTL);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(this.clock.instant(), ClockConfig.KST);
    }
}
