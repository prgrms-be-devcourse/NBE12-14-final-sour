package com.back.nbe1214finalsour.domain.click;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.hamcrest.Matchers;
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

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 클릭 제출 API를 PostgreSQL·Redis(Testcontainers)와 실제 HTTP 처리로 확인한다.
 *
 * <p>기본 클리커(레벨 1)의 clickPower는 1이라, 배율이 없으면 인정 점수 = clickCount다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ClickIntegrationTest {

    private static final String EMAIL = "clicker@click-test.ac.kr";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private RedisHandler redisHandler;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailHasher emailHasher;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private ClickerService clickerService;

    private long universityId;
    private long departmentId;
    /** 토큰의 학과 ID가 DB 값 대신 쓰이는지 확인할 때만 만든다 */
    private Long otherDepartmentId;
    private User user;
    private String token;

    @BeforeEach
    void setUp() {
        clickerService.loadLevelTable();

        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name, logo_url) VALUES ('클릭테스트대', 'https://cdn.test/u.png') RETURNING id",
                Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        user = userRepository.saveAndFlush(User.create(universityId, departmentId, "클릭왕", EMAIL,
                emailHasher.hash(EMAIL), "{noop}unused", OffsetDateTime.now()));
        token = jwtProvider.createAccessToken(new AuthUser(user.getId(), universityId, departmentId, Role.USER));
    }

    @AfterEach
    void tearDown() {
        // 공유 키는 이 테스트가 넣은 member만, 전용 키는 통째로 지운다
        redis.opsForZSet().remove(RedisKey.RANK_UNIV.of(), String.valueOf(universityId));
        redis.opsForSet().remove(RedisKey.DIRTY_UNIV.of(), String.valueOf(universityId));
        redis.opsForSet().remove(RedisKey.DIRTY_DEPT.of(), String.valueOf(departmentId));
        if (otherDepartmentId != null) {
            redis.opsForSet().remove(RedisKey.DIRTY_DEPT.of(), String.valueOf(otherDepartmentId));
        }
        redis.opsForSet().remove(RedisKey.DIRTY_USER.of(), String.valueOf(user.getId()));
        redis.delete(List.of(
                RedisKey.RANK_DEPT.of(universityId),
                RedisKey.RANK_USER.of(universityId),
                RedisKey.USER_STATUS.of(user.getId()),
                RedisKey.USER_CLICKER.of(user.getId()),
                RedisKey.USER_EFFECT.of(user.getId()),
                RedisKey.RATELIMIT_ACTION.of(user.getId()),
                RedisKey.RATELIMIT_CLICK.of(user.getId())));

        long nowSec = Instant.now().getEpochSecond();
        redis.delete(LongStream.rangeClosed(nowSec - 8, nowSec + 2)
                .mapToObj(sec -> RedisKey.CLICKS_UNIV_SECOND.of(universityId, sec))
                .toList());

        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
    }

    @Test
    @DisplayName("클릭을 제출하면 200과 점수를 응답하고, Redis의 순위·배치 대상에 반영된다")
    void submitClickAppliesScoreAndReturnsResponse() throws Exception {
        click("sub-1", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.acceptedClicks").value(3))
                .andExpect(jsonPath("$.data.earnedScore").value(3))
                .andExpect(jsonPath("$.data.multiplier").value(1.0))
                .andExpect(jsonPath("$.data.myContribScore").value(3))
                .andExpect(jsonPath("$.data.university.id").value(universityId))
                .andExpect(jsonPath("$.data.university.totalScore").value(3))
                .andExpect(jsonPath("$.data.department.id").value(departmentId))
                .andExpect(jsonPath("$.data.department.totalScore").value(3))
                .andExpect(jsonPath("$.data.schoolClicksLastSecond").value(0));

        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), String.valueOf(universityId)))
                .isEqualTo(3.0);
        assertThat(redis.opsForZSet().score(RedisKey.RANK_USER.of(universityId), String.valueOf(user.getId())))
                .isEqualTo(3.0);
        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_USER.of(), String.valueOf(user.getId()))).isTrue();
    }

    @Test
    @DisplayName("학과 점수는 토큰의 학과 ID로 반영한다 (회원을 DB에서 다시 읽지 않음)")
    void departmentScoreUsesDepartmentFromToken() throws Exception {
        otherDepartmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '전자공학과') RETURNING id", Long.class,
                universityId);
        String tokenWithOtherDepartment = jwtProvider.createAccessToken(
                new AuthUser(user.getId(), universityId, otherDepartmentId, Role.USER));
        // 첫 클릭은 레벨 캐시를 채우려고 회원을 읽으므로, 캐시가 있는 상태에서 확인한다
        redis.opsForValue().set(RedisKey.USER_CLICKER.of(user.getId()), "1");

        click(tokenWithOtherDepartment, "sub-1", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.department.id").value(otherDepartmentId))
                .andExpect(jsonPath("$.data.department.totalScore").value(3));

        assertThat(redis.opsForZSet().score(RedisKey.RANK_DEPT.of(universityId), String.valueOf(otherDepartmentId)))
                .isEqualTo(3.0);
        assertThat(redis.opsForZSet().score(RedisKey.RANK_DEPT.of(universityId), String.valueOf(departmentId)))
                .isNull();
    }

    @Test
    @DisplayName("학과 claim이 없는 토큰(배포 전 발급)이면 DB의 학과 ID로 반영한다")
    void tokenWithoutDepartmentFallsBackToDb() throws Exception {
        String legacyToken = jwtProvider.createAccessToken(new AuthUser(user.getId(), universityId, null, Role.USER));
        redis.opsForValue().set(RedisKey.USER_CLICKER.of(user.getId()), "1");

        click(legacyToken, "sub-1", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.department.id").value(departmentId))
                .andExpect(jsonPath("$.data.department.totalScore").value(3));
    }

    @Test
    @DisplayName("학교 순위는 1위부터 센다 (0이 아니다)")
    void universityRankStartsAtOne() throws Exception {
        click("sub-1", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.university.rank").value(Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("클릭을 두 번 제출하면 기여도가 누적된다")
    void repeatedClicksAccumulate() throws Exception {
        click("sub-1", 3).andExpect(status().isOk());

        click("sub-2", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.earnedScore").value(2))
                .andExpect(jsonPath("$.data.myContribScore").value(5));
    }

    @Test
    @DisplayName("첫 클릭에서 클리커 레벨 캐시를 평문 숫자로 채운다")
    void firstClickWarmsClickerLevelCache() throws Exception {
        assertThat(redis.opsForValue().get(RedisKey.USER_CLICKER.of(user.getId()))).isNull();

        click("sub-1", 3).andExpect(status().isOk());

        assertThat(redis.opsForValue().get(RedisKey.USER_CLICKER.of(user.getId()))).isEqualTo("1");
    }

    @Test
    @DisplayName("정지된 회원이 클릭하면 403 USER_SUSPENDED, 점수는 반영되지 않는다")
    void suspendedUserIsRejected() throws Exception {
        redis.opsForValue().set(RedisKey.USER_STATUS.of(user.getId()), "SUSPENDED");

        click("sub-1", 3)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_SUSPENDED"));

        assertThat(redis.opsForZSet().score(RedisKey.RANK_USER.of(universityId), String.valueOf(user.getId())))
                .isNull();
    }

    @Test
    @DisplayName("같은 submissionId로 다시 제출하면 409 CLICK_DUPLICATE_SUBMISSION, 점수는 오르지 않는다")
    void duplicateSubmissionIsRejected() throws Exception {
        click("sub-dup", 3).andExpect(status().isOk());

        click("sub-dup", 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLICK_DUPLICATE_SUBMISSION"));

        assertThat(redis.opsForZSet().score(RedisKey.RANK_USER.of(universityId), String.valueOf(user.getId())))
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("10초에 12회까지 통과하고 13회째는 429 CLICK_TOO_FREQUENT")
    void rejectsThirteenthRequestWithinWindow() throws Exception {
        for (int i = 1; i <= 12; i++) {
            click("sub-" + i, 1).andExpect(status().isOk());
        }

        click("sub-13", 1)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CLICK_TOO_FREQUENT"));

        // 한도에 걸린 요청의 점수는 반영되지 않는다
        assertThat(redis.opsForZSet().score(RedisKey.RANK_USER.of(universityId), String.valueOf(user.getId())))
                .isEqualTo(12.0);
    }

    @Test
    @DisplayName("1분 동안 clickCount 합계가 900을 넘으면 429 CLICK_COUNT_EXCEEDED, 점수는 반영되지 않는다")
    void rejectsWhenClickCountExceedsLimitWithinWindow() throws Exception {
        // 요청 한도(10초 12회 × 최대 20클릭) 안에서는 900을 채울 수 없으므로 합계를 미리 넣어 둔다
        redis.opsForValue().set(RedisKey.RATELIMIT_CLICK.of(user.getId()), "890", Duration.ofSeconds(60));

        click("sub-1", 10).andExpect(status().isOk());

        click("sub-2", 1)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CLICK_COUNT_EXCEEDED"));

        assertThat(redis.opsForZSet().score(RedisKey.RANK_USER.of(universityId), String.valueOf(user.getId())))
                .isEqualTo(10.0);
    }

    @Test
    @DisplayName("첫 클릭에서 클릭 수 한도 키에 clickCount와 60초 만료를 건다")
    void firstClickSetsClickCountWithTtl() throws Exception {
        click("sub-1", 7).andExpect(status().isOk());

        assertThat(redis.opsForValue().get(RedisKey.RATELIMIT_CLICK.of(user.getId()))).isEqualTo("7");
        assertThat(redis.getExpire(RedisKey.RATELIMIT_CLICK.of(user.getId()))).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("RedisHandler가 문자열로 쓴 아이템 배율도 적용된다 (JSON 따옴표 호환)")
    void appliesItemMultiplierWrittenAsString() throws Exception {
        // 아이템 팀이 문자열로 쓰면 Redis에는 "1.5"(따옴표 포함)로 저장된다
        redisHandler.setHash(RedisKey.USER_EFFECT.of(user.getId()), "multiplier", "1.5");
        assertThat(redis.<String, String>opsForHash().get(RedisKey.USER_EFFECT.of(user.getId()), "multiplier"))
                .isEqualTo("\"1.5\"");

        click("sub-1", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.multiplier").value(1.5))
                // floor(3 * 1 * 1.5) = 4
                .andExpect(jsonPath("$.data.earnedScore").value(4));
    }

    @Test
    @DisplayName("숫자로 쓴 아이템 배율도 적용된다")
    void appliesItemMultiplierWrittenAsNumber() throws Exception {
        redisHandler.setHash(RedisKey.USER_EFFECT.of(user.getId()), "multiplier", 2.0);

        click("sub-1", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.multiplier").value(2.0))
                .andExpect(jsonPath("$.data.earnedScore").value(6));
    }

    @Test
    @DisplayName("토큰이 없으면 401 COMMON_UNAUTHORIZED")
    void requiresToken() throws Exception {
        mockMvc.perform(post("/api/v1/clicks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionId":"sub-1","clickCount":3}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("clickCount가 1~20을 벗어나거나 submissionId가 비면 400 COMMON_INVALID_INPUT")
    void rejectsInvalidRequest() throws Exception {
        click("sub-1", 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
        click("sub-2", 21)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
        click("  ", 3)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
    }

    private ResultActions click(String submissionId, int clickCount) throws Exception {
        return click(token, submissionId, clickCount);
    }

    private ResultActions click(String accessToken, String submissionId, int clickCount) throws Exception {
        return mockMvc.perform(post("/api/v1/clicks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"submissionId":"%s","clickCount":%d}""".formatted(submissionId, clickCount)));
    }
}
