package com.back.nbe1214finalsour.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import com.back.nbe1214finalsour.domain.support.repository.CustomerSupportRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProperties;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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

/**
 * 문의 등록 {@code POST /api/v1/supports} (추가 명세 §3-8, SCRUM-110)를 PostgreSQL·Redis(Testcontainers)와 실제 HTTP
 * 처리로 확인한다. 기본 한도(1시간 5건)를 쓴다.
 *
 * <p>MockMvc는 Tomcat {@code RemoteIpValve}를 거치지 않으므로 {@code remoteAddr}를 직접 넣는다. 넣은 행과 Redis
 * {@code ratelimit:support:*} 키는 테스트마다 지운다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SupportCreateIntegrationTest {

    private static final String TITLE_PREFIX = "SCRUM-110 ";
    private static final String EMAIL = "me@example.com";
    private static final String CDN = "https://cdn.devcproject.cloud";
    private static final String IP_A = "203.0.113.7";
    private static final String IP_B = "198.51.100.9";
    private static final int MAX_REQUESTS = 5;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private CustomerSupportRepository repository;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private EmailHasher emailHasher;

    private long universityId;
    private long memberId;
    private long otherMemberId;
    private String memberToken;
    private String otherMemberToken;

    @BeforeEach
    void setUp() {
        clearRedis();
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('문의등록테스트대') RETURNING id", Long.class);
        long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        memberId = insertUser(departmentId, "문의회원", "a");
        otherMemberId = insertUser(departmentId, "다른회원", "b");
        memberToken = jwtProvider.createAccessToken(new AuthUser(memberId, universityId, departmentId, Role.USER), null);
        otherMemberToken = jwtProvider.createAccessToken(
                new AuthUser(otherMemberId, universityId, departmentId, Role.USER), null);
    }

    @AfterEach
    void tearDown() {
        clearRedis();
        jdbcTemplate.update("DELETE FROM customer_support WHERE title LIKE ?", TITLE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
    }

    @Test
    @DisplayName("비회원 문의는 201 RECEIVED이고 user_id 없이, 수신 이메일은 앞뒤 공백을 지운 뒤 암호문으로 저장된다")
    void guestCreates() throws Exception {
        long id = supportId(create(null, IP_A, body("  " + EMAIL + " ", "비회원 문의", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("RECEIVED")));

        Map<String, Object> row = row(id);
        assertThat(row.get("user_id")).isNull();
        assertThat(row.get("attach_file_url")).isNull();
        assertThat((String) row.get("recipient_email")).isNotEqualTo(EMAIL).doesNotContain("example");
        assertThat(row).containsEntry("status", "RECEIVED").containsEntry("content", "내용입니다");

        CustomerSupport saved = repository.findById(id).orElseThrow();
        assertThat(saved.getRecipientEmail()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("회원 문의는 user_id를 기록하고 내 support 경로의 첨부를 저장한다")
    void memberCreatesWithAttachment() throws Exception {
        String url = CDN + "/support/" + memberId + "/a1b2.png";

        long id = supportId(create(memberToken, IP_A, body(EMAIL, "회원 문의", url))
                .andExpect(status().isCreated()));

        assertThat(row(id)).containsEntry("user_id", memberId).containsEntry("attach_file_url", url);
    }

    @Test
    @DisplayName("비회원이 첨부를 보내면 400 SUPPORT_ATTACHMENT_NOT_ALLOWED이고 저장·한도 소모가 없다")
    void guestAttachmentRejected() throws Exception {
        create(null, IP_A, body(EMAIL, "비회원 첨부", CDN + "/support/1/a.png"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPORT_ATTACHMENT_NOT_ALLOWED"));

        assertThat(count()).isZero();
        assertThat(redis.keys("ratelimit:support:*")).isEmpty();
    }

    @Test
    @DisplayName("회원이 다른 회원 경로·외부 주소를 첨부하면 400 FILE_INVALID_URL이고 저장되지 않는다")
    void invalidAttachmentRejected() throws Exception {
        create(memberToken, IP_A, body(EMAIL, "다른 회원 경로", CDN + "/support/" + otherMemberId + "/a.png"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_INVALID_URL"));
        create(memberToken, IP_A, body(EMAIL, "외부 주소", "https://evil.example.com/support/" + memberId + "/a.png"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_INVALID_URL"));

        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("만료된 토큰을 보내면 비회원으로 넘기지 않고 401 COMMON_EXPIRED_TOKEN이며 저장되지 않는다 (계획 Q4)")
    void expiredTokenRejected() throws Exception {
        create(expiredToken(), IP_A, body(EMAIL, "만료 토큰", null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_EXPIRED_TOKEN"));

        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("위조된 토큰을 보내면 401 COMMON_INVALID_TOKEN")
    void invalidTokenRejected() throws Exception {
        create("not-a-jwt", IP_A, body(EMAIL, "위조 토큰", null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_TOKEN"));

        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("입력 검증: 제목이 비었거나 100자를 넘거나, 이메일 형식이 틀리면 400 COMMON_INVALID_INPUT")
    void validation() throws Exception {
        create(null, IP_A, body(EMAIL, "", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
        create(null, IP_A, body(EMAIL, "가".repeat(101 - TITLE_PREFIX.length()), null))
                .andExpect(status().isBadRequest());
        create(null, IP_A, body("not-an-email", "이메일 형식", null)).andExpect(status().isBadRequest());

        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("비회원은 같은 IP에서 1시간에 5건까지, 6번째는 429 COMMON_TOO_MANY_REQUESTS. 다른 IP와 회원은 영향 없음")
    void guestLimitedPerIp() throws Exception {
        for (int i = 1; i <= MAX_REQUESTS; i++) {
            create(null, IP_A, body(EMAIL, "비회원 " + i, null)).andExpect(status().isCreated());
        }

        create(null, IP_A, body(EMAIL, "비회원 초과", null))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COMMON_TOO_MANY_REQUESTS"));
        create(null, IP_B, body(EMAIL, "다른 IP", null)).andExpect(status().isCreated());
        create(memberToken, IP_A, body(EMAIL, "같은 IP 회원", null)).andExpect(status().isCreated());

        assertThat(count()).isEqualTo(MAX_REQUESTS + 2);
        assertThat(redis.getExpire("ratelimit:support:ip:" + emailHasher.hashClientIp(IP_A))).isBetween(1L, 3600L);
    }

    @Test
    @DisplayName("회원은 IP와 관계없이 회원별로 1시간에 5건까지, 같은 IP의 다른 회원은 영향 없음")
    void memberLimitedPerUser() throws Exception {
        for (int i = 1; i <= MAX_REQUESTS; i++) {
            create(memberToken, i % 2 == 0 ? IP_A : IP_B, body(EMAIL, "회원 " + i, null))
                    .andExpect(status().isCreated());
        }

        create(memberToken, "192.0.2.1", body(EMAIL, "회원 초과", null))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COMMON_TOO_MANY_REQUESTS"));
        create(otherMemberToken, IP_A, body(EMAIL, "같은 IP 다른 회원", null)).andExpect(status().isCreated());

        assertThat(count()).isEqualTo(MAX_REQUESTS + 1);
        assertThat(redis.keys("ratelimit:support:*"))
                .contains("ratelimit:support:user:" + memberId, "ratelimit:support:user:" + otherMemberId)
                .noneMatch(key -> key.startsWith("ratelimit:support:ip:"));
    }

    private ResultActions create(String accessToken, String ip, String body) throws Exception {
        var builder = post("/api/v1/supports")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
        if (accessToken != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return mockMvc.perform(builder);
    }

    /** 제목 앞에 {@link #TITLE_PREFIX}를 붙여 정리 대상으로 표시한다. 빈 제목은 그대로 보낸다 */
    private String body(String recipientEmail, String title, String attachFileUrl) {
        Map<String, String> body = new HashMap<>();
        body.put("recipientEmail", recipientEmail);
        body.put("title", title.isEmpty() ? "" : TITLE_PREFIX + title);
        body.put("content", "내용입니다");
        if (attachFileUrl != null) {
            body.put("attachFileUrl", attachFileUrl);
        }
        return jsonMapper.writeValueAsString(body);
    }

    private long supportId(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString())
                .path("data").path("supportId").asLong();
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM customer_support WHERE id = ?", id);
    }

    private int count() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customer_support WHERE title LIKE ?", Integer.class, TITLE_PREFIX + "%");
    }

    private long insertUser(long departmentId, String nickname, String hashChar) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at) "
                        + "VALUES (?, ?, ?, 'enc', ?, 'pw', now()) RETURNING id",
                Long.class, universityId, departmentId, nickname, hashChar.repeat(64));
    }

    private String expiredToken() {
        Instant past = Instant.now().minusSeconds(3600);
        return Jwts.builder()
                .subject(String.valueOf(memberId)).claim("universityId", universityId).claim("role", "USER")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private void clearRedis() {
        Set<String> keys = redis.keys("ratelimit:support:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
