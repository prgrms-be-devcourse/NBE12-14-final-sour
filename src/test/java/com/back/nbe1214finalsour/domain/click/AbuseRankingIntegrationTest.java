package com.back.nbe1214finalsour.domain.click;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.IntStream;
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
 * 관리자 부정행위 순위 API를 PostgreSQL·Redis(Testcontainers)와 실제 HTTP 처리로 확인한다.
 *
 * <p>순위 키는 날짜별 키라 다른 테스트(예: 클릭 한도 초과)가 남긴 member가 섞일 수 있어 최근 3일 키를 통째로 지운다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AbuseRankingIntegrationTest {

    private static final String EMAIL = "abuser@scrum69-test.ac.kr";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    // DB에 없는 회원 ID. 닉네임은 null로 나온다
    private static final long GHOST_USER_A = 900_501L;
    private static final long GHOST_USER_B = 900_502L;

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

    private long universityId;
    private User user;

    @BeforeEach
    void setUp() {
        redis.delete(recentKeys());
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name, logo_url) VALUES ('부정행위테스트대', 'https://cdn.test/u/logo.png') RETURNING id",
                Long.class);
        long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        user = userRepository.saveAndFlush(User.create(universityId, departmentId, "매크로왕", EMAIL,
                emailHasher.hash(EMAIL), "{noop}unused", OffsetDateTime.now()));
    }

    @AfterEach
    void tearDown() {
        redis.delete(recentKeys());
        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
    }

    @Test
    @DisplayName("최근 3일 횟수를 합쳐 많은 순으로 보여 주고, 같으면 userId가 작은 순이다")
    void sumsRecentDaysAndSortsByCount() throws Exception {
        // user: 오늘 2 + 2일 전 1 = 3, A: 어제 3 = 3, B: 오늘 5. 3일 전 기록은 집계하지 않는다
        record(0, user.getId(), 2);
        record(2, user.getId(), 1);
        record(1, GHOST_USER_A, 3);
        record(0, GHOST_USER_B, 5);
        record(3, GHOST_USER_A, 100);

        ranking(adminToken(), 0, 20)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.content[0].rank").value(1))
                .andExpect(jsonPath("$.data.content[0].userId").value(GHOST_USER_B))
                .andExpect(jsonPath("$.data.content[0].abuseCount").value(5))
                .andExpect(jsonPath("$.data.content[0].nickname").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].userId").value(user.getId()))
                .andExpect(jsonPath("$.data.content[1].nickname").value("매크로왕"))
                .andExpect(jsonPath("$.data.content[1].abuseCount").value(3))
                .andExpect(jsonPath("$.data.content[2].userId").value(GHOST_USER_A))
                .andExpect(jsonPath("$.data.content[2].abuseCount").value(3));
    }

    @Test
    @DisplayName("page·size로 자르고, 순위는 페이지를 넘어 이어진다")
    void paginates() throws Exception {
        IntStream.rangeClosed(1, 5).forEach(i -> record(0, 900_600L + i, 10 - i));

        ranking(adminToken(), 1, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].rank").value(3))
                .andExpect(jsonPath("$.data.content[0].userId").value(900_603L))
                .andExpect(jsonPath("$.data.content[1].rank").value(4));

        ranking(adminToken(), 3, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("기록이 없으면 빈 목록을 돌려준다")
    void returnsEmptyWhenNoRecords() throws Exception {
        ranking(adminToken(), 0, 20)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("일반 회원은 403 COMMON_FORBIDDEN")
    void forbidsNonAdmin() throws Exception {
        String userToken = jwtProvider.createAccessToken(
                new AuthUser(user.getId(), universityId, user.getDepartmentId(), Role.USER), null);

        ranking(userToken, 0, 20)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
    }

    @Test
    @DisplayName("size가 100을 넘거나 page가 음수면 400")
    void rejectsInvalidPaging() throws Exception {
        ranking(adminToken(), 0, 101).andExpect(status().isBadRequest());
        ranking(adminToken(), -1, 20).andExpect(status().isBadRequest());
    }

    private void record(int daysAgo, long userId, int count) {
        redis.opsForZSet().incrementScore(key(daysAgo), String.valueOf(userId), count);
    }

    private static String key(int daysAgo) {
        return RedisKey.RANK_USER_ABUSE.of(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(daysAgo).format(DAY));
    }

    private static List<String> recentKeys() {
        return IntStream.rangeClosed(0, 3).mapToObj(AbuseRankingIntegrationTest::key).toList();
    }

    private String adminToken() {
        return jwtProvider.createAccessToken(new AuthUser(user.getId(), universityId, user.getDepartmentId(), Role.ADMIN), null);
    }

    private ResultActions ranking(String accessToken, int page, int size) throws Exception {
        return mockMvc.perform(get("/api/v1/admin/abuse-rankings")
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }
}
