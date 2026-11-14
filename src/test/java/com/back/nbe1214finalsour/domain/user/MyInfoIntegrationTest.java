package com.back.nbe1214finalsour.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 내 정보 API를 PostgreSQL·Redis(Testcontainers)와 실제 HTTP 처리로 확인한다 (회원 MVP 계획 단계 10).
 *
 * <p>넣은 행, 클리커 레벨, Redis 점수 키는 테스트마다 지운다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class MyInfoIntegrationTest {

    private static final String EMAIL = "student@scrum69-test.ac.kr";

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

    private long universityId;
    private long departmentId;
    private User user;
    private List<Long> insertedLevels;

    @BeforeEach
    void setUp() {
        // 2·3레벨 추가. 이미 있으면 건너뛰고, 이 테스트가 넣은 레벨만 기록해 두었다가 지운다
        insertedLevels = jdbcTemplate.queryForList("""
                INSERT INTO clicker (id, click_power, required_score)
                VALUES (2, 20, 300), (3, 40, 1000)
                ON CONFLICT (id) DO NOTHING
                RETURNING id
                """, Long.class);
        clickerService.loadLevelTable();

        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name, logo_url) VALUES ('내정보테스트대', 'https://cdn.test/u/logo.png') RETURNING id",
                Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        user = userRepository.saveAndFlush(User.create(universityId, departmentId, "내정보왕", EMAIL,
                emailHasher.hash(EMAIL), "{noop}unused", OffsetDateTime.now()));
        jdbcTemplate.update("""
                UPDATE users SET total_contrib_score = 500, total_attack_score = 7,
                                 logo_url = 'https://cdn.test/users/profile.png'
                WHERE id = ?
                """, user.getId());
    }

    @AfterEach
    void tearDown() {
        redis.delete(RedisKey.RANK_USER.of(universityId));
        redis.opsForHash().delete(RedisKey.SCORE_USER_ATTACK.of(), user.getId().toString());
        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
        insertedLevels.forEach(level -> jdbcTemplate.update("DELETE FROM clicker WHERE id = ?", level));
        clickerService.loadLevelTable();
    }

    @Test
    @DisplayName("명세서 응답 필드를 모두 채운다. 이메일은 복호화하고, Redis에 점수가 없으면 DB 점수를 쓴다")
    void returnsAllFieldsWithDbScoresWhenRedisHasNone() throws Exception {
        me(tokenOf(user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(user.getId()))
                .andExpect(jsonPath("$.data.nickname").value("내정보왕"))
                .andExpect(jsonPath("$.data.email").value(EMAIL))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://cdn.test/users/profile.png"))
                .andExpect(jsonPath("$.data.university.id").value(universityId))
                .andExpect(jsonPath("$.data.university.name").value("내정보테스트대"))
                .andExpect(jsonPath("$.data.university.logoUrl").value("https://cdn.test/u/logo.png"))
                .andExpect(jsonPath("$.data.department.id").value(departmentId))
                .andExpect(jsonPath("$.data.department.name").value("컴퓨터공학과"))
                .andExpect(jsonPath("$.data.clicker.level").value(1))
                .andExpect(jsonPath("$.data.clicker.clickPower").value(1))
                .andExpect(jsonPath("$.data.clicker.nextLevelRequiredScore").value(300))
                .andExpect(jsonPath("$.data.totalContribScore").value(500))
                .andExpect(jsonPath("$.data.totalAttackScore").value(7));
    }

    @Test
    @DisplayName("점수는 Redis 값(실시간)이 있으면 DB 값보다 우선한다: 기여도 RANK_USER ZSet, 공격 점수 SCORE_USER_ATTACK Hash")
    void redisScoresTakePrecedence() throws Exception {
        redis.opsForZSet().add(RedisKey.RANK_USER.of(universityId), user.getId().toString(), 12840);
        redis.opsForHash().put(RedisKey.SCORE_USER_ATTACK.of(), user.getId().toString(), "3");

        me(tokenOf(user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalContribScore").value(12840))
                .andExpect(jsonPath("$.data.totalAttackScore").value(3));
    }

    @Test
    @DisplayName("최고 레벨이면 nextLevelRequiredScore는 null이고, 값이 없는 필드도 null로 응답에 남는다")
    void maxLevelHasNullNextLevelScore() throws Exception {
        long maxLevel = jdbcTemplate.queryForObject("SELECT max(id) FROM clicker", Long.class);
        int maxPower = jdbcTemplate.queryForObject("SELECT click_power FROM clicker WHERE id = ?", Integer.class,
                maxLevel);
        jdbcTemplate.update("UPDATE users SET clicker_id = ?, logo_url = NULL WHERE id = ?", maxLevel, user.getId());

        String body = me(tokenOf(user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clicker.level").value(maxLevel))
                .andExpect(jsonPath("$.data.clicker.clickPower").value(maxPower))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> clicker = JsonPath.read(body, "$.data.clicker");
        Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(clicker).containsEntry("nextLevelRequiredScore", null);
        assertThat(data).containsEntry("profileImageUrl", null);
    }

    @Test
    @DisplayName("정지 회원도 조회할 수 있고 상태를 SUSPENDED로 알려 준다 (정지는 로그인 때만 막는다, 계획 Q3)")
    void suspendedUserSeesStatus() throws Exception {
        jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", user.getId());

        me(tokenOf(user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("토큰은 유효해도 탈퇴했거나 없는 회원이면 401 COMMON_UNAUTHORIZED")
    void withdrawnOrMissingUserIsUnauthorized() throws Exception {
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = now() WHERE id = ?", user.getId());

        me(tokenOf(user.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        me(tokenOf(Long.MAX_VALUE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("토큰이 없으면 401 COMMON_UNAUTHORIZED")
    void requiresToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    private String tokenOf(long userId) {
        return jwtProvider.createAccessToken(new AuthUser(userId, universityId, departmentId, Role.USER), null);
    }

    private ResultActions me(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }
}
