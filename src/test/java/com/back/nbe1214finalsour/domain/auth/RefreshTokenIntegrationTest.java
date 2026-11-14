package com.back.nbe1214finalsour.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.auth.service.RefreshTokenService;
import com.back.nbe1214finalsour.domain.auth.service.TokenReissueService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 재발급(Rotation)·로그아웃과 쓰이지 않는 refresh 토큰 행 정리(SCRUM-72 A~C)를 PostgreSQL·Redis(Testcontainers)와
 * 실제 HTTP 처리로 확인한다 (회원 MVP 계획 단계 8).
 */
@IntegrationTest
@AutoConfigureMockMvc
class RefreshTokenIntegrationTest {

    private static final String DOMAIN = "scrum67-test.ac.kr";
    private static final String EMAIL = "student@" + DOMAIN;
    private static final String PASSWORD = "Passw0rd!";

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
    @Autowired
    private TokenReissueService tokenReissueService;

    private long universityId;
    private long departmentId;
    private User user;

    @BeforeEach
    void setUp() {
        clearRedis();
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('재발급테스트대') RETURNING id", Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        user = createUser("재발급왕", EMAIL);
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

    // ===== 재발급 =====

    @Test
    @DisplayName("재발급: 새 access 토큰과 새 refresh 쿠키를 주고, 이전 행은 지우고 새 행 하나만 남긴다")
    void reissueRotates() throws Exception {
        String oldToken = loginAndGetRefreshToken(null);

        MvcResult result = reissue(oldToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(3600))
                .andReturn();

        String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
        assertThat(jwtProvider.parseAccessToken(accessToken))
                .isEqualTo(new AuthUser(user.getId(), universityId, departmentId, Role.USER));
        String newToken = refreshTokenOf(result);
        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=2592000");
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(tokenHashes()).containsExactly(RefreshTokenService.hash(newToken));
    }

    @Test
    @DisplayName("재발급에 쓴 이전 쿠키로 다시 요청하면 401 AUTH_INVALID_REFRESH_TOKEN")
    void reusedTokenIsRejected() throws Exception {
        String oldToken = loginAndGetRefreshToken(null);
        reissue(oldToken).andExpect(status().isOk());

        reissue(oldToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_REFRESH_TOKEN"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("쿠키가 없거나 모르는 토큰이면 401 AUTH_INVALID_REFRESH_TOKEN")
    void missingOrUnknownTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/reissue"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_REFRESH_TOKEN"));
        reissue("unknown-token")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("만료된 토큰으로 재발급하면 401이고 그 행을 지운다 (SCRUM-72 C)")
    void expiredTokenIsRejectedAndDeleted() throws Exception {
        String token = loginAndGetRefreshToken(null);
        jdbcTemplate.update("UPDATE refresh_token SET expired_at = now() - interval '1 second' WHERE token = ?",
                RefreshTokenService.hash(token));

        reissue(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_REFRESH_TOKEN"));
        assertThat(tokenHashes()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SUSPENDED", "BANNED", "WITHDRAWN"})
    @DisplayName("로그인 뒤 정지·차단·탈퇴된 회원의 재발급은 401이고 그 행을 지운다")
    void inactiveUserCannotReissue(String status) throws Exception {
        String token = loginAndGetRefreshToken(null);
        jdbcTemplate.update("UPDATE users SET status = ?, deleted_at = CASE WHEN ? = 'WITHDRAWN' THEN now() END "
                + "WHERE id = ?", status, status, user.getId());

        reissue(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_REFRESH_TOKEN"));
        assertThat(tokenHashes()).isEmpty();
    }

    @Test
    @DisplayName("같은 토큰으로 동시에 5번 재발급하면 1번만 성공하고 남는 행도 1개")
    void concurrentReissueSucceedsOnce() throws Exception {
        String token = loginAndGetRefreshToken(null);
        int threads = 5;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<ErrorCode>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    tokenReissueService.reissue(token);
                    return null;
                } catch (BusinessException e) {
                    return e.getErrorCode();
                }
            }));
        }
        start.countDown();

        int succeeded = 0;
        List<ErrorCode> failures = new ArrayList<>();
        for (Future<ErrorCode> future : futures) {
            try {
                ErrorCode errorCode = future.get(30, TimeUnit.SECONDS);
                if (errorCode == null) {
                    succeeded++;
                } else {
                    failures.add(errorCode);
                }
            } catch (ExecutionException e) {
                throw new AssertionError("재발급 중 예상하지 못한 예외", e.getCause());
            }
        }
        executor.shutdownNow();

        assertThat(succeeded).isEqualTo(1);
        assertThat(failures).hasSize(threads - 1).containsOnly(ErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        assertThat(tokenHashes()).hasSize(1);
    }

    // ===== 로그아웃 =====

    @Test
    @DisplayName("로그아웃: 204, Max-Age=0 쿠키, 행 삭제. 그 뒤 같은 쿠키로 재발급하면 401")
    void logoutDeletesToken() throws Exception {
        String token = loginAndGetRefreshToken(null);

        logout(accessTokenOf(user), token)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        assertThat(tokenHashes()).isEmpty();

        reissue(token).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃은 access 토큰이 없으면 401이고 행을 지우지 않는다")
    void logoutRequiresAuthentication() throws Exception {
        String token = loginAndGetRefreshToken(null);

        mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("refreshToken", token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        assertThat(tokenHashes()).hasSize(1);
    }

    @Test
    @DisplayName("다른 회원의 refresh 쿠키로 로그아웃하면 204지만 그 행은 지우지 않는다")
    void logoutDoesNotDeleteOthersToken() throws Exception {
        String token = loginAndGetRefreshToken(null);
        User other = createUser("다른회원", "other@" + DOMAIN);

        logout(accessTokenOf(other), token).andExpect(status().isNoContent());

        assertThat(tokenHashes()).containsExactly(RefreshTokenService.hash(token));
    }

    // ===== SCRUM-72 A·B =====

    @Test
    @DisplayName("A: 같은 브라우저에서 다시 로그인하면 이전 쿠키의 행을 지운다 → 행 1개, 이전 쿠키로 재발급 401")
    void reloginInSameBrowserDeletesPreviousToken() throws Exception {
        String first = loginAndGetRefreshToken(null);
        String second = loginAndGetRefreshToken(first);

        assertThat(tokenHashes()).containsExactly(RefreshTokenService.hash(second));
        reissue(first).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A: 로그인이 실패하면 요청에 실린 이전 쿠키의 행을 지우지 않는다")
    void failedLoginKeepsPreviousToken() throws Exception {
        String token = loginAndGetRefreshToken(null);

        login("Wr0ngPass!", token).andExpect(status().isUnauthorized());

        assertThat(tokenHashes()).containsExactly(RefreshTokenService.hash(token));
    }

    @Test
    @DisplayName("로그인하면 그 회원의 행(다른 기기의 유효한 행·만료된 행)을 모두 지우고, 다른 회원의 행은 남긴다 (계정당 1기기, SCRUM-84)")
    void loginDeletesAllOwnTokensOnly() throws Exception {
        loginAndGetRefreshToken(null);
        insertToken(user.getId(), "a".repeat(64), OffsetDateTime.now().minusDays(1));
        User other = createUser("다른회원", "other@" + DOMAIN);
        insertToken(other.getId(), "b".repeat(64), OffsetDateTime.now().minusDays(1));

        String thisDevice = loginAndGetRefreshToken(null);

        assertThat(tokenHashes()).containsExactlyInAnyOrder(RefreshTokenService.hash(thisDevice), "b".repeat(64));
    }

    private User createUser(String nickname, String email) {
        return userRepository.saveAndFlush(User.create(universityId, departmentId, nickname, email,
                emailHasher.hash(email), passwordEncoder.encode(PASSWORD), OffsetDateTime.now()));
    }

    private String accessTokenOf(User target) {
        return jwtProvider.createAccessToken(new AuthUser(target.getId(), universityId, departmentId, Role.USER), null);
    }

    private ResultActions login(String password, String previousToken) throws Exception {
        var request = post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}");
        if (previousToken != null) {
            request.cookie(new Cookie("refreshToken", previousToken));
        }
        return mockMvc.perform(request);
    }

    private String loginAndGetRefreshToken(String previousToken) throws Exception {
        return refreshTokenOf(login(PASSWORD, previousToken).andExpect(status().isOk()).andReturn());
    }

    private ResultActions reissue(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reissue").cookie(new Cookie("refreshToken", token)));
    }

    private ResultActions logout(String accessToken, String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + accessToken)
                .cookie(new Cookie("refreshToken", refreshToken)));
    }

    private static String refreshTokenOf(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("refreshToken=");
        return setCookie.substring("refreshToken=".length(), setCookie.indexOf(';'));
    }

    private void insertToken(Long userId, String tokenHash, OffsetDateTime expiredAt) {
        jdbcTemplate.update("INSERT INTO refresh_token (user_id, token, expired_at) VALUES (?, ?, ?)",
                userId, tokenHash, expiredAt);
    }

    private List<String> tokenHashes() {
        return jdbcTemplate.queryForList(
                "SELECT token FROM refresh_token WHERE user_id IN (SELECT id FROM users WHERE university_id = ?)",
                String.class, universityId);
    }

    private void clearRedis() {
        Set<String> keys = redis.keys("auth:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
