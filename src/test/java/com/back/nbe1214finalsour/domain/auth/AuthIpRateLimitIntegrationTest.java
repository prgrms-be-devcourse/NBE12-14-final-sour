package com.back.nbe1214finalsour.domain.auth;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.auth.mail.VerificationMailSender;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisKey;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인·인증 코드 요청의 IP 기준 한도 (SCRUM-100). 기본 설정값(로그인 실패 30번/10분, 인증 코드 20번/1시간)으로 확인한다.
 *
 * <p>MockMvc는 Tomcat {@code RemoteIpValve}를 거치지 않으므로 {@code remoteAddr}를 직접 넣는다. 프록시 헤더 처리는
 * {@code ClientIpIntegrationTest}가 실제 서버로 확인한다. 넣은 행과 Redis {@code auth:*} 키는 테스트마다 지운다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AuthIpRateLimitIntegrationTest {

    private static final String DOMAIN = "scrum100-test.ac.kr";
    private static final String EMAIL = "student@" + DOMAIN;
    private static final String PASSWORD = "Passw0rd!";
    private static final String WRONG_PASSWORD = "Wr0ngPass!";
    private static final String IP_A = "203.0.113.7";
    private static final String IP_B = "198.51.100.9";
    private static final int LOGIN_MAX_FAILURES = 30;
    private static final int EMAIL_CODE_MAX_REQUESTS = 20;

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
    private VerificationMailSender mailSender;

    private long universityId;

    @BeforeEach
    void setUp() {
        clearRedis();
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('IP한도테스트대') RETURNING id", Long.class);
        long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        jdbcTemplate.update("INSERT INTO uni_domain (university_id, domain) VALUES (?, ?)", universityId, DOMAIN);
        userRepository.saveAndFlush(User.create(universityId, departmentId, "아이피왕", EMAIL,
                emailHasher.hash(EMAIL), passwordEncoder.encode(PASSWORD), OffsetDateTime.now()));
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

    // ===== 로그인 =====

    @Test
    @DisplayName("로그인: 한 IP에서 서로 다른 이메일로 30번 실패하면 31번째는 맞는 비밀번호여도 429 AUTH_TOO_MANY_REQUESTS이고 이메일 실패 횟수는 늘지 않는다")
    void loginBlockedAfterIpFailures() throws Exception {
        for (int i = 1; i <= LOGIN_MAX_FAILURES; i++) {
            login(IP_A, "nobody" + i + "@" + DOMAIN, WRONG_PASSWORD)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        }

        login(IP_A, EMAIL, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_TOO_MANY_REQUESTS"));
        assertThat(redis.hasKey(RedisKey.AUTH_LOGIN_FAIL.of(emailHasher.hash(EMAIL)))).isFalse();
    }

    @Test
    @DisplayName("로그인: 다른 IP는 영향을 받지 않는다")
    void otherIpNotAffected() throws Exception {
        redis.opsForValue().set(loginKey(IP_A), String.valueOf(LOGIN_MAX_FAILURES));

        login(IP_A, EMAIL, PASSWORD).andExpect(status().isTooManyRequests());
        login(IP_B, EMAIL, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("로그인: 실패만 세고 성공은 세지 않는다. 횟수 키는 첫 실패부터 10분 뒤 사라진다")
    void onlyFailuresCountedWithWindow() throws Exception {
        login(IP_A, EMAIL, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        login(IP_A, EMAIL, PASSWORD).andExpect(status().isOk());
        login(IP_A, EMAIL, PASSWORD).andExpect(status().isOk());

        assertThat(redis.opsForValue().get(loginKey(IP_A))).isEqualTo("1");
        assertThat(redis.getExpire(loginKey(IP_A))).isBetween(590L, 600L);
    }

    @Test
    @DisplayName("로그인: 정지 회원은 비밀번호가 맞으므로 실패로 세지 않는다")
    void suspendedLoginNotCounted() throws Exception {
        jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE university_id = ?", universityId);

        login(IP_A, EMAIL, PASSWORD).andExpect(status().isForbidden());

        assertThat(redis.hasKey(loginKey(IP_A))).isFalse();
    }

    @Test
    @DisplayName("로그인: 이메일 잠금(429 AUTH_LOGIN_LOCKED)에 걸린 요청도 IP 실패로 센다")
    void emailLockedCountsAsIpFailure() throws Exception {
        for (int i = 1; i <= 6; i++) {
            login(IP_A, EMAIL, WRONG_PASSWORD);
        }

        assertThat(redis.opsForValue().get(loginKey(IP_A))).isEqualTo("6");
    }

    @Test
    @DisplayName("로그인: IPv6 주소도 센다 (Redis 키에는 IP 원문 대신 해시를 넣어 ':'가 섞이지 않는다)")
    void ipv6AddressIsHashed() throws Exception {
        String ipv6 = "2001:db8::1";

        login(ipv6, EMAIL, WRONG_PASSWORD).andExpect(status().isUnauthorized());

        assertThat(redis.opsForValue().get(loginKey(ipv6))).isEqualTo("1");
        assertThat(redis.keys("auth:ip:*")).allSatisfy(key -> assertThat(key).doesNotContain("2001").doesNotContain("203.0"));
    }

    // ===== 인증 코드 요청 =====

    @Test
    @DisplayName("인증 코드: 한 IP에서 서로 다른 이메일로 20번 요청하면 21번째는 429 AUTH_TOO_MANY_REQUESTS이고 메일을 보내지 않는다. 다른 IP는 영향 없음")
    void emailCodeBlockedAfterIpRequests() throws Exception {
        for (int i = 1; i <= EMAIL_CODE_MAX_REQUESTS; i++) {
            requestCode(IP_A, "new" + i + "@" + DOMAIN).andExpect(status().isOk());
        }

        requestCode(IP_A, "new21@" + DOMAIN)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_TOO_MANY_REQUESTS"));
        verify(mailSender, times(EMAIL_CODE_MAX_REQUESTS)).sendCode(anyString(), anyString());
        verify(mailSender, never()).sendCode(eq("new21@" + DOMAIN), anyString());
        assertThat(redis.getExpire(emailCodeKey(IP_A))).isBetween(3590L, 3600L);

        requestCode(IP_B, "new21@" + DOMAIN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("인증 코드: 이미 가입된 이메일 조회도 IP 한도에 들어간다 (가입 여부 대량 조회 방지)")
    void registeredEmailLookupCounted() throws Exception {
        requestCode(IP_A, EMAIL)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_ALREADY_REGISTERED"));

        assertThat(redis.opsForValue().get(emailCodeKey(IP_A))).isEqualTo("1");
    }

    private ResultActions login(String ip, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions requestCode(String ip, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/email/code")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private String loginKey(String ip) {
        return RedisKey.AUTH_IP_LOGIN_FAIL.of(emailHasher.hashClientIp(ip));
    }

    private String emailCodeKey(String ip) {
        return RedisKey.AUTH_IP_EMAIL_CODE.of(emailHasher.hashClientIp(ip));
    }

    private void clearRedis() {
        Set<String> keys = redis.keys("auth:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
