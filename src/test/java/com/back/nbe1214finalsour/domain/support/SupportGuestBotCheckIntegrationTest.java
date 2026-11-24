package com.back.nbe1214finalsour.domain.support;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.support.service.SupportBotCheck;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import com.back.nbe1214finalsour.global.turnstile.TurnstileResult;
import com.back.nbe1214finalsour.global.turnstile.TurnstileVerifier;
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
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 비회원 문의 의심 구간의 봇 확인을 PostgreSQL·Redis(Testcontainers)와 실제 HTTP 처리로 확인한다 (SCRUM-119, 계획 §5 문의).
 *
 * <p>{@link TurnstileVerifier}를 목으로 바꿔 켜진 상태와 결과를 흉내 낸다. 기본값: 같은 IP 1시간 3번째부터 확인, 5건 한도.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SupportGuestBotCheckIntegrationTest {

    private static final String TITLE_PREFIX = "SCRUM-119 ";
    private static final String EMAIL = "guest@example.com";
    private static final String IP = "203.0.113.50";
    private static final String ACTION = SupportBotCheck.GUEST_ACTION;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private EmailHasher emailHasher;
    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private TurnstileVerifier turnstileVerifier;

    private String ipKey;

    @BeforeEach
    void setUp() {
        clearRedis();
        given(turnstileVerifier.enabled()).willReturn(true);
        ipKey = "ratelimit:support:ip:" + emailHasher.hashClientIp(IP);
    }

    @AfterEach
    void tearDown() {
        clearRedis();
        jdbcTemplate.update("DELETE FROM customer_support WHERE title LIKE ?", TITLE_PREFIX + "%");
    }

    @Test
    @DisplayName("1~2건째는 토큰 없이 201, 3건째는 토큰 없으면 403 REQUIRED이고 저장·IP 카운트가 늘지 않는다. 토큰을 붙여 다시 보내면 201")
    void thirdGuestInquiryRequiresToken() throws Exception {
        guest(null, "1").andExpect(status().isCreated());
        guest(null, "2").andExpect(status().isCreated());
        verify(turnstileVerifier, never()).verify(any(), any(), any());

        guest(null, "3")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ABUSE_VERIFICATION_REQUIRED"));
        assertThat(count()).isEqualTo(2);
        assertThat(redis.opsForValue().get(ipKey)).isEqualTo("2");

        given(turnstileVerifier.verify("real-token", ACTION, IP)).willReturn(TurnstileResult.PASSED);
        guest("real-token", "3").andExpect(status().isCreated());
        assertThat(count()).isEqualTo(3);
        assertThat(redis.opsForValue().get(ipKey)).isEqualTo("3");
    }

    @Test
    @DisplayName("Cloudflare가 거절하면 403 ABUSE_VERIFICATION_FAILED이고 저장·IP 카운트가 늘지 않는다")
    void rejectedTokenFails() throws Exception {
        redis.opsForValue().set(ipKey, "2");
        given(turnstileVerifier.verify("fake", ACTION, IP)).willReturn(TurnstileResult.REJECTED);

        guest("fake", "거절")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ABUSE_VERIFICATION_FAILED"));

        assertThat(count()).isZero();
        assertThat(redis.opsForValue().get(ipKey)).isEqualTo("2");
    }

    @Test
    @DisplayName("위젯을 못 불러와 빈 토큰을 보내도 Cloudflare에 닿지 못하면 201로 통과시킨다 (fail-open, P6)")
    void emptyTokenUnavailablePasses() throws Exception {
        redis.opsForValue().set(ipKey, "2");
        given(turnstileVerifier.verify("", ACTION, IP)).willReturn(TurnstileResult.UNAVAILABLE);

        guest("", "연결불가").andExpect(status().isCreated());

        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("6건째는 확인 없이 지금처럼 429 COMMON_TOO_MANY_REQUESTS")
    void sixthGuestInquiryStillTooManyRequests() throws Exception {
        redis.opsForValue().set(ipKey, "5");

        guest(null, "초과")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COMMON_TOO_MANY_REQUESTS"));

        verify(turnstileVerifier, never()).verify(any(), any(), any());
    }

    @Test
    @DisplayName("회원 문의는 바뀌지 않는다. 같은 IP에 비회원 문의가 몰려 있어도 토큰 없이 201")
    void memberUnaffected() throws Exception {
        long universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('봇확인문의테스트대') RETURNING id", Long.class);
        long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        long memberId = jdbcTemplate.queryForObject(
                "INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at) "
                        + "VALUES (?, ?, '봇확인회원', 'enc', ?, 'pw', now()) RETURNING id",
                Long.class, universityId, departmentId, "c".repeat(64));
        try {
            redis.opsForValue().set(ipKey, "4");
            String token = jwtProvider.createAccessToken(
                    new AuthUser(memberId, universityId, departmentId, Role.USER), null);

            create(token, body(null, "회원")).andExpect(status().isCreated());

            verify(turnstileVerifier, never()).verify(any(), any(), any());
            assertThat(redis.opsForValue().get(ipKey)).isEqualTo("4");
        } finally {
            jdbcTemplate.update("DELETE FROM customer_support WHERE user_id = ?", memberId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", memberId);
            jdbcTemplate.update("DELETE FROM department WHERE id = ?", departmentId);
            jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
        }
    }

    @Test
    @DisplayName("2048자를 넘는 토큰은 400 COMMON_INVALID_INPUT")
    void tooLongToken() throws Exception {
        guest("x".repeat(2049), "긴토큰")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
    }

    private ResultActions guest(String turnstileToken, String title) throws Exception {
        return create(null, body(turnstileToken, title));
    }

    private ResultActions create(String accessToken, String body) throws Exception {
        var builder = post("/api/v1/supports")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(request -> {
                    request.setRemoteAddr(IP);
                    return request;
                });
        if (accessToken != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return mockMvc.perform(builder);
    }

    /** {@code turnstileToken}이 null이면 필드를 넣지 않는다 */
    private String body(String turnstileToken, String title) {
        Map<String, String> body = new HashMap<>();
        body.put("recipientEmail", EMAIL);
        body.put("title", TITLE_PREFIX + title);
        body.put("content", "내용입니다");
        if (turnstileToken != null) {
            body.put("turnstileToken", turnstileToken);
        }
        return jsonMapper.writeValueAsString(body);
    }

    private int count() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customer_support WHERE title LIKE ?", Integer.class, TITLE_PREFIX + "%");
    }

    private void clearRedis() {
        Set<String> keys = redis.keys("ratelimit:support:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
