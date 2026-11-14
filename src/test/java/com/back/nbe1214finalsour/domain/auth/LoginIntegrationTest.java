package com.back.nbe1214finalsour.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.auth.service.LoginService;
import com.back.nbe1214finalsour.domain.auth.service.RefreshTokenService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * 로그인을 PostgreSQL·Redis(Testcontainers)와 실제 HTTP 처리로 확인한다 (회원 MVP 계획 단계 7).
 *
 * <p>넣은 행과 Redis {@code auth:*} 키는 테스트마다 지운다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class LoginIntegrationTest {

    private static final String DOMAIN = "scrum66-test.ac.kr";
    private static final String EMAIL = "student@" + DOMAIN;
    private static final String PASSWORD = "Passw0rd!";
    private static final String WRONG_PASSWORD = "Wr0ngPass!";

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
    private LoginService loginService;

    private long universityId;
    private long departmentId;
    private User user;

    @BeforeEach
    void setUp() {
        clearRedis();
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('로그인테스트대') RETURNING id", Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        user = userRepository.saveAndFlush(User.create(universityId, departmentId, "로그인왕", EMAIL,
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
    @DisplayName("성공: access 토큰·회원을 응답하고, refresh 토큰 쿠키 속성 5개를 붙이며, DB에는 쿠키 값의 SHA-256과 30일 뒤 만료를 저장")
    void loginSucceeds() throws Exception {
        MvcResult result = login("  Student@SCRUM66-Test.ac.kr", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(3600))
                .andExpect(jsonPath("$.data.user.id").value(user.getId()))
                .andExpect(jsonPath("$.data.user.nickname").value("로그인왕"))
                .andExpect(jsonPath("$.data.user.universityId").value(universityId))
                .andExpect(jsonPath("$.data.user.role").value("USER"))
                .andReturn();

        String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
        assertThat(jwtProvider.parseAccessToken(accessToken))
                .isEqualTo(new AuthUser(user.getId(), universityId, departmentId, Role.USER));

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("refreshToken=")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=2592000");
        String refreshToken = setCookie.substring("refreshToken=".length(), setCookie.indexOf(';'));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT token, expired_at FROM refresh_token WHERE user_id = ?", user.getId());
        assertThat(row.get("token")).isEqualTo(RefreshTokenService.hash(refreshToken)).isNotEqualTo(refreshToken);
        OffsetDateTime expiredAt = ((Timestamp) row.get("expired_at")).toInstant().atOffset(ZoneOffset.UTC);
        assertThat(expiredAt).isCloseTo(OffsetDateTime.now().plusDays(30), within(1, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("다시 로그인하면 refresh 토큰 행을 하나 더 만든다 (기기마다 로그인)")
    void loginTwiceKeepsBothRefreshTokens() throws Exception {
        login(EMAIL, PASSWORD).andExpect(status().isOk());
        login(EMAIL, PASSWORD).andExpect(status().isOk());

        assertThat(refreshTokenCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("틀린 비밀번호와 없는 이메일은 같은 401 AUTH_INVALID_CREDENTIALS 본문으로 응답하고 쿠키를 보내지 않는다")
    void wrongPasswordAndUnknownEmailLookTheSame() throws Exception {
        String wrongPassword = login(EMAIL, WRONG_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login("nobody@" + DOMAIN, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownEmail).isEqualTo(wrongPassword);
        assertThat(refreshTokenCount()).isZero();
    }

    @Test
    @DisplayName("5번째 실패는 429 AUTH_LOGIN_LOCKED. 잠긴 10분 동안은 맞는 비밀번호도 429이고 토큰을 발급하지 않는다")
    void fifthFailureLocks() throws Exception {
        for (int i = 1; i <= 4; i++) {
            login(EMAIL, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        }
        login(EMAIL, WRONG_PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_LOCKED"));

        login(EMAIL, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_LOCKED"));
        assertThat(refreshTokenCount()).isZero();

        String hash = emailHasher.hash(EMAIL);
        assertThat(redis.getExpire("auth:login:lock:" + hash, TimeUnit.SECONDS)).isBetween(590L, 600L);
        assertThat(redis.hasKey("auth:login:fail:" + hash)).isFalse();
    }

    @Test
    @DisplayName("성공하면 실패 횟수를 지운다: 4번 실패 → 성공 → 다시 4번 실패해도 잠기지 않는다")
    void successResetsFailures() throws Exception {
        for (int i = 1; i <= 4; i++) {
            login(EMAIL, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        }
        login(EMAIL, PASSWORD).andExpect(status().isOk());

        for (int i = 1; i <= 4; i++) {
            login(EMAIL, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        }
        login(EMAIL, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("가입하지 않은 이메일도 5번째 실패에 잠긴다 (잠금 여부로 가입 여부가 드러나지 않음)")
    void unknownEmailAlsoLocks() throws Exception {
        String unknown = "nobody@" + DOMAIN;
        for (int i = 1; i <= 4; i++) {
            login(unknown, PASSWORD).andExpect(status().isUnauthorized());
        }
        login(unknown, PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("같은 이메일로 틀린 비밀번호 10개를 동시에 보내면 비밀번호 확인은 5번만 하고 나머지는 429 (4번 401, 6번 429)")
    void concurrentFailuresCannotExceedLimit() throws Exception {
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<ErrorCode>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    loginService.login(EMAIL, WRONG_PASSWORD, null);
                    return null;
                } catch (BusinessException e) {
                    return e.getErrorCode();
                }
            }));
        }
        start.countDown();

        List<ErrorCode> results = new ArrayList<>();
        for (Future<ErrorCode> future : futures) {
            try {
                results.add(future.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                throw new AssertionError("로그인 중 예상하지 못한 예외", e.getCause());
            }
        }
        executor.shutdownNow();

        assertThat(results.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting())))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        ErrorCode.AUTH_INVALID_CREDENTIALS, 4L,
                        ErrorCode.AUTH_LOGIN_LOCKED, 6L));
    }

    @Test
    @DisplayName("탈퇴한 회원은 맞는 비밀번호여도 401 AUTH_INVALID_CREDENTIALS (일반 실패와 같음)")
    void withdrawnUserIsInvalidCredentials() throws Exception {
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = now() WHERE id = ?", user.getId());

        login(EMAIL, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("탈퇴 후 같은 이메일로 다시 가입한 회원은 새 계정으로 로그인한다")
    void rejoinedUserLogsInAsNewAccount() throws Exception {
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = now() WHERE id = ?", user.getId());
        User rejoined = userRepository.saveAndFlush(User.create(universityId, departmentId, "재가입왕", EMAIL,
                emailHasher.hash(EMAIL), passwordEncoder.encode(PASSWORD), OffsetDateTime.now()));

        login(EMAIL, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(rejoined.getId()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SUSPENDED", "BANNED"})
    @DisplayName("정지·차단 회원은 맞는 비밀번호면 403 USER_SUSPENDED이고 토큰을 발급하지 않는다. 틀린 비밀번호면 401 (상태를 드러내지 않음)")
    void suspendedUserIsForbidden(String status) throws Exception {
        jdbcTemplate.update("UPDATE users SET status = ? WHERE id = ?", status, user.getId());

        login(EMAIL, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_SUSPENDED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        login(EMAIL, WRONG_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        assertThat(refreshTokenCount()).isZero();
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private int refreshTokenCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id IN (SELECT id FROM users WHERE university_id = ?)",
                Integer.class, universityId);
    }

    private void clearRedis() {
        Set<String> keys = redis.keys("auth:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
