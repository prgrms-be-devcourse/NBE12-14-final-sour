package com.back.nbe1214finalsour.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 회원 탈퇴 (추가 명세 §3-1, SCRUM-79)를 PostgreSQL·Redis(Testcontainers)와 실제 HTTP 처리로 확인한다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class WithdrawalIntegrationTest {

    private static final String DOMAIN = "scrum79-test.ac.kr";
    private static final String EMAIL = "student@" + DOMAIN;
    private static final String PASSWORD = "Passw0rd!";
    private static final String NICKNAME = "탈퇴왕";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private EmailHasher emailHasher;
    @Autowired
    private JwtProvider jwtProvider;

    private long universityId;
    private long departmentId;
    private User user;

    @BeforeEach
    void setUp() {
        clearRedis();
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('탈퇴테스트대') RETURNING id", Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        jdbcTemplate.update("INSERT INTO uni_domain (university_id, domain) VALUES (?, ?)", universityId, DOMAIN);
        user = createUser(NICKNAME, EMAIL);
    }

    @AfterEach
    void tearDown() {
        clearRedis();
        jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id IN (SELECT id FROM users WHERE university_id = ?)",
                universityId);
        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM uni_domain WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
    }

    @Test
    @DisplayName("탈퇴: 204·쿠키 만료, WITHDRAWN + deleted_at, 모든 기기의 refresh 토큰 삭제, 이후 내 정보·로그인·재발급이 401")
    void withdraw() throws Exception {
        String deviceA = loginAndGetRefreshToken();
        String deviceB = loginAndGetRefreshToken();
        String accessToken = accessTokenOf(user);

        withdraw(accessToken, PASSWORD)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, deleted_at FROM users WHERE id = ?", user.getId());
        assertThat(row.get("status")).isEqualTo("WITHDRAWN");
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(tokenHashesOf(user.getId())).isEmpty();

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        login().andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        reissue(deviceA).andExpect(status().isUnauthorized());
        reissue(deviceB).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("탈퇴한 이메일·닉네임으로 바로 재가입할 수 있고, 새 계정은 새 ID로 0점부터 시작한다")
    void canSignupAgainWithSameEmailAndNickname() throws Exception {
        jdbcTemplate.update("UPDATE users SET total_contrib_score = 500, total_attack_score = 7 WHERE id = ?",
                user.getId());
        withdraw(accessTokenOf(user), PASSWORD).andExpect(status().isNoContent());

        MvcResult result = mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signupToken\":\"" + signupToken(EMAIL) + "\",\"nickname\":\"" + NICKNAME
                                + "\",\"password\":\"" + PASSWORD + "\",\"departmentId\":" + departmentId + "}"))
                .andExpect(status().isCreated())
                .andReturn();

        long newUserId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.userId"))
                .longValue();
        assertThat(newUserId).isNotEqualTo(user.getId());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, total_contrib_score, total_attack_score FROM users WHERE id = ?", newUserId);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) row.get("total_contrib_score")).longValue()).isZero();
        assertThat(((Number) row.get("total_attack_score")).longValue()).isZero();
        login().andExpect(status().isOk());
    }

    @Test
    @DisplayName("비밀번호가 다르면 400 USER_INVALID_PASSWORD이고 탈퇴되지 않으며 refresh 토큰도 남는다")
    void wrongPasswordKeepsAccount() throws Exception {
        String token = loginAndGetRefreshToken();

        withdraw(accessTokenOf(user), "Wr0ngPass!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_INVALID_PASSWORD"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(statusOf(user.getId())).isEqualTo("ACTIVE");
        reissue(token).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SUSPENDED", "BANNED"})
    @DisplayName("정지·차단 회원은 403 USER_SUSPENDED이고 탈퇴되지 않는다 (탈퇴 후 재가입으로 정지를 피하는 것 방지)")
    void suspendedUserCannotWithdraw(String userStatus) throws Exception {
        jdbcTemplate.update("UPDATE users SET status = ? WHERE id = ?", userStatus, user.getId());

        withdraw(accessTokenOf(user), PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_SUSPENDED"));

        assertThat(statusOf(user.getId())).isEqualTo(userStatus);
    }

    @Test
    @DisplayName("이미 탈퇴한 회원의 토큰이면 401 COMMON_UNAUTHORIZED")
    void alreadyWithdrawnUserIsUnauthorized() throws Exception {
        String accessToken = accessTokenOf(user);
        withdraw(accessToken, PASSWORD).andExpect(status().isNoContent());

        withdraw(accessToken, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("다른 회원은 영향을 받지 않는다")
    void otherUserIsNotAffected() throws Exception {
        User other = createUser("다른회원", "other@" + DOMAIN);
        insertToken(other.getId(), "b".repeat(64));

        withdraw(accessTokenOf(user), PASSWORD).andExpect(status().isNoContent());

        assertThat(statusOf(other.getId())).isEqualTo("ACTIVE");
        assertThat(tokenHashesOf(other.getId())).containsExactly("b".repeat(64));
    }

    private User createUser(String nickname, String email) {
        return userRepository.saveAndFlush(User.create(universityId, departmentId, nickname, email,
                emailHasher.hash(email), passwordEncoder.encode(PASSWORD), OffsetDateTime.now()));
    }

    private String accessTokenOf(User target) {
        return jwtProvider.createAccessToken(new AuthUser(target.getId(), universityId, departmentId, Role.USER));
    }

    private ResultActions withdraw(String accessToken, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/users/me/withdrawal")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + password + "\"}"));
    }

    private ResultActions login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    private String loginAndGetRefreshToken() throws Exception {
        String setCookie = login().andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("refreshToken=");
        return setCookie.substring("refreshToken=".length(), setCookie.indexOf(';'));
    }

    private ResultActions reissue(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reissue").cookie(new Cookie("refreshToken", token)));
    }

    /** 이메일 인증을 마친 상태를 만든다 (코드 확인이 저장하는 값과 같다. {@code SignupIntegrationTest}와 같은 방식) */
    private String signupToken(String email) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set("auth:signup:" + token, email);
        return token;
    }

    private void insertToken(Long userId, String tokenHash) {
        jdbcTemplate.update("INSERT INTO refresh_token (user_id, token, expired_at) VALUES (?, ?, ?)",
                userId, tokenHash, OffsetDateTime.now().plusDays(1));
    }

    private List<String> tokenHashesOf(Long userId) {
        return jdbcTemplate.queryForList("SELECT token FROM refresh_token WHERE user_id = ?", String.class, userId);
    }

    private String statusOf(Long userId) {
        return jdbcTemplate.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId);
    }

    private void clearRedis() {
        Set<String> keys = redis.keys("auth:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
