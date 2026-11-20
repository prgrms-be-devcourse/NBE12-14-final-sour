package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchSettlement;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchSettlementRepository;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceMatchSettlementCompletionService;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceMatchService;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceMatchScoringScheduler;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceService;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 PostgreSQL의 서로 다른 트랜잭션/커넥션에서 동시에 실행한다. */
@IntegrationTest
@TestPropertySource(properties = {
        "alliance.scoring.enabled=true", "alliance.scoring.prepare-delay=PT1H"
})
class AllianceMatchConcurrencyIntegrationTest {

    @Autowired
    AllianceService allianceService;
    @Autowired
    AllianceMatchService matchService;
    @Autowired
    AllianceMatchRepository matchRepository;
    @Autowired
    AllianceMatchSettlementRepository settlementRepository;
    @Autowired
    AllianceMatchSettlementCompletionService completionService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EmailHasher emailHasher;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    AllianceRepository allianceRepository;
    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired StringRedisTemplate redis;
    @Autowired AllianceMatchScoringScheduler scoringScheduler;
    @Autowired JwtProvider jwt;
    @Autowired MockMvc mvc;
    @Autowired UniversityCache universityCache;
    private final List<String> scoreKeys = new ArrayList<>();

    private final List<Long> allianceIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> departmentIds = new ArrayList<>();
    private final List<Long> universityIds = new ArrayList<>();

    record Member(Long userId, Long universityId) {}
    record Group(Long allianceId, Member first, Member second) {}

    @Test
    void oppositeDirectionRequests_onlyOneRequestIsSaved() throws Exception {
        Group a = createFullAlliance();
        Group b = createFullAlliance();

        int success = concurrently(
                () -> matchService.requestMatch(a.first.userId(), a.allianceId(), b.allianceId(), 6),
                () -> matchService.requestMatch(b.first.userId(), b.allianceId(), a.allianceId(), 6)
        );

        assertThat(success).isEqualTo(1);
        assertThat(requestedPairCount(a.allianceId(), b.allianceId())).isEqualTo(1L);
    }

    @Test
    void sameMatchAcceptedTwice_onlyOneSucceeds() throws Exception {
        Group a = createFullAlliance();
        Group b = createFullAlliance();
        AllianceMatch requested = matchService.requestMatch(a.first.userId(), a.allianceId(), b.allianceId(), 6);

        int success = concurrently(
                () -> matchService.acceptMatch(b.first.userId(), requested.getId()),
                () -> matchService.acceptMatch(b.second.userId(), requested.getId())
        );

        assertThat(success).isEqualTo(1);
        assertThat(matchStatus(requested.getId())).isEqualTo("IN_PROGRESS");
        assertThat(allianceStatus(a.allianceId())).isEqualTo("IN_MATCH");
        assertThat(allianceStatus(b.allianceId())).isEqualTo("IN_MATCH");
    }

    @Test
    void committedStartPreparesRosterOnceAndNeverRecreatesLostScores() {
        Group a = createFullAlliance();
        Group b = createFullAlliance();
        Long id = matchService.requestMatch(a.first.userId(), a.allianceId(), b.allianceId(), 6).getId();
        String key = "match:" + id + ":score";
        scoreKeys.add(key);
        scoringScheduler.prepareStartedMatches();
        assertThat(redis.hasKey(key)).isFalse();

        matchService.acceptMatch(b.first.userId(), id);
        scoringScheduler.prepareStartedMatches();
        assertThat(redis.opsForHash().multiGet(key, List.of("aUniversity1Id", "aUniversity2Id", "bUniversity1Id", "bUniversity2Id")))
                .containsExactly(a.first.universityId().toString(), a.second.universityId().toString(),
                        b.first.universityId().toString(), b.second.universityId().toString());
        assertThat(redis.opsForHash().get(key, "aUniversity1Contribution")).isEqualTo("0");

        redis.opsForHash().put(key, "aUniversity1Contribution", "17");
        scoringScheduler.prepareStartedMatches();
        assertThat(redis.opsForHash().get(key, "aUniversity1Contribution")).isEqualTo("17");
        redis.delete(key);
        scoringScheduler.prepareStartedMatches();
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void realClickAndAssistRejectBeforePreparationThenScoreOnceForTheReceivingSchool() throws Exception {
        Group a = createFullAlliance();
        Group b = createFullAlliance();
        universityCache.load();
        Member player = a.first();
        String school = player.universityId().toString();
        String receiver = a.second().universityId().toString();
        String submission = "score-click";
        scoreKeys.add(RedisKey.SUBMIT_DEDUPE.of(player.userId(), submission));
        scoreKeys.add(RedisKey.SUBMIT_DEDUPE.of(player.userId(), "score-assist"));
        scoreKeys.add(RedisKey.RANK_USER.of(school));
        scoreKeys.add(RedisKey.RANK_DEPT.of(school));
        Long id = matchService.requestMatch(player.userId(), a.allianceId(), b.allianceId(), 6).getId();
        matchService.acceptMatch(b.first().userId(), id);
        String scoreKey = RedisKey.MATCH_SCORE.of(id);
        scoreKeys.add(scoreKey);
        String token = jwt.createAccessToken(new AuthUser(player.userId(), player.universityId(),
                userRepository.findById(player.userId()).orElseThrow().getDepartmentId(), Role.USER), null);
        var click = post("/api/v1/clicks").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"submissionId":"score-click","clickCount":10}
                         """);
        mvc.perform(click).andExpect(status().isServiceUnavailable());
        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), school)).isNull();

        scoringScheduler.prepareStartedMatches();
        mvc.perform(click).andExpect(status().isOk()).andExpect(jsonPath("$.data.earnedScore").value(10));
        mvc.perform(post("/api/v1/assists").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                           {"submissionId":"score-assist","clickCount":10,"targetUniversityId":%s}
                           """.formatted(receiver)))
                .andExpect(status().isOk());
        mvc.perform(click).andExpect(status().isConflict());
        assertThat(redis.opsForHash().multiGet(scoreKey, List.of("aUniversity1Contribution", "aUniversity2Contribution")))
                .containsExactly("10", "3");
        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), school)).isEqualTo(10.0);
        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), receiver)).isEqualTo(3.0);
        assertThat(redis.opsForZSet().score(RedisKey.RANK_USER.of(school), player.userId().toString())).isEqualTo(20.0);
        assertThat(redis.getExpire(RedisKey.SUBMIT_DEDUPE.of(player.userId(), submission))).isGreaterThan(10);
    }

    @Test
    void acceptAndCancelRacing_exactlyOneWins() throws Exception {
        Group a = createFullAlliance();
        Group b = createFullAlliance();
        AllianceMatch requested = matchService.requestMatch(a.first.userId(), a.allianceId(), b.allianceId(), 6);

        int success = concurrently(
                () -> matchService.acceptMatch(b.first.userId(), requested.getId()),
                () -> matchService.cancelMatch(a.first.userId(), requested.getId())
        );

        assertThat(success).isEqualTo(1);
        String status = matchStatus(requested.getId());
        assertThat(status).isIn("IN_PROGRESS", "CANCELED");
        String expectedAllianceStatus = status.equals("IN_PROGRESS") ? "IN_MATCH" : "ACTIVE";
        assertThat(allianceStatus(a.allianceId())).isEqualTo(expectedAllianceStatus);
        assertThat(allianceStatus(b.allianceId())).isEqualTo(expectedAllianceStatus);
    }

    @Test
    void sameAllianceTwoMatches_onlyOneCanStart() throws Exception {
        Group a = createFullAlliance();
        Group b = createFullAlliance();
        Group c = createFullAlliance();
        AllianceMatch ab = matchService.requestMatch(a.first.userId(), a.allianceId(), b.allianceId(), 6);
        AllianceMatch ac = matchService.requestMatch(a.second.userId(), a.allianceId(), c.allianceId(), 6);

        int success = concurrently(
                () -> matchService.acceptMatch(b.first.userId(), ab.getId()),
                () -> matchService.acceptMatch(c.first.userId(), ac.getId())
        );

        assertThat(success).isEqualTo(1);
        long started = List.of(matchStatus(ab.getId()), matchStatus(ac.getId())).stream()
                .filter("IN_PROGRESS"::equals).count();
        assertThat(started).isEqualTo(1);
        assertThat(allianceStatus(a.allianceId())).isEqualTo("IN_MATCH");
    }

    /** 두 스레드가 준비된 뒤 동시에 시작하도록 맞춘다. 실패는 서비스의 비즈니스 예외일 때만 허용. */
    private int concurrently(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> one = executor.submit(() -> tryCall(first, ready, start));
            Future<Boolean> two = executor.submit(() -> tryCall(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return (one.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (two.get(30, TimeUnit.SECONDS) ? 1 : 0);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private boolean tryCall(Callable<?> action, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시성 테스트 시작 실패");
        }
        try {
            action.call();
            return true;
        } catch (BusinessException expectedRejection) {
            return false;
        }
    }

    private Group createFullAlliance() {
        Member first = createMember();
        Member second = createMember();
        Alliance alliance = allianceService.createAlliance(first.userId(),
                "동시대결_" + UUID.randomUUID().toString().substring(0, 8), 48);
        allianceIds.add(alliance.getId());
        AllianceUniversity application = allianceService.applyToAlliance(second.userId(), alliance.getId());
        allianceService.approveApplication(first.userId(), application.getId());
        return new Group(alliance.getId(), first, second);
    }

    private Member createMember() {
        String key = UUID.randomUUID().toString().replace("-", "");
        Long universityId = jdbc.queryForObject(
                "INSERT INTO university(name) VALUES(?) RETURNING id", Long.class, "대결병렬_" + key);
        universityIds.add(universityId);
        Long deptId = jdbc.queryForObject(
                "INSERT INTO department(university_id, name) VALUES (?, ?) RETURNING id",
                Long.class, universityId, "테스트학과");
        departmentIds.add(deptId);
        String email = "parallel-match-" + key + "@test.ac.kr";
        User user = User.create(universityId, deptId, "대결" + key.substring(0, 10), email,
                emailHasher.hash(email), passwordEncoder.encode("Passw0rd!"), OffsetDateTime.now());
        Long userId = userRepository.saveAndFlush(user).getId();
        userIds.add(userId);
        return new Member(userId, universityId);
    }

    private long requestedPairCount(Long aId, Long bId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM alliance_match
                WHERE status = 'REQUESTED' AND deleted_at IS NULL
                  AND ((alliance_a_id = ? AND alliance_b_id = ?)
                    OR (alliance_a_id = ? AND alliance_b_id = ?))
                """, Long.class, aId, bId, bId, aId);
    }

    private String matchStatus(Long matchId) {
        return jdbc.queryForObject("SELECT status FROM alliance_match WHERE id = ?",
                String.class, matchId);
    }

    private String allianceStatus(Long allianceId) {
        return jdbc.queryForObject("SELECT status FROM alliance WHERE id = ?",
                String.class, allianceId);
    }

    @AfterEach
    void cleanUp() {
        if (!scoreKeys.isEmpty()) redis.delete(scoreKeys);
        // FK 제약 때문에 대결 -> 가입 -> 연합 -> 회원 -> 학과 -> 대학 순서로 정리한다.
        for (Long id : allianceIds) {
            jdbc.update("DELETE FROM alliance_match_settlement WHERE match_id IN (SELECT id FROM alliance_match WHERE alliance_a_id = ? OR alliance_b_id = ?)", id, id);
            jdbc.update("DELETE FROM alliance_match WHERE alliance_a_id = ? OR alliance_b_id = ?", id, id);
        }
        for (Long id : allianceIds) jdbc.update("DELETE FROM alliance_university WHERE alliance_id = ?", id);
        for (Long id : allianceIds) jdbc.update("DELETE FROM alliance WHERE id = ?", id);
        for (Long id : userIds) {
            redis.delete(List.of(RedisKey.USER_STATUS.of(id), RedisKey.USER_CLICKER.of(id),
                    RedisKey.RATELIMIT_ACTION.of(id), RedisKey.RATELIMIT_CLICK.of(id)));
            redis.opsForSet().remove(RedisKey.DIRTY_USER.of(), id.toString());
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        for (Long id : departmentIds) {
            redis.opsForSet().remove(RedisKey.DIRTY_DEPT.of(), id.toString());
            jdbc.update("DELETE FROM department WHERE id = ?", id);
        }
        for (Long id : universityIds) {
            redis.opsForSet().remove(RedisKey.DIRTY_UNIV.of(), id.toString());
            redis.opsForZSet().remove(RedisKey.RANK_UNIV.of(), id.toString());
            jdbc.update("DELETE FROM university WHERE id = ?", id);
        }
    }

    @Test
    void sameMatchFinishedTwice_onlyOneSucceeds() throws Exception {
        // given: 두 연합과 종료 시각이 지난 대결
        Group a = createFullAlliance();
        Group b = createFullAlliance();

        OffsetDateTime startedAt = OffsetDateTime.now().minusHours(7);

        Long matchId = transactionTemplate.execute(status -> {
            Alliance allianceA = allianceRepository.findByIdForUpdate(
                    a.allianceId()
            ).orElseThrow();

            Alliance allianceB = allianceRepository.findByIdForUpdate(
                    b.allianceId()
            ).orElseThrow();

            AllianceMatch match = AllianceMatch.request(
                    a.allianceId(),
                    b.allianceId(),
                    6,
                    startedAt.plusHours(1)
            );

            match.accept(startedAt);
            allianceA.startMatch(startedAt);
            allianceB.startMatch(startedAt);

            Long id = matchRepository.saveAndFlush(match).getId();
            AllianceMatchSettlement settlement = AllianceMatchSettlement.prepare(
                    id, 300L, 200L,
                    a.first.universityId(), a.second.universityId(),
                    b.first.universityId(), b.second.universityId(),
                    120L, 180L, 80L, 120L);
            settlement.markApplied();
            settlementRepository.saveAndFlush(settlement);
            return id;
        });

        // when: 두 스레드가 동시에 같은 대결 종료 시도
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Boolean> first = executor.submit(() ->
                    tryFinishMatch(matchId, ready, start)
            );

            Future<Boolean> second = executor.submit(() ->
                    tryFinishMatch(matchId, ready, start)
            );

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successCount = (first.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);

            // then: 먼저 도착한 호출이 종료하고 다음 호출은 멱등적으로 성공
            assertThat(successCount).isEqualTo(2);
            assertThat(matchStatus(matchId)).isEqualTo("FINISHED");

            assertThat(allianceStatus(a.allianceId()))
                    .isEqualTo("ACTIVE");
            assertThat(allianceStatus(b.allianceId()))
                    .isEqualTo("ACTIVE");

            // then: 동결된 정산 스냅샷의 결과만 저장
            Long aScore = jdbc.queryForObject(
                    "SELECT a_score FROM alliance_match WHERE id = ?",
                    Long.class,
                    matchId
            );

            Long bScore = jdbc.queryForObject(
                    "SELECT b_score FROM alliance_match WHERE id = ?",
                    Long.class,
                    matchId
            );

            assertThat(List.of(aScore, bScore)).isEqualTo(List.of(300L, 200L));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                    .isTrue();
        }
    }

    private boolean tryFinishMatch(
            Long matchId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시성 테스트 시작 실패");
        }
        completionService.completeApplied(matchId);
        return true;
    }
}
