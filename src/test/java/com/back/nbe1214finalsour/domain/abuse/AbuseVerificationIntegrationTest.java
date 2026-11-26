package com.back.nbe1214finalsour.domain.abuse;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.abuse.service.AbuseVerificationService;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisKey;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 클릭 어뷰징 의심 표시와 봇 확인 API를 PostgreSQL·Redis(Testcontainers)와 실제 HTTP 처리로 확인한다 (SCRUM-118, 계획 §5).
 *
 * <p>Cloudflare는 부르지 않는다. {@link TurnstileVerifier}를 목으로 바꿔 켜진 상태와 세 가지 결과를 흉내 낸다.
 * MockMvc 요청의 클라이언트 IP는 {@code 127.0.0.1}이다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AbuseVerificationIntegrationTest {

    private static final String EMAIL = "abuser@abuse-test.ac.kr";
    private static final String ACTION = AbuseVerificationService.CLICK_ACTION;

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

    @Autowired
    private TurnstileVerifier turnstileVerifier;

    private long universityId;
    private long departmentId;
    private User user;
    private String token;
    private String flagKey;

    @BeforeEach
    void setUp() {
        clickerService.loadLevelTable();
        given(turnstileVerifier.enabled()).willReturn(true);

        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name, logo_url) VALUES ('어뷰징테스트대', 'https://cdn.test/a.png') RETURNING id",
                Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '보안학과') RETURNING id", Long.class,
                universityId);
        user = userRepository.saveAndFlush(User.create(universityId, departmentId, "봇의심", EMAIL,
                emailHasher.hash(EMAIL), "{noop}unused", OffsetDateTime.now()));
        token = jwtProvider.createAccessToken(new AuthUser(user.getId(), universityId, departmentId, Role.USER), null);
        flagKey = RedisKey.ABUSE_VERIFY_USER.of(user.getId());
    }

    @AfterEach
    void tearDown() {
        String userId = String.valueOf(user.getId());
        redis.opsForZSet().remove(RedisKey.RANK_UNIV.of(), String.valueOf(universityId));
        redis.opsForZSet().remove(RedisKey.RANK_USER_ABUSE.of(today()), userId);
        redis.opsForSet().remove(RedisKey.DIRTY_UNIV.of(), String.valueOf(universityId));
        redis.opsForSet().remove(RedisKey.DIRTY_DEPT.of(), String.valueOf(departmentId));
        redis.opsForSet().remove(RedisKey.DIRTY_USER.of(), userId);
        redis.delete(List.of(
                flagKey,
                RedisKey.RATELIMIT_ABUSE_VERIFY.of(user.getId()),
                RedisKey.RANK_DEPT.of(universityId),
                RedisKey.RANK_USER.of(universityId),
                RedisKey.USER_STATUS.of(user.getId()),
                RedisKey.USER_CLICKER.of(user.getId()),
                RedisKey.USER_EFFECT.of(user.getId()),
                RedisKey.RATELIMIT_ACTION.of(user.getId()),
                RedisKey.RATELIMIT_CLICK.of(user.getId())));
        long nowSec = Instant.now().getEpochSecond();
        redis.delete(LongStream.rangeClosed(nowSec - 30, nowSec + 2)
                .mapToObj(sec -> RedisKey.CLICKS_UNIV_SECOND.of(universityId, sec))
                .toList());

        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
    }

    @Test
    @DisplayName("요청 한도를 넘으면 429와 함께 24시간 표시가 생기고, 그 뒤 클릭은 403이며 점수·submissionId·한도 카운터가 바뀌지 않는다")
    void actionLimitFlagsAndBlocksClicks() throws Exception {
        for (int i = 1; i <= 12; i++) {
            click("sub-" + i, 1).andExpect(status().isOk());
        }
        click("sub-13", 1)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CLICK_TOO_FREQUENT"));

        assertThat(redis.hasKey(flagKey)).isTrue();
        assertThat(redis.getExpire(flagKey, TimeUnit.HOURS)).isBetween(23L, 24L);

        click("sub-14", 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ABUSE_VERIFICATION_REQUIRED"));

        assertThat(redis.hasKey(RedisKey.SUBMIT_DEDUPE.of(user.getId(), "sub-14"))).isFalse();
        assertThat(redis.opsForZSet().score(RedisKey.RANK_USER.of(universityId), String.valueOf(user.getId())))
                .isEqualTo(120.0);
        assertThat(redis.opsForValue().get(RedisKey.RATELIMIT_ACTION.of(user.getId()))).isEqualTo("13");
    }

    @Test
    @DisplayName("클릭 수 한도를 넘어도 표시가 생기고, 부정행위 순위 기록은 지금과 같다")
    void clickCountLimitFlagsAndKeepsAbuseRank() throws Exception {
        redis.opsForValue().set(RedisKey.RATELIMIT_CLICK.of(user.getId()), "895");

        click("sub-1", 10)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CLICK_COUNT_EXCEEDED"));

        assertThat(redis.hasKey(flagKey)).isTrue();
        assertThat(redis.opsForZSet().score(RedisKey.RANK_USER_ABUSE.of(today()), String.valueOf(user.getId())))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("확인을 통과하면 204, 표시와 클릭 한도 카운터가 지워져 바로 클릭할 수 있다")
    void passedClearsFlagAndClickWorks() throws Exception {
        flagAfterActionLimit();
        given(turnstileVerifier.verify("real-token", ACTION, "127.0.0.1")).willReturn(TurnstileResult.PASSED);

        verification("{\"turnstileToken\":\"real-token\"}").andExpect(status().isNoContent());

        assertThat(redis.hasKey(flagKey)).isFalse();
        click("sub-after", 1).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Cloudflare가 거절하면 403 ABUSE_VERIFICATION_FAILED이고 표시가 남는다")
    void rejectedKeepsFlag() throws Exception {
        redis.opsForValue().set(flagKey, "1");
        given(turnstileVerifier.verify("fake", ACTION, "127.0.0.1")).willReturn(TurnstileResult.REJECTED);

        verification("{\"turnstileToken\":\"fake\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ABUSE_VERIFICATION_FAILED"));

        assertThat(redis.hasKey(flagKey)).isTrue();
        click("sub-1", 1).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("토큰 없이 불러도 확인한다. Cloudflare에 닿지 못하면 204로 통과시키고 표시를 지운다 (fail-open, P5·P6)")
    void unavailableWithoutTokenClearsFlag() throws Exception {
        redis.opsForValue().set(flagKey, "1");
        given(turnstileVerifier.verify(null, ACTION, "127.0.0.1")).willReturn(TurnstileResult.UNAVAILABLE);

        verification("{}").andExpect(status().isNoContent());

        assertThat(redis.hasKey(flagKey)).isFalse();
    }

    @Test
    @DisplayName("표시가 없을 때 불러도 204이고 클릭 한도 카운터는 그대로다 (멱등)")
    void verificationWithoutFlagIsIdempotent() throws Exception {
        redis.opsForValue().set(RedisKey.RATELIMIT_ACTION.of(user.getId()), "5");
        given(turnstileVerifier.verify(any(), eq(ACTION), any())).willReturn(TurnstileResult.PASSED);

        verification("{\"turnstileToken\":\"t\"}").andExpect(status().isNoContent());

        assertThat(redis.opsForValue().get(RedisKey.RATELIMIT_ACTION.of(user.getId()))).isEqualTo("5");
    }

    @Test
    @DisplayName("확인 API는 회원당 10분에 10번까지다. 11번째는 Cloudflare를 부르지 않고 429")
    void verificationRateLimit() throws Exception {
        given(turnstileVerifier.verify(any(), eq(ACTION), any())).willReturn(TurnstileResult.PASSED);

        for (int i = 0; i < 10; i++) {
            verification("{\"turnstileToken\":\"t\"}").andExpect(status().isNoContent());
        }
        verification("{\"turnstileToken\":\"t\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COMMON_TOO_MANY_REQUESTS"));

        verify(turnstileVerifier, times(10)).verify(any(), eq(ACTION), any());
    }

    @Test
    @DisplayName("2048자를 넘는 토큰은 400이고 Cloudflare를 부르지 않는다")
    void tooLongTokenIsInvalidInput() throws Exception {
        verification("{\"turnstileToken\":\"" + "x".repeat(2049) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));

        verify(turnstileVerifier, never()).verify(any(), any(), any());
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/api/v1/abuse/verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private void flagAfterActionLimit() throws Exception {
        for (int i = 1; i <= 13; i++) {
            click("pre-" + i, 1);
        }
        assertThat(redis.hasKey(flagKey)).isTrue();
    }

    private ResultActions click(String submissionId, int clickCount) throws Exception {
        return mockMvc.perform(post("/api/v1/clicks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"submissionId\":\"%s\",\"clickCount\":%d}".formatted(submissionId, clickCount)));
    }

    private ResultActions verification(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/abuse/verification")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String today() {
        return LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }
}
