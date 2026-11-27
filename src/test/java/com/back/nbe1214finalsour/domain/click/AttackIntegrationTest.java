package com.back.nbe1214finalsour.domain.click;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.domain.university.service.rival.RivalChecker;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 타 학교 공격 API를 PostgreSQL·Redis(Testcontainers)와 실제 HTTP 처리로 확인한다.
 * {@code apply-attack.lua}도 여기서 실제 Redis로 검증된다.
 *
 * <p>기본 클리커(레벨 1)의 clickPower는 1이다. 테스트가 만든 학교는 {@code rivals.groups}에 없으므로
 * 요청 피해량 = floor(clickCount * 0.3)이고, 경쟁 학교 공격은 {@link RivalChecker}를 스파이로 바꿔 확인한다.
 *
 * <p>학교는 서버가 뜬 뒤에 넣으므로 {@link UniversityCache}를 다시 불러와야 공격 대상으로 찾을 수 있다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AttackIntegrationTest {

    private static final String EMAIL = "attacker@attack-test.ac.kr";
    private static final String TARGET_NAME = "공격대상테스트대";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailHasher emailHasher;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private ClickerService clickerService;
    /** 테스트가 만든 학교는 설정 파일에 없으므로, 경쟁 학교 공격만 이 스파이로 경쟁 관계를 만든다 */
    @Autowired
    private RivalChecker rivalChecker;
    @Autowired
    private UniversityCache universityCache;
    @Autowired
    private Clock clock;

    private long universityId;
    private long departmentId;
    private long targetUniversityId;
    private User user;
    private String token;
    private Long allianceId;

    @BeforeEach
    void setUp() {
        clickerService.loadLevelTable();

        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('공격테스트대') RETURNING id", Long.class);
        targetUniversityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES (?) RETURNING id", Long.class, TARGET_NAME);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        universityCache.load();

        user = userRepository.saveAndFlush(User.create(universityId, departmentId, "공격왕", EMAIL,
                emailHasher.hash(EMAIL), "{noop}unused", OffsetDateTime.now()));
        token = jwtProvider.createAccessToken(new AuthUser(user.getId(), universityId, departmentId, Role.USER), null);
    }

    @AfterEach
    void tearDown() {
        // 공유 키는 이 테스트가 넣은 member·field만, 전용 키는 통째로 지운다
        redis.opsForZSet().remove(RedisKey.RANK_UNIV.of(),
                String.valueOf(universityId), String.valueOf(targetUniversityId));
        redis.opsForSet().remove(RedisKey.DIRTY_UNIV.of(),
                String.valueOf(universityId), String.valueOf(targetUniversityId));
        redis.opsForSet().remove(RedisKey.DIRTY_DEPT.of(), String.valueOf(departmentId));
        redis.opsForSet().remove(RedisKey.DIRTY_USER.of(), String.valueOf(user.getId()));
        redis.opsForHash().delete(RedisKey.SCORE_USER_ATTACK.of(), String.valueOf(user.getId()));
        redis.delete(List.of(
                RedisKey.RANK_DEPT.of(universityId),
                RedisKey.RANK_USER.of(universityId),
                RedisKey.USER_STATUS.of(user.getId()),
                RedisKey.USER_CLICKER.of(user.getId()),
                RedisKey.RATELIMIT_ACTION.of(user.getId()),
                RedisKey.RATELIMIT_CLICK.of(user.getId()),
                RedisKey.ATTACKS_CRITICAL.of(user.getId()),
                RedisKey.CLICKS_CRITICAL.of(user.getId()),
                RedisKey.ATTACKS_DAMAGE_RATE.of(user.getId())));

        // 요청 한도 테스트에서 클릭 API도 부르므로 초별 클릭 키도 지운다
        long nowSec = Instant.now().getEpochSecond();
        redis.delete(LongStream.rangeClosed(nowSec - 8, nowSec + 2)
                .mapToObj(sec -> RedisKey.CLICKS_UNIV_SECOND.of(universityId, sec))
                .toList());

        if (allianceId != null) {
            jdbcTemplate.update("DELETE FROM alliance_university WHERE alliance_id = ?", allianceId);
            jdbcTemplate.update("DELETE FROM alliance_chat_room WHERE alliance_id = ?", allianceId);
            jdbcTemplate.update("DELETE FROM alliance WHERE id = ?", allianceId);
        }
        redis.delete(RedisKey.SUBMIT_DEDUPE.of(user.getId(), "ally-attack"));
        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id IN (?, ?)", universityId, targetUniversityId);
        universityCache.load();
    }

    @Test
    @DisplayName("공격하면 200과 피해량을 응답하고, 대상 학교 점수가 깎이고 내 공격 점수가 오른다")
    void attackLowersTargetScoreAndRaisesMyAttackScore() throws Exception {
        givenTargetScore(100);

        attack("sub-1", targetUniversityId, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.acceptedClicks").value(10))
                // floor(10 * 1 * 0.3) = 3
                .andExpect(jsonPath("$.data.damage").value(30))
                .andExpect(jsonPath("$.data.multiplier").value(1.0))
                .andExpect(jsonPath("$.data.isRival").value(false))
                .andExpect(jsonPath("$.data.targetUniversity.id").value(targetUniversityId))
                .andExpect(jsonPath("$.data.targetUniversity.name").value(TARGET_NAME))
                .andExpect(jsonPath("$.data.targetUniversity.totalScore").value(70))
                .andExpect(jsonPath("$.data.targetUniversity.rank").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.myAttackScore").value(30));

        assertThat(targetScore()).isEqualTo(70.0);
        assertThat(myAttackScore()).isEqualTo("30");
        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_UNIV.of(), String.valueOf(targetUniversityId))).isTrue();
    }

    @Test
    @DisplayName("공격은 대상 학교 점수만 바꾸고 내 학교·학과·개인 기여도는 그대로 둔다")
    void attackDoesNotChangeMyUniversityOrContribution() throws Exception {
        givenTargetScore(100);

        attack("sub-1", targetUniversityId, 10).andExpect(status().isOk());

        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), String.valueOf(universityId))).isNull();
        assertThat(redis.hasKey(RedisKey.RANK_DEPT.of(universityId))).isFalse();
        assertThat(redis.hasKey(RedisKey.RANK_USER.of(universityId))).isFalse();
        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_USER.of(), String.valueOf(user.getId()))).isTrue();
    }

    @Test
    @DisplayName("공격을 두 번 하면 공격 점수가 누적된다")
    void repeatedAttacksAccumulate() throws Exception {
        givenTargetScore(100);

        attack("sub-1", targetUniversityId, 10).andExpect(status().isOk());

        attack("sub-2", targetUniversityId, 20)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.damage").value(60))
                .andExpect(jsonPath("$.data.targetUniversity.totalScore").value(10))
                .andExpect(jsonPath("$.data.myAttackScore").value(90));
    }

    @Test
    @DisplayName("공격도 공격용 데미지 배율을 곱한 뒤 0.3배를 한다")
    void attackAppliesDamageRates() throws Exception {
        givenTargetScore(100);
        redis.opsForValue().set(RedisKey.ATTACKS_DAMAGE_RATE.of(user.getId()), "[130,130,130,130]");

        attack("sub-1", targetUniversityId, 4)
                .andExpect(status().isOk())
                // 4 * 10 * 130 / 100 = 52 → 52 * 0.3 = 15.6 → 15 (배율이 없으면 12)
                .andExpect(jsonPath("$.data.damage").value(15));
    }

    @Test
    @DisplayName("공격은 공격용 크리티컬 위치만 2배로 계산하고, 클릭용 위치는 쓰지 않는다")
    void attackUsesOnlyAttackCriticalIndex() throws Exception {
        givenTargetScore(100);
        redis.opsForValue().set(RedisKey.ATTACKS_CRITICAL.of(user.getId()), "[0]");
        redis.opsForValue().set(RedisKey.CLICKS_CRITICAL.of(user.getId()), "[0,1,2,3]");

        attack("sub-1", targetUniversityId, 4)
                .andExpect(status().isOk())
                // (20 + 10 + 10 + 10) * 0.3
                .andExpect(jsonPath("$.data.damage").value(15));

        assertThat(redis.opsForValue().get(RedisKey.ATTACKS_CRITICAL.of(user.getId()))).isEqualTo("[]");
        assertThat(redis.opsForValue().get(RedisKey.CLICKS_CRITICAL.of(user.getId()))).isEqualTo("[0,1,2,3]");
    }

    @Test
    @DisplayName("대상 학교 점수보다 큰 피해는 남은 점수만큼만 깎고, 그만큼만 공격 점수로 인정한다")
    void damageIsCappedAtRemainingScore() throws Exception {
        givenTargetScore(2);

        attack("sub-1", targetUniversityId, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.damage").value(2))
                .andExpect(jsonPath("$.data.targetUniversity.totalScore").value(0))
                .andExpect(jsonPath("$.data.myAttackScore").value(2));

        assertThat(targetScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("경쟁 학교를 공격하면 1.1배를 더 곱하고 소수점은 버린다, 응답에 isRival=true·multiplier=1.1")
    void rivalAttackAppliesMultiplier() throws Exception {
        givenTargetScore(100);
        doReturn(true).when(rivalChecker).isRival(universityId, targetUniversityId);

        attack("sub-1", targetUniversityId, 19)
                .andExpect(status().isOk())
                // floor(19 * 1 * 0.3 * 1.1) = floor(6.27) = 6 (경쟁 학교가 아니면 floor(5.7) = 5)
                .andExpect(jsonPath("$.data.damage").value(62))
                .andExpect(jsonPath("$.data.multiplier").value(1.1))
                .andExpect(jsonPath("$.data.isRival").value(true))
                .andExpect(jsonPath("$.data.targetUniversity.totalScore").value(38))
                .andExpect(jsonPath("$.data.myAttackScore").value(62));
    }

    @Test
    @DisplayName("경쟁 학교가 아니면 배율 1.0, isRival=false")
    void nonRivalAttackHasNoMultiplier() throws Exception {
        givenTargetScore(100);

        attack("sub-1", targetUniversityId, 19)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.damage").value(57))
                .andExpect(jsonPath("$.data.multiplier").value(1.0))
                .andExpect(jsonPath("$.data.isRival").value(false));
    }

    @Test
    @DisplayName("점수가 0인 학교를 공격하면 피해 0, 공격 점수도 오르지 않는다")
    void attackOnZeroScoreDoesNothing() throws Exception {
        givenTargetScore(2);
        attack("sub-1", targetUniversityId, 10).andExpect(status().isOk());

        attack("sub-2", targetUniversityId, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.damage").value(0))
                .andExpect(jsonPath("$.data.targetUniversity.totalScore").value(0))
                .andExpect(jsonPath("$.data.myAttackScore").value(2));
    }

    @Test
    @DisplayName("Redis에 점수가 없는 학교를 공격하면 피해 0이고, 배치 대상(dirty)에 넣지 않아 DB 점수를 0으로 덮지 않는다")
    void attackOnUniversityWithoutRedisScoreDoesNotMarkDirty() throws Exception {
        attack("sub-1", targetUniversityId, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.damage").value(0))
                .andExpect(jsonPath("$.data.myAttackScore").value(0));

        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_UNIV.of(), String.valueOf(targetUniversityId))).isFalse();
    }

    @Test
    @DisplayName("내 학교를 공격하면 400 ATTACK_OWN_UNIVERSITY, 같은 submissionId로 다른 학교 공격은 된다")
    void attackOnOwnUniversityIsRejected() throws Exception {
        givenTargetScore(100);

        attack("sub-1", universityId, 10)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ATTACK_OWN_UNIVERSITY"));

        // 거절된 요청은 중복 제출 키를 쓰지 않는다
        attack("sub-1", targetUniversityId, 10).andExpect(status().isOk());
    }

    @Test
    @DisplayName("동맹 직접 공격은 점수·제출 ID·한도를 소비하지 않고, 같은 제출 ID로 지원할 수 있다")
    void allyAttackIsRejectedWithoutSideEffects() throws Exception {
        givenAlliance();
        givenTargetScore(100);
        redis.opsForHash().put(RedisKey.SCORE_USER_ATTACK.of(), String.valueOf(user.getId()), "7");
        redis.opsForZSet().add(RedisKey.RANK_USER.of(universityId), String.valueOf(user.getId()), 11);

        for (int i = 0; i < 13; i++) {
            attack("ally-attack", targetUniversityId, 10)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ATTACK_ALLY_UNIVERSITY"));
        }
        assertThat(targetScore()).isEqualTo(100.0);
        assertThat(myAttackScore()).isEqualTo("7");
        assertThat(redis.opsForZSet().score(RedisKey.RANK_USER.of(universityId), String.valueOf(user.getId())))
                .isEqualTo(11.0);
        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_UNIV.of(), String.valueOf(targetUniversityId))).isFalse();
        for (String key : List.of(RedisKey.SUBMIT_DEDUPE.of(user.getId(), "ally-attack"),
                RedisKey.RATELIMIT_ACTION.of(user.getId()), RedisKey.RATELIMIT_CLICK.of(user.getId()))) {
            assertThat(redis.hasKey(key)).as(key).isFalse();
        }
        mockMvc.perform(post("/api/v1/assists")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionId":"ally-attack","targetUniversityId":%d,"clickCount":10}"""
                                .formatted(targetUniversityId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetUniversity.totalScore").value(130))
                .andExpect(jsonPath("$.data.myContribScore").value(111));
    }

    @ParameterizedTest(name = "{0}: 동맹 공격 차단={1}")
    @CsvSource({
            "IN_MATCH,true", "EXPIRED,false", "expired-time,false", "deleted-alliance,false",
            "mine-PENDING,false", "target-PENDING,false", "mine-REJECTED,false", "target-REJECTED,false",
            "mine-LEFT,false", "target-LEFT,false", "mine-deleted,false", "target-deleted,false"
    })
    @DisplayName("공격 동맹 판정은 양 학교 승인·삭제·연합 상태·실제 만료 시각을 구분한다")
    void allianceStateControlsAttack(String scenario, boolean blocked) throws Exception {
        givenAlliance();
        givenTargetScore(100);
        switch (scenario) {
            case "IN_MATCH", "EXPIRED" -> jdbcTemplate.update("UPDATE alliance SET status = ? WHERE id = ?", scenario, allianceId);
            case "expired-time" -> jdbcTemplate.update("""
                    UPDATE alliance SET created_at = now() - INTERVAL '2 days', expired_at = now() - INTERVAL '1 hour'
                    WHERE id = ?""", allianceId);
            case "deleted-alliance" -> jdbcTemplate.update("UPDATE alliance SET deleted_at = now() WHERE id = ?", allianceId);
            default -> {
                String[] parts = scenario.split("-");
                long memberId = parts[0].equals("mine") ? universityId : targetUniversityId;
                if (parts[1].equals("deleted")) {
                    jdbcTemplate.update("UPDATE alliance_university SET deleted_at = now() WHERE alliance_id = ? AND university_id = ?",
                            allianceId, memberId);
                } else {
                    jdbcTemplate.update("UPDATE alliance_university SET status = ? WHERE alliance_id = ? AND university_id = ?",
                            parts[1], allianceId, memberId);
                }
            }
        }
        attack("ally-attack", targetUniversityId, 10)
                .andExpect(status().is(blocked ? 400 : 200))
                .andExpect(jsonPath("$.code").value(blocked ? "ATTACK_ALLY_UNIVERSITY" : "SUCCESS"));
        assertThat(targetScore()).isEqualTo(blocked ? 100.0 : 70.0);
    }

    @Test
    @DisplayName("expiredAt == now면 스케줄러 실행 없이 동맹 공격을 허용한다")
    void exactlyExpiredAllianceIsNotAlly() throws Exception {
        givenAlliance();
        givenTargetScore(100);
        Instant boundary = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        doReturn(boundary).when(clock).instant();
        jdbcTemplate.update("UPDATE alliance SET expired_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(boundary, clock.getZone()), allianceId);

        attack("ally-attack", targetUniversityId, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.damage").value(30));
        assertThat(targetScore()).isEqualTo(70.0);
    }

    @Test
    @DisplayName("없는 학교를 공격하면 404 UNIV_NOT_FOUND, 같은 submissionId로 다른 학교 공격은 된다")
    void attackOnUnknownUniversityIsRejected() throws Exception {
        givenTargetScore(100);

        attack("sub-1", 999_999_999L, 10)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIV_NOT_FOUND"));

        attack("sub-1", targetUniversityId, 10).andExpect(status().isOk());
    }

    @Test
    @DisplayName("같은 submissionId로 다시 공격하면 409 CLICK_DUPLICATE_SUBMISSION, 점수는 한 번만 깎인다")
    void duplicateSubmissionIsRejected() throws Exception {
        givenTargetScore(100);
        attack("sub-dup", targetUniversityId, 10).andExpect(status().isOk());

        attack("sub-dup", targetUniversityId, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLICK_DUPLICATE_SUBMISSION"));

        assertThat(targetScore()).isEqualTo(70.0);
        assertThat(myAttackScore()).isEqualTo("30");
    }

    @Test
    @DisplayName("요청 한도는 클릭과 합산한다: 클릭 12회 뒤 공격은 429 CLICK_TOO_FREQUENT")
    void rateLimitIsSharedWithClick() throws Exception {
        givenTargetScore(100);
        for (int i = 1; i <= 12; i++) {
            click("click-" + i, 1).andExpect(status().isOk());
        }

        attack("sub-1", targetUniversityId, 10)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CLICK_TOO_FREQUENT"));

        assertThat(targetScore()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("정지된 회원이 공격하면 403 USER_SUSPENDED, 점수는 깎이지 않는다")
    void suspendedUserIsRejected() throws Exception {
        givenTargetScore(100);
        redis.opsForValue().set(RedisKey.USER_STATUS.of(user.getId()), "SUSPENDED");

        attack("sub-1", targetUniversityId, 10)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_SUSPENDED"));

        assertThat(targetScore()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("토큰이 없으면 401 COMMON_UNAUTHORIZED")
    void requiresToken() throws Exception {
        mockMvc.perform(post("/api/v1/attacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionId":"sub-1","targetUniversityId":%d,"clickCount":3}"""
                                .formatted(targetUniversityId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("clickCount가 1~20을 벗어나거나 submissionId·targetUniversityId가 없으면 400 COMMON_INVALID_INPUT")
    void rejectsInvalidRequest() throws Exception {
        attack("sub-1", targetUniversityId, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
        attack("sub-2", targetUniversityId, 21)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
        attack("  ", targetUniversityId, 3)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
        mockMvc.perform(post("/api/v1/attacks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionId":"sub-3","clickCount":3}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
    }

    private void givenAlliance() {
        allianceId = jdbcTemplate.queryForObject("""
                INSERT INTO alliance (name, duration_hours, expired_at)
                VALUES ('공격차단테스트연합', 24, now() + INTERVAL '24 hours') RETURNING id""", Long.class);
        for (long memberId : List.of(universityId, targetUniversityId)) {
            jdbcTemplate.update("""
                    INSERT INTO alliance_university (alliance_id, university_id, applicant_user_id, approver_user_id, status)
                    VALUES (?, ?, ?, ?, 'ACCEPTED')""", allianceId, memberId, user.getId(), user.getId());
        }
    }

    private void givenTargetScore(double score) {
        redis.opsForZSet().add(RedisKey.RANK_UNIV.of(), String.valueOf(targetUniversityId), score);
    }

    private Double targetScore() {
        return redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), String.valueOf(targetUniversityId));
    }

    private Object myAttackScore() {
        return redis.opsForHash().get(RedisKey.SCORE_USER_ATTACK.of(), String.valueOf(user.getId()));
    }

    private ResultActions attack(String submissionId, long targetId, int clickCount) throws Exception {
        return mockMvc.perform(post("/api/v1/attacks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"submissionId":"%s","targetUniversityId":%d,"clickCount":%d}"""
                        .formatted(submissionId, targetId, clickCount)));
    }

    private ResultActions click(String submissionId, int clickCount) throws Exception {
        return mockMvc.perform(post("/api/v1/clicks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"submissionId":"%s","clickCount":%d}""".formatted(submissionId, clickCount)));
    }
}
