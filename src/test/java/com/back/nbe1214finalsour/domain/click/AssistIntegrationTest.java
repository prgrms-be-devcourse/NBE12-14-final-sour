package com.back.nbe1214finalsour.domain.click;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 연합 학교 지원 API (SCRUM-187)를 PostgreSQL·Redis(Testcontainers)와 실제 HTTP 처리로 확인한다.
 * {@code apply-assist.lua}와 동맹 판정 쿼리도 여기서 실제로 검증된다.
 *
 * <p>기본 클리커(레벨 1)의 clickPower는 1이므로 대상 학교 증가량 = floor(clickCount * 0.3),
 * 내 기여도 증가량 = floor(clickCount * 아이템 배율)이다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AssistIntegrationTest {

    private static final String EMAIL = "assister@assist-test.ac.kr";
    private static final String ALLY_NAME = "지원대상테스트대";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisHandler redisHandler;
    @Autowired private UserRepository userRepository;
    @Autowired private EmailHasher emailHasher;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ClickerService clickerService;
    @Autowired private UniversityCache universityCache;

    private long universityId;
    private long departmentId;
    private long allyId;
    private long strangerId;
    private long allianceId;
    private User user;
    private String token;
    private final List<Long> matchIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clickerService.loadLevelTable();
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('지원테스트대') RETURNING id", Long.class);
        allyId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES (?) RETURNING id", Long.class, ALLY_NAME);
        strangerId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('지원남남테스트대') RETURNING id", Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        universityCache.load();

        user = userRepository.saveAndFlush(User.create(universityId, departmentId, "지원왕", EMAIL,
                emailHasher.hash(EMAIL), "{noop}unused", OffsetDateTime.now()));
        token = jwtProvider.createAccessToken(new AuthUser(user.getId(), universityId, departmentId, Role.USER), null);

        allianceId = jdbcTemplate.queryForObject("""
                INSERT INTO alliance (name, duration_hours, expired_at)
                VALUES ('지원테스트연합', 24, now() + INTERVAL '24 hours') RETURNING id""", Long.class);
        member(universityId, "ACCEPTED");
        member(allyId, "ACCEPTED");
    }

    @AfterEach
    void tearDown() {
        redis.opsForZSet().remove(RedisKey.RANK_UNIV.of(),
                String.valueOf(universityId), String.valueOf(allyId), String.valueOf(strangerId));
        redis.opsForSet().remove(RedisKey.DIRTY_UNIV.of(),
                String.valueOf(universityId), String.valueOf(allyId), String.valueOf(strangerId));
        redis.opsForSet().remove(RedisKey.DIRTY_DEPT.of(), String.valueOf(departmentId));
        redis.opsForSet().remove(RedisKey.DIRTY_USER.of(), String.valueOf(user.getId()));
        redis.delete(List.of(
                RedisKey.RANK_DEPT.of(universityId),
                RedisKey.RANK_USER.of(universityId),
                RedisKey.USER_STATUS.of(user.getId()),
                RedisKey.USER_CLICKER.of(user.getId()),
                RedisKey.USER_EFFECT.of(user.getId()),
                RedisKey.RATELIMIT_ACTION.of(user.getId()),
                RedisKey.RATELIMIT_CLICK.of(user.getId()),
                RedisKey.ASSISTS_CRITICAL.of(user.getId()),
                RedisKey.CLICKS_CRITICAL.of(user.getId()),
                RedisKey.ATTACKS_CRITICAL.of(user.getId()),
                RedisKey.ASSISTS_DAMAGE_RATE.of(user.getId()),
                RedisKey.CLICKS_DAMAGE_RATE.of(user.getId()),
                RedisKey.ATTACKS_DAMAGE_RATE.of(user.getId()),
                RedisKey.SUBMIT_DEDUPE.of(user.getId(), "sub-1"),
                RedisKey.SUBMIT_DEDUPE.of(user.getId(), "sub-2"),
                RedisKey.UNIV_ALLIANCE.of(universityId), RedisKey.UNIV_ALLIANCE.of(allyId),
                RedisKey.UNIV_MATCH.of(universityId), RedisKey.UNIV_MATCH.of(allyId),
                RedisKey.ALLIANCE_SCORE.of(allianceId)));
        redis.delete(matchIds.stream().map(RedisKey.MATCH_SCORE::of).toList());
        // dirty:alliance는 공유 키라 이 테스트가 넣은 연합만 뺀다. 남기면 배치 테스트에서 이 연합을 반영하려 한다
        redis.opsForSet().remove(RedisKey.DIRTY_ALLIANCE.of(), String.valueOf(allianceId));
        redis.opsForSet().remove(RedisKey.DIRTY_ALLIANCE.of() + ":flushing", String.valueOf(allianceId));
        if (!matchIds.isEmpty()) {
            Object[] members = matchIds.stream().map(String::valueOf).toArray();
            redis.opsForSet().remove(RedisKey.DIRTY_MATCH.of(), members);
            redis.opsForSet().remove(RedisKey.DIRTY_MATCH.of() + ":flushing", members);
        }

        jdbcTemplate.update("DELETE FROM alliance_match WHERE alliance_a_id IN (SELECT id FROM alliance WHERE name LIKE '지원테스트%')"
                + " OR alliance_b_id IN (SELECT id FROM alliance WHERE name LIKE '지원테스트%')");

        jdbcTemplate.update("DELETE FROM alliance_university WHERE university_id IN (?, ?, ?)",
                universityId, allyId, strangerId);
        jdbcTemplate.update("DELETE FROM alliance_chat_room WHERE alliance_id IN (SELECT id FROM alliance WHERE name LIKE '지원테스트%')");
        jdbcTemplate.update("DELETE FROM alliance WHERE name LIKE '지원테스트%'");
        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id IN (?, ?, ?)", universityId, allyId, strangerId);
        universityCache.load();
    }

    @Test
    @DisplayName("같은 연합 학교를 지원하면 대상 학교 점수가 0.3배만큼, 내 기여도가 클릭만큼 오르고 내 학교·학과는 그대로다")
    void assistRaisesAllyScoreAndMyContribution() throws Exception {
        givenScore(allyId, 100);

        assist("sub-1", allyId, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.acceptedClicks").value(10))
                // floor(10 * 1 * 0.3) = 3
                .andExpect(jsonPath("$.data.assistScore").value(30))
                .andExpect(jsonPath("$.data.targetUniversity.id").value(allyId))
                .andExpect(jsonPath("$.data.targetUniversity.name").value(ALLY_NAME))
                .andExpect(jsonPath("$.data.targetUniversity.totalScore").value(130))
                .andExpect(jsonPath("$.data.targetUniversity.rank").isNumber())
                .andExpect(jsonPath("$.data.myContribScore").value(100));

        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), String.valueOf(allyId))).isEqualTo(130.0);
        assertThat(redis.opsForZSet().score(RedisKey.RANK_USER.of(universityId), String.valueOf(user.getId())))
                .isEqualTo(100.0);
        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), String.valueOf(universityId))).isNull();
        assertThat(redis.hasKey(RedisKey.RANK_DEPT.of(universityId))).isFalse();
        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_UNIV.of(), String.valueOf(allyId))).isTrue();
        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_UNIV.of(), String.valueOf(universityId))).isFalse();
        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_USER.of(), String.valueOf(user.getId()))).isTrue();
    }

    @Test
    @DisplayName("아이템 배율은 내 기여도에만 곱하고, 대상 학교 증가량에는 곱하지 않는다")
    void itemMultiplierAppliesOnlyToContribution() throws Exception {
        redisHandler.setHash(RedisKey.USER_EFFECT.of(user.getId()), "multiplier", "1.5");

        assist("sub-1", allyId, 9)
                .andExpect(status().isOk())
                // floor(9 * 0.3) = 2, floor(9 * 1.5) = 13
                .andExpect(jsonPath("$.data.assistScore").value(27))
                .andExpect(jsonPath("$.data.myContribScore").value(135));
    }

    @Test
    @DisplayName("지원은 지원용 크리티컬 위치만 2배로 계산하고, 대상 학교 점수와 내 기여도에 모두 반영한다")
    void criticalAppliesToAssistScoreAndContribution() throws Exception {
        givenScore(allyId, 100);
        redisHandler.setHash(RedisKey.USER_EFFECT.of(user.getId()), "multiplier", 1.5);
        redis.opsForValue().set(RedisKey.ASSISTS_CRITICAL.of(user.getId()), "[0]");
        redis.opsForValue().set(RedisKey.CLICKS_CRITICAL.of(user.getId()), "[0,1,2,3]");
        redis.opsForValue().set(RedisKey.ATTACKS_CRITICAL.of(user.getId()), "[0,1,2,3]");

        assist("sub-1", allyId, 4)
                .andExpect(status().isOk())
                // 기본 점수 20 + 10 + 10 + 10 = 50. 대상 학교 50 * 0.3 = 15, 내 기여도 50 * 1.5 = 75
                .andExpect(jsonPath("$.data.assistScore").value(15))
                .andExpect(jsonPath("$.data.targetUniversity.totalScore").value(115))
                .andExpect(jsonPath("$.data.myContribScore").value(75))
                .andExpect(jsonPath("$.data.criticalIndex", Matchers.empty()));

        assertThat(redis.opsForValue().get(RedisKey.ASSISTS_CRITICAL.of(user.getId()))).isEqualTo("[]");
        assertThat(redis.opsForValue().get(RedisKey.CLICKS_CRITICAL.of(user.getId()))).isEqualTo("[0,1,2,3]");
        assertThat(redis.opsForValue().get(RedisKey.ATTACKS_CRITICAL.of(user.getId()))).isEqualTo("[0,1,2,3]");
    }

    @Test
    @DisplayName("지원은 지원용 데미지 배율만 곱하고, 대상 학교 점수와 내 기여도에 모두 반영한다")
    void damageRatesApplyToAssistScoreAndContribution() throws Exception {
        givenScore(allyId, 100);
        redisHandler.setHash(RedisKey.USER_EFFECT.of(user.getId()), "multiplier", 1.5);
        redis.opsForValue().set(RedisKey.ASSISTS_DAMAGE_RATE.of(user.getId()), "[130,130,130,130]");
        redis.opsForValue().set(RedisKey.CLICKS_DAMAGE_RATE.of(user.getId()), "[70,70,70,70]");
        redis.opsForValue().set(RedisKey.ATTACKS_DAMAGE_RATE.of(user.getId()), "[70,70,70,70]");

        assist("sub-1", allyId, 4)
                .andExpect(status().isOk())
                // 기본 점수 4 * 10 * 130 / 100 = 52. 대상 학교 52 * 0.3 = 15.6 → 15, 내 기여도 52 * 1.5 = 78
                .andExpect(jsonPath("$.data.assistScore").value(15))
                .andExpect(jsonPath("$.data.targetUniversity.totalScore").value(115))
                .andExpect(jsonPath("$.data.myContribScore").value(78))
                .andExpect(jsonPath("$.data.damageRateIndex.length()").value(20))
                .andExpect(jsonPath("$.data.damageRateIndex[*]", Matchers.everyItem(Matchers.is(100))));

        assertThat(redis.opsForValue().get(RedisKey.ASSISTS_DAMAGE_RATE.of(user.getId())))
                .isEqualTo("[" + String.join(",", Collections.nCopies(20, "100")) + "]");
        assertThat(redis.opsForValue().get(RedisKey.CLICKS_DAMAGE_RATE.of(user.getId()))).isEqualTo("[70,70,70,70]");
        assertThat(redis.opsForValue().get(RedisKey.ATTACKS_DAMAGE_RATE.of(user.getId()))).isEqualTo("[70,70,70,70]");
    }

    @Test
    @DisplayName("지원도 배율과 크리티컬을 합친 뒤 한 번 버린 값을 기본 점수로 쓴다")
    void assistCombinesDamageRateAndCritical() throws Exception {
        givenScore(allyId, 100);
        redis.opsForValue().set(RedisKey.ASSISTS_DAMAGE_RATE.of(user.getId()), "[87,130]");
        redis.opsForValue().set(RedisKey.ASSISTS_CRITICAL.of(user.getId()), "[1]");

        assist("sub-1", allyId, 2)
                .andExpect(status().isOk())
                // (10*87 + 10*130*2) / 100 = 34.7 → 34. 대상 학교 34 * 0.3 = 10.2 → 10, 내 기여도 34
                .andExpect(jsonPath("$.data.assistScore").value(10))
                .andExpect(jsonPath("$.data.targetUniversity.totalScore").value(110))
                .andExpect(jsonPath("$.data.myContribScore").value(34));
    }

    @Test
    @DisplayName("연합이 없는 학교·내 학교는 400 ASSIST_NOT_ALLY, 점수와 제출 ID는 그대로다")
    void nonAllyIsRejected() throws Exception {
        givenScore(strangerId, 100);

        assist("sub-1", strangerId, 10)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSIST_NOT_ALLY"));
        assist("sub-1", universityId, 10)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSIST_NOT_ALLY"));

        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), String.valueOf(strangerId))).isEqualTo(100.0);
        assertThat(redis.hasKey(RedisKey.SUBMIT_DEDUPE.of(user.getId(), "sub-1"))).isFalse();
        assertThat(redis.hasKey(RedisKey.RANK_USER.of(universityId))).isFalse();
    }

    @Test
    @DisplayName("상대 학교가 탈퇴했거나, 연합이 만료 시각을 지났으면(스케줄러 전이라도) 동맹이 아니다")
    void leftOrExpiredAllianceIsNotAlly() throws Exception {
        jdbcTemplate.update("UPDATE alliance_university SET status = 'LEFT' WHERE university_id = ?", allyId);
        assist("sub-1", allyId, 10)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSIST_NOT_ALLY"));

        jdbcTemplate.update("UPDATE alliance_university SET status = 'ACCEPTED' WHERE university_id = ?", allyId);
        jdbcTemplate.update("""
                UPDATE alliance SET created_at = now() - INTERVAL '2 days', expired_at = now() - INTERVAL '1 hour'
                WHERE id = ?""", allianceId);
        assist("sub-2", allyId, 10)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSIST_NOT_ALLY"));
    }

    @Test
    @DisplayName("없는 학교는 404 UNIV_NOT_FOUND, 같은 submissionId를 다시 보내면 409이고 점수는 한 번만 오른다")
    void unknownTargetAndDuplicateSubmission() throws Exception {
        assist("sub-1", Long.MAX_VALUE, 10)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIV_NOT_FOUND"));

        givenScore(allyId, 100);
        assist("sub-1", allyId, 10).andExpect(status().isOk());
        assist("sub-1", allyId, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLICK_DUPLICATE_SUBMISSION"));
        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), String.valueOf(allyId))).isEqualTo(130.0);
    }

    @Test
    @DisplayName("로그인 없이 지원하면 401, 클릭 수가 20을 넘으면 400")
    void authAndValidation() throws Exception {
        mockMvc.perform(post("/api/v1/assists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionId":"sub-1","targetUniversityId":%d,"clickCount":10}""".formatted(allyId)))
                .andExpect(status().isUnauthorized());
        assist("sub-1", allyId, 21)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
    }

    @Test
    @DisplayName("학교 상세의 isMyAlly는 같은 연합을 보는 회원에게만 true다")
    void detailShowsIsMyAlly() throws Exception {
        mockMvc.perform(get("/api/v1/universities/{id}", allyId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isMyAlly").value(true));
        mockMvc.perform(get("/api/v1/universities/{id}", strangerId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.isMyAlly").value(false));
        mockMvc.perform(get("/api/v1/universities/{id}", universityId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.isMyUniversity").value(true))
                .andExpect(jsonPath("$.data.isMyAlly").value(false));
        mockMvc.perform(get("/api/v1/universities/{id}", allyId))
                .andExpect(jsonPath("$.data.isMyAlly").value(false));
    }

    @Test
    @DisplayName("연합 학교를 지원하면 지원 점수(0.3배)가 연합 점수의 대상 학교 칸에 쌓이고, 내 학교 칸은 그대로다")
    void assistAddsToAllianceScoreOfTarget() throws Exception {
        assist("sub-1", allyId, 10).andExpect(status().isOk());
        assist("sub-2", allyId, 10).andExpect(status().isOk());   // 두 번째는 캐시에서 연합을 읽는다

        // floor(10 * 0.3) = 3, 두 번
        assertThat(redis.opsForHash().get(RedisKey.ALLIANCE_SCORE.of(allianceId), String.valueOf(allyId)))
                .isEqualTo("60");
        assertThat(redis.opsForHash().get(RedisKey.ALLIANCE_SCORE.of(allianceId), String.valueOf(universityId)))
                .isNull();
        assertThat(redis.opsForValue().get(RedisKey.UNIV_ALLIANCE.of(allyId))).isEqualTo(String.valueOf(allianceId));
        assertThat(redis.opsForValue().get(RedisKey.UNIV_MATCH.of(allyId))).isEqualTo("none");
        assertThat(redis.hasKey(RedisKey.UNIV_ALLIANCE.of(universityId))).isFalse();
        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_ALLIANCE.of(), String.valueOf(allianceId))).isTrue();
    }

    @Test
    @DisplayName("연합 대결 중이면 지원 점수가 대결 점수의 우리 편에 들어간다")
    void assistDuringMatchAddsToMatchScore() throws Exception {
        long rivalAllianceId = jdbcTemplate.queryForObject("""
                INSERT INTO alliance (name, duration_hours, expired_at)
                VALUES ('지원테스트상대연합', 24, now() + INTERVAL '24 hours') RETURNING id""", Long.class);
        long matchId = jdbcTemplate.queryForObject("""
                INSERT INTO alliance_match (alliance_a_id, alliance_b_id, started_at, ended_at, duration_hours,
                                            request_expired_at, status)
                VALUES (?, ?, now() - INTERVAL '1 hour', now() + INTERVAL '2 hours', 3, now(), 'IN_PROGRESS')
                RETURNING id""", Long.class, allianceId, rivalAllianceId);
        matchIds.add(matchId);

        assist("sub-1", allyId, 10).andExpect(status().isOk());
        assist("sub-2", allyId, 10).andExpect(status().isOk());   // 두 번째는 캐시에서 대결을 읽는다

        assertThat(redis.opsForHash().get(RedisKey.MATCH_SCORE.of(matchId), "A")).isEqualTo("60");
        assertThat(redis.opsForHash().get(RedisKey.MATCH_SCORE.of(matchId), "B")).isNull();
        assertThat(redis.opsForValue().get(RedisKey.UNIV_MATCH.of(allyId))).isEqualTo(matchId + ":A");
        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_MATCH.of(), String.valueOf(matchId))).isTrue();
        assertThat(redis.getExpire(RedisKey.UNIV_MATCH.of(allyId))).isPositive();
        assertThat(redis.opsForHash().get(RedisKey.ALLIANCE_SCORE.of(allianceId), String.valueOf(allyId)))
                .isEqualTo("60");
    }

    private void member(long university, String memberStatus) {
        jdbcTemplate.update("""
                INSERT INTO alliance_university (alliance_id, university_id, applicant_user_id, approver_user_id, status)
                VALUES (?, ?, ?, ?, ?)""", allianceId, university, user.getId(), user.getId(), memberStatus);
    }

    private void givenScore(long university, double score) {
        redis.opsForZSet().add(RedisKey.RANK_UNIV.of(), String.valueOf(university), score);
    }

    private ResultActions assist(String submissionId, long targetId, int clickCount) throws Exception {
        return mockMvc.perform(post("/api/v1/assists")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"submissionId":"%s","targetUniversityId":%d,"clickCount":%d}"""
                        .formatted(submissionId, targetId, clickCount)));
    }
}
