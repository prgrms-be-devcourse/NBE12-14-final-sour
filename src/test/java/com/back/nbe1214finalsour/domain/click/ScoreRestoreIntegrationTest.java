package com.back.nbe1214finalsour.domain.click;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.click.scheduler.ScoreFlushScheduler;
import com.back.nbe1214finalsour.domain.click.service.ScoreFlushService;
import com.back.nbe1214finalsour.domain.click.service.ScoreRestoreService;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AOF까지 잃어 Redis가 비었을 때 1분 배치 스케줄러가 DB 값으로 Redis 점수를 다시 채우는지 확인한다.
 *
 * <p>복구는 Redis가 통째로 빈 상태를 전제로 하므로, 테스트 전에 Redis 전체를 떠 두고 비운 뒤, 끝나면 그대로 되돌린다.
 * 그래서 다른 테스트가 쓰는 키({@code redis:ready} 포함)에 영향을 주지 않는다. DB는 {@code @Transactional}로 롤백되고,
 * 복구 쿼리(JdbcTemplate)도 같은 트랜잭션이라 테스트가 넣은 행을 읽는다.
 */
@IntegrationTest
@Transactional
class ScoreRestoreIntegrationTest {

    @Autowired
    private ScoreFlushService scoreFlushService;
    @Autowired
    private ScoreRestoreService scoreRestoreService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UniversityRepository universityRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailHasher emailHasher;

    private final Map<String, Dumped> redisBefore = new HashMap<>();

    private record Dumped(byte[] value, long ttlMillis) {
    }

    @BeforeEach
    void emptyRedis() {
        Set<String> keys = redis.keys("*");
        for (String key : keys) {
            byte[] value = redis.dump(key);
            Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
            if (value != null) {
                redisBefore.put(key, new Dumped(value, ttl == null || ttl < 0 ? 0 : ttl));
            }
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @AfterEach
    void restoreRedisAsBefore() {
        Set<String> keys = redis.keys("*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
        redisBefore.forEach((key, dumped) ->
                redis.restore(key, dumped.value(), dumped.ttlMillis(), TimeUnit.MILLISECONDS, true));
    }

    @Test
    @DisplayName("Redis가 비어 있으면 DB 점수로 학교·학과·기여도·공격 점수를 채우고 준비 표시를 남긴다")
    void restoresScoresFromDbWhenRedisIsEmpty() {
        // given
        University university = universityRepository.saveAndFlush(new University("복구테스트대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "복구학과"));
        User user = saveUser(university, department, "복구회원", "restore-user@test.ac.kr");
        jdbcTemplate.update("update university set total_score = 1000 where id = ?", university.getId());
        jdbcTemplate.update("update department set total_score = 300 where id = ?", department.getId());
        jdbcTemplate.update("update users set total_contrib_score = 120, total_attack_score = 45 where id = ?", user.getId());

        // when
        runScheduler();

        // then
        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), id(university))).isEqualTo(1000.0);
        assertThat(redis.opsForZSet().score(RedisKey.RANK_DEPT.of(university.getId()), id(department))).isEqualTo(300.0);
        assertThat(redis.opsForZSet().score(RedisKey.RANK_USER.of(university.getId()), id(user))).isEqualTo(120.0);
        assertThat(redis.opsForHash().get(RedisKey.SCORE_USER_ATTACK.of(), id(user))).isEqualTo("45");
        assertThat(scoreRestoreService.isReady()).isTrue();
    }

    @Test
    @DisplayName("숨김 학교 점수는 공개 학교 순위가 아니라 숨김 학교 순위에 채운다")
    void restoresHiddenUniversityIntoHiddenRank() {
        // given
        University hidden = universityRepository.saveAndFlush(new University("복구숨김대학교"));
        jdbcTemplate.update("update university set total_score = 700, hidden = true where id = ?", hidden.getId());

        // when
        runScheduler();

        // then
        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV_HIDDEN.of(), id(hidden))).isEqualTo(700.0);
        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), id(hidden))).isNull();
    }

    @Test
    @DisplayName("진행 중인 연합의 학교별 점수와 진행 중인 대결 점수를 채운다")
    void restoresAllianceAndMatchScores() {
        // given
        University univA = universityRepository.saveAndFlush(new University("복구연합A대학교"));
        University univB = universityRepository.saveAndFlush(new University("복구연합B대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(univA, "복구연합학과"));
        User user = saveUser(univA, department, "연합회원", "restore-ally@test.ac.kr");

        long allianceId = insertAlliance("복구연합", "IN_MATCH");
        long otherAllianceId = insertAlliance("복구상대연합", "IN_MATCH");
        insertAcceptedMember(allianceId, univA.getId(), user.getId(), 80);
        insertAcceptedMember(allianceId, univB.getId(), user.getId(), 20);
        long matchId = insertInProgressMatch(allianceId, otherAllianceId, 150, 90);

        // when
        runScheduler();

        // then
        Map<Object, Object> allianceScore = redis.opsForHash().entries(RedisKey.ALLIANCE_SCORE.of(allianceId));
        assertThat(allianceScore).containsEntry(id(univA), "80").containsEntry(id(univB), "20");
        Map<Object, Object> matchScore = redis.opsForHash().entries(RedisKey.MATCH_SCORE.of(matchId));
        assertThat(matchScore).containsEntry("A", "150").containsEntry("B", "90");
    }

    @Test
    @DisplayName("아직 끝나지 않은 물약 효과를 채우고, 끝나는 시각에 키가 사라지게 한다")
    void restoresActivePotionEffect() {
        // given
        University university = universityRepository.saveAndFlush(new University("복구물약대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "복구물약학과"));
        User user = saveUser(university, department, "물약회원", "restore-potion@test.ac.kr");
        Long itemId = jdbcTemplate.queryForObject(
                "select id from item where type = 'MULTIPLIER' and multiplier = 2.0", Long.class);
        Long purchaseId = jdbcTemplate.queryForObject("""
                insert into user_item (user_id, item_id, price, expired_at)
                values (?, ?, 1500, now() + interval '3 minutes') returning id
                """, Long.class, user.getId(), itemId);

        // when
        runScheduler();

        // then
        String key = RedisKey.USER_EFFECT.of(user.getId());
        Map<Object, Object> effect = redis.opsForHash().entries(key);
        assertThat(effect)
                .containsEntry("itemId", String.valueOf(itemId))
                .containsEntry("multiplier", "2.0")
                .containsEntry("purchaseId", String.valueOf(purchaseId));
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 180L);
    }

    @Test
    @DisplayName("준비 표시만 없고 점수가 남아 있으면 덮어쓰지 않고 표시만 남긴다")
    void onlyMarksReadyWhenScoresStillExist() {
        // given: 첫 배포처럼 Redis 점수는 있고 표시 키만 없다. Redis 값이 DB보다 앞서 있다
        University university = universityRepository.saveAndFlush(new University("복구첫배포대학교"));
        jdbcTemplate.update("update university set total_score = 1000 where id = ?", university.getId());
        redis.opsForZSet().add(RedisKey.RANK_UNIV.of(), id(university), 1500);

        // when
        runScheduler();

        // then
        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), id(university))).isEqualTo(1500.0);
        assertThat(scoreRestoreService.isReady()).isTrue();
    }

    @Test
    @DisplayName("복구한 회차에는 배치가 DB에 쓰지 않는다")
    void doesNotFlushInRestoringRun() {
        // given: Redis가 빈 뒤 들어온 dirty 표시가 있어도, 복구 회차에는 DB를 덮지 않는다
        University university = universityRepository.saveAndFlush(new University("복구배치대학교"));
        jdbcTemplate.update("update university set total_score = 1000 where id = ?", university.getId());
        redis.opsForSet().add(RedisKey.DIRTY_UNIV.of(), id(university));

        // when
        runScheduler();

        // then
        Long dbScore = jdbcTemplate.queryForObject(
                "select total_score from university where id = ?", Long.class, university.getId());
        assertThat(dbScore).isEqualTo(1000L);
    }

    private void runScheduler() {
        new ScoreFlushScheduler(scoreFlushService, redis, scoreRestoreService).run();
    }

    private long insertAlliance(String name, String status) {
        return jdbcTemplate.queryForObject("""
                insert into alliance (name, status, duration_hours, expired_at)
                values (?, ?, 6, now() + interval '6 hours') returning id
                """, Long.class, name, status);
    }

    private void insertAcceptedMember(long allianceId, long universityId, long userId, long totalScore) {
        jdbcTemplate.update("""
                insert into alliance_university
                    (alliance_id, university_id, applicant_user_id, approver_user_id, total_score, status)
                values (?, ?, ?, ?, ?, 'ACCEPTED')
                """, allianceId, universityId, userId, userId, totalScore);
    }

    private long insertInProgressMatch(long allianceAId, long allianceBId, long aScore, long bScore) {
        return jdbcTemplate.queryForObject("""
                insert into alliance_match
                    (alliance_a_id, alliance_b_id, started_at, ended_at, duration_hours,
                     request_expired_at, status, a_score, b_score)
                values (?, ?, now(), now() + interval '1 hour', 1, now() + interval '1 hour', 'IN_PROGRESS', ?, ?)
                returning id
                """, Long.class, allianceAId, allianceBId, aScore, bScore);
    }

    private User saveUser(University university, Department department, String nickname, String email) {
        return userRepository.saveAndFlush(User.create(
                university.getId(),
                department.getId(),
                nickname,
                email,
                emailHasher.hash(email),
                "{noop}unused",
                OffsetDateTime.now()));
    }

    private static String id(University university) {
        return String.valueOf(university.getId());
    }

    private static String id(Department department) {
        return String.valueOf(department.getId());
    }

    private static String id(User user) {
        return String.valueOf(user.getId());
    }
}
