package com.back.nbe1214finalsour.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.auth.service.RefreshTokenService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 계정당 1기기(SCRUM-83)를 로그인 → 보호 API → 재발급 흐름(SCRUM-86)과 로그아웃·탈퇴 정리(SCRUM-87)로 확인한다. 보호 API는 {@code GET /api/v1/users/me}.
 *
 * <p>넣은 행과 Redis {@code auth:*} 키는 테스트마다 지운다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SingleSessionIntegrationTest {

    private static final String DOMAIN = "scrum86-test.ac.kr";
    private static final String EMAIL = "student@" + DOMAIN;
    private static final String PASSWORD = "Passw0rd!";
    private static final String ME = "/api/v1/users/me";

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
    private User user;

    @BeforeEach
    void setUp() {
        clearRedis();
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('세션테스트대') RETURNING id", Long.class);
        long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        user = userRepository.saveAndFlush(User.create(universityId, departmentId, "세션왕", EMAIL,
                emailHasher.hash(EMAIL), passwordEncoder.encode(PASSWORD), OffsetDateTime.now()));
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
    @DisplayName("A 로그인 → B 로그인: A는 보호 API 401 AUTH_SESSION_REPLACED·재발급 401, B는 보호 API와 재발급 뒤 보호 API 모두 200")
    void lastLoginWins() throws Exception {
        MvcResult deviceA = login().andExpect(status().isOk()).andReturn();
        getMe(accessTokenOf(deviceA)).andExpect(status().isOk());

        MvcResult deviceB = login().andExpect(status().isOk()).andReturn();

        getMe(accessTokenOf(deviceA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_REPLACED"));
        reissue(refreshTokenOf(deviceA)).andExpect(status().isUnauthorized());

        getMe(accessTokenOf(deviceB)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(user.getId()));
        MvcResult reissuedB = reissue(refreshTokenOf(deviceB)).andExpect(status().isOk()).andReturn();
        getMe(accessTokenOf(reissuedB)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Redis에 현재 세션이 없으면 보호 API 401 COMMON_EXPIRED_TOKEN, 재발급하면 세션이 복구되어 이전·새 access 토큰 모두 200")
    void missingSessionKeyRecoversByReissue() throws Exception {
        MvcResult login = login().andExpect(status().isOk()).andReturn();
        redis.delete(RedisKey.AUTH_SESSION.of(user.getId()));

        getMe(accessTokenOf(login))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_EXPIRED_TOKEN"));

        MvcResult reissued = reissue(refreshTokenOf(login)).andExpect(status().isOk()).andReturn();
        getMe(accessTokenOf(reissued)).andExpect(status().isOk());
        getMe(accessTokenOf(login)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("sid 없는 access 토큰(SCRUM-84 배포 전 발급)은 다른 세션이 있어도 통과한다 (계획 Q3)")
    void tokenWithoutSessionStillPasses() throws Exception {
        login().andExpect(status().isOk());
        String legacy = jwtProvider.createAccessToken(
                new AuthUser(user.getId(), universityId, user.getDepartmentId(), Role.USER), null);

        getMe(legacy).andExpect(status().isOk());
        assertThat(redis.hasKey(RedisKey.AUTH_SESSION.of(user.getId()))).isTrue();
    }

    @Test
    @DisplayName("로그아웃하면 현재 세션 키를 지워, 남은 access 토큰도 바로 401 COMMON_EXPIRED_TOKEN (재발급할 refresh 토큰도 없음) (SCRUM-87)")
    void logoutEndsSession() throws Exception {
        MvcResult login = login().andExpect(status().isOk()).andReturn();

        logout(accessTokenOf(login), refreshTokenOf(login)).andExpect(status().isNoContent());

        assertThat(redis.hasKey(sessionKey())).isFalse();
        getMe(accessTokenOf(login))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_EXPIRED_TOKEN"));
        reissue(refreshTokenOf(login)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("다른 세션의 refresh 토큰으로 로그아웃하면 그 행만 지우고 현재 세션은 지우지 않는다 (SCRUM-87)")
    void logoutWithStaleSessionKeepsCurrentSession() throws Exception {
        MvcResult current = login().andExpect(status().isOk()).andReturn();
        String currentSessionId = redis.opsForValue().get(sessionKey());
        String staleToken = "stale-device-refresh-token";
        jdbcTemplate.update("INSERT INTO refresh_token (user_id, token, expired_at, session_id) VALUES (?, ?, ?, ?)",
                user.getId(), RefreshTokenService.hash(staleToken), OffsetDateTime.now().plus(1, ChronoUnit.DAYS),
                UUID.randomUUID());
        // 배포 전(sid 없음) access 토큰은 필터를 통과하므로, 끊긴 세션의 refresh 쿠키로 로그아웃을 부를 수 있다
        String legacyAccessToken = jwtProvider.createAccessToken(
                new AuthUser(user.getId(), universityId, user.getDepartmentId(), Role.USER), null);

        logout(legacyAccessToken, staleToken).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_token WHERE token = ?", Integer.class,
                RefreshTokenService.hash(staleToken))).isZero();
        assertThat(redis.opsForValue().get(sessionKey())).isEqualTo(currentSessionId);
        getMe(accessTokenOf(current)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("탈퇴하면 커밋 뒤 세션 키를 지워, 남은 access 토큰은 바로 401 COMMON_EXPIRED_TOKEN (SCRUM-87)")
    void withdrawalEndsSession() throws Exception {
        MvcResult login = login().andExpect(status().isOk()).andReturn();

        mockMvc.perform(post("/api/v1/users/me/withdrawal")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenOf(login))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(redis.hasKey(sessionKey())).isFalse();
        getMe(accessTokenOf(login))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_EXPIRED_TOKEN"));
    }

    private ResultActions login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    private ResultActions getMe(String accessToken) throws Exception {
        return mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private ResultActions logout(String accessToken, String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .cookie(new Cookie("refreshToken", refreshToken)));
    }

    private ResultActions reissue(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reissue").cookie(new Cookie("refreshToken", refreshToken)));
    }

    private static String accessTokenOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
    }

    private static String refreshTokenOf(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        return setCookie.substring("refreshToken=".length(), setCookie.indexOf(';'));
    }

    private String sessionKey() {
        return RedisKey.AUTH_SESSION.of(user.getId());
    }

    private void clearRedis() {
        Set<String> keys = redis.keys("auth:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
