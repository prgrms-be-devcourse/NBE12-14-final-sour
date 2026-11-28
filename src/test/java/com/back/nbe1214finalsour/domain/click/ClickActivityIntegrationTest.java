package com.back.nbe1214finalsour.domain.click;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 학교 직전 1초 활동 조회 API({@code GET /api/v1/universities/{id}/activity})를 실제 Redis·HTTP 처리로 확인한다.
 *
 * <p>서버는 요청을 받은 시각의 직전 초를 읽는다. 요청 중에 초가 바뀌어도 결과가 같도록, 값을 넣는 테스트는
 * 직전 초와 이번 초 키에 같은 값을 둔다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ClickActivityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private UniversityCache universityCache;

    private long universityId;

    @BeforeEach
    void setUp() {
        // 학교는 서버가 뜬 뒤에 넣으므로 캐시를 다시 불러와야 조회 대상으로 찾을 수 있다
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('활동조회테스트대') RETURNING id", Long.class);
        universityCache.load();
    }

    @AfterEach
    void tearDown() {
        long nowSec = Instant.now().getEpochSecond();
        redis.delete(LongStream.rangeClosed(nowSec - 8, nowSec + 2)
                .boxed()
                .flatMap(sec -> Stream.of(
                        RedisKey.CLICKS_UNIV_SECOND.of(universityId, sec),
                        RedisKey.ATTACKS_UNIV_SECOND.of(universityId, sec),
                        RedisKey.ASSISTS_UNIV_SECOND.of(universityId, sec)))
                .toList());
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
        universityCache.load();
    }

    @Test
    @DisplayName("활동이 없던 학교는 클릭·공격·지원이 모두 0이고, second는 서버 현재 초 − 1이다")
    void returnsZerosWhenNoActivity() throws Exception {
        // given: 초별 키 없음
        long before = Instant.now().getEpochSecond();

        // when
        ResultActions result = activity(universityId);

        // then
        long after = Instant.now().getEpochSecond();
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.clicks").value(0))
                .andExpect(jsonPath("$.data.attacks").value(0))
                .andExpect(jsonPath("$.data.assists").value(0))
                .andExpect(jsonPath("$.data.second").value(
                        allOf(greaterThanOrEqualTo(before - 1), lessThanOrEqualTo(after - 1)), Long.class));
    }

    @Test
    @DisplayName("직전 1초의 클릭·공격·지원 수를 섞지 않고 그대로 돌려준다")
    void returnsPreviousSecondCounts() throws Exception {
        // given: 서로 섞이면 드러나도록 세 값을 모두 다르게 둔다
        long nowSec = Instant.now().getEpochSecond();
        for (long sec : List.of(nowSec - 1, nowSec)) {
            redis.opsForValue().set(RedisKey.CLICKS_UNIV_SECOND.of(universityId, sec), "12");
            redis.opsForValue().set(RedisKey.ATTACKS_UNIV_SECOND.of(universityId, sec), "3");
            redis.opsForValue().set(RedisKey.ASSISTS_UNIV_SECOND.of(universityId, sec), "7");
        }

        // when
        ResultActions result = activity(universityId);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clicks").value(12))
                .andExpect(jsonPath("$.data.attacks").value(3))
                .andExpect(jsonPath("$.data.assists").value(7));
    }

    @Test
    @DisplayName("일부 종류만 있으면 없는 종류는 0이다")
    void missingKindIsZero() throws Exception {
        // given: 받은 공격만 있음
        long nowSec = Instant.now().getEpochSecond();
        for (long sec : List.of(nowSec - 1, nowSec)) {
            redis.opsForValue().set(RedisKey.ATTACKS_UNIV_SECOND.of(universityId, sec), "5");
        }

        // when
        ResultActions result = activity(universityId);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clicks").value(0))
                .andExpect(jsonPath("$.data.attacks").value(5))
                .andExpect(jsonPath("$.data.assists").value(0));
    }

    @Test
    @DisplayName("이번 초 값은 직전 1초로 보이지 않는다")
    void currentSecondIsNotReported() throws Exception {
        // given: 다음 초까지 넣어 두면 요청 중 초가 바뀌어도 '이번 초'만 남는다
        long nowSec = Instant.now().getEpochSecond();
        for (long sec : List.of(nowSec + 1, nowSec + 2)) {
            redis.opsForValue().set(RedisKey.CLICKS_UNIV_SECOND.of(universityId, sec), "9");
        }

        // when
        ResultActions result = activity(universityId);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clicks").value(0));
    }

    @Test
    @DisplayName("토큰 없이도 조회할 수 있다")
    void publicWithoutToken() throws Exception {
        // when
        ResultActions result = activity(universityId);

        // then: 인증 헤더 없이 200
        result.andExpect(status().isOk());
    }

    @Test
    @DisplayName("없는 학교는 404 UNIV_NOT_FOUND")
    void unknownUniversityIsNotFound() throws Exception {
        // given
        long unknownId = universityId + 1_000_000;

        // when
        ResultActions result = activity(unknownId);

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIV_NOT_FOUND"));
    }

    @Test
    @DisplayName("universityId가 숫자가 아니면 400 COMMON_INVALID_INPUT")
    void nonNumericIdIsInvalid() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/api/v1/universities/abc/activity"));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
    }

    private ResultActions activity(long id) throws Exception {
        return mockMvc.perform(get("/api/v1/universities/{universityId}/activity", id));
    }
}
