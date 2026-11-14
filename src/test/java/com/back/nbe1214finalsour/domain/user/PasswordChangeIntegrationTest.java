package com.back.nbe1214finalsour.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 비밀번호 변경 (추가 명세 §3-1, SCRUM-78)을 PostgreSQL·Redis(Testcontainers)와 실제 HTTP 처리로 확인한다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class PasswordChangeIntegrationTest {

    private static final String DOMAIN = "scrum78-test.ac.kr";
    private static final String EMAIL = "student@" + DOMAIN;
    private static final String PASSWORD = "Passw0rd!";
    private static final String NEW_PASSWORD = "N3wPassw0rd!";

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
                "INSERT INTO university (name) VALUES ('비번변경테스트대') RETURNING id", Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        user = createUser("비번바꿈", EMAIL);
    }

    @AfterEach
    void tearDown() {
        clearRedis();
        jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id IN (SELECT id FROM users WHERE university_id = ?)",
                universityId);
        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
    }

    @Test
    @DisplayName("변경 후 새 비밀번호로만 로그인되고, 다른 기기를 포함한 모든 refresh 토큰이 지워져 재발급이 401")
    void changePasswordRevokesAllRefreshTokens() throws Exception {
        String deviceA = loginAndGetRefreshToken(PASSWORD);
        String deviceB = loginAndGetRefreshToken(PASSWORD);
        User other = createUser("다른회원", "other@" + DOMAIN);
        insertToken(other.getId(), "b".repeat(64));

        changePassword(accessTokenOf(user), PASSWORD, NEW_PASSWORD)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        assertThat(tokenHashesOf(user)).isEmpty();
        assertThat(tokenHashesOf(other)).containsExactly("b".repeat(64));
        reissue(deviceA).andExpect(status().isUnauthorized());
        reissue(deviceB).andExpect(status().isUnauthorized());
        login(PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        login(NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("현재 비밀번호가 다르면 400 USER_INVALID_PASSWORD이고 비밀번호와 refresh 토큰이 그대로다")
    void wrongCurrentPasswordKeepsEverything() throws Exception {
        String token = loginAndGetRefreshToken(PASSWORD);

        changePassword(accessTokenOf(user), "Wr0ngPass!", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_INVALID_PASSWORD"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(tokenHashesOf(user)).hasSize(1);
        reissue(token).andExpect(status().isOk());
        assertThat(passwordEncoder.matches(PASSWORD, storedPassword())).isTrue();
    }

    @Test
    @DisplayName("새 비밀번호가 현재와 같아도 204 (계획 Q1)")
    void samePasswordIsAllowed() throws Exception {
        changePassword(accessTokenOf(user), PASSWORD, PASSWORD).andExpect(status().isNoContent());

        login(PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("저장된 비밀번호는 {bcrypt} 접두어의 BCrypt 해시이고 원문이 아니다 (PasswordConfig의 DelegatingPasswordEncoder)")
    void storesEncodedPassword() throws Exception {
        changePassword(accessTokenOf(user), PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        String stored = storedPassword();
        assertThat(stored).isNotEqualTo(NEW_PASSWORD).startsWith("{bcrypt}$2");
        assertThat(passwordEncoder.matches(NEW_PASSWORD, stored)).isTrue();
    }

    @Test
    @DisplayName("탈퇴한 회원의 토큰이면 401 COMMON_UNAUTHORIZED (내 정보와 같은 기준)")
    void withdrawnUserIsUnauthorized() throws Exception {
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = now() WHERE id = ?", user.getId());

        changePassword(accessTokenOf(user), PASSWORD, NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("access 토큰이 없으면 401 COMMON_UNAUTHORIZED")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    private User createUser(String nickname, String email) {
        return userRepository.saveAndFlush(User.create(universityId, departmentId, nickname, email,
                emailHasher.hash(email), passwordEncoder.encode(PASSWORD), OffsetDateTime.now()));
    }

    private String accessTokenOf(User target) {
        return jwtProvider.createAccessToken(new AuthUser(target.getId(), universityId, departmentId, Role.USER), null);
    }

    private ResultActions changePassword(String accessToken, String currentPassword, String newPassword)
            throws Exception {
        return mockMvc.perform(patch("/api/v1/users/me/password")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(currentPassword, newPassword)));
    }

    private static String body(String currentPassword, String newPassword) {
        return "{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + newPassword + "\"}";
    }

    private ResultActions login(String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}"));
    }

    private String loginAndGetRefreshToken(String password) throws Exception {
        MvcResult result = login(password).andExpect(status().isOk()).andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("refreshToken=");
        return setCookie.substring("refreshToken=".length(), setCookie.indexOf(';'));
    }

    private ResultActions reissue(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reissue").cookie(new Cookie("refreshToken", token)));
    }

    private void insertToken(Long userId, String tokenHash) {
        jdbcTemplate.update("INSERT INTO refresh_token (user_id, token, expired_at) VALUES (?, ?, ?)",
                userId, tokenHash, OffsetDateTime.now().plusDays(1));
    }

    private List<String> tokenHashesOf(User target) {
        return jdbcTemplate.queryForList("SELECT token FROM refresh_token WHERE user_id = ?", String.class,
                target.getId());
    }

    private String storedPassword() {
        return jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, user.getId());
    }

    private void clearRedis() {
        Set<String> keys = redis.keys("auth:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
