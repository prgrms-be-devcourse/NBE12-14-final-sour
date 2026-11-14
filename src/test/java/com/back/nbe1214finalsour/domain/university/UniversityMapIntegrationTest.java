package com.back.nbe1214finalsour.domain.university;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.dto.UniversityMapResponse;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class UniversityMapIntegrationTest {

    private static final String RANK_KEY =
            RedisKey.RANK_UNIV.of();

    private static final String CACHE_KEY =
            RedisKey.CACHE_MAP_UNIVERSITIES.of();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisHandler redisHandler;

    private final List<Long> testUniversityIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {

        // 테스트 학교의 Redis 순위 데이터만 삭제
        if (!testUniversityIds.isEmpty()) {
            String[] ids = testUniversityIds.stream()
                    .map(String::valueOf)
                    .toArray(String[]::new);

            redis.opsForZSet().remove(RANK_KEY, (Object[]) ids);
        }

        // 지도 캐시 정리
        redis.delete(CACHE_KEY);
    }

    @Test
    @DisplayName("실제 DB와 Redis를 이용해 지도 조회 및 캐싱을 검증한다")
    void getUniversityMap() throws Exception {

        // 1. 기존 지도 캐시 제거
        redis.delete(CACHE_KEY);

        // 2. PostgreSQL에 테스트 학교 3개 등록
        University first = universityRepository.saveAndFlush(
                new University("검증A대학교")
        );

        University second = universityRepository.saveAndFlush(
                new University("검증B대학교")
        );

        University third = universityRepository.saveAndFlush(
                new University("검증C대학교")
        );

        testUniversityIds.add(first.getId());
        testUniversityIds.add(second.getId());
        testUniversityIds.add(third.getId());

        // 3. 첫 번째 학교에 좌표 설정
        jdbcTemplate.update(
                """
                UPDATE university
                SET library_lat = ?, library_lng = ?
                WHERE id = ?
                """,
                new BigDecimal("37.123456"),
                new BigDecimal("127.123456"),
                first.getId()
        );

        // JPA에 남아 있는 기존 엔티티 정보 초기화
        entityManager.clear();

        // 4. 실제 Redis에 학교 점수 등록
        redis.opsForZSet().add(
                RANK_KEY, first.getId().toString(),
                3_000_000_000_000.0
        );

        redis.opsForZSet().add(
                RANK_KEY, second.getId().toString(),
                2_000_000_000_000.0
        );

        redis.opsForZSet().add(
                RANK_KEY, third.getId().toString(),
                1_000_000_000_000.0
        );

        // 5. 실제 지도 API 요청
        mockMvc.perform(
                        get("/api/v1/map/universities")
                                .param("limit", "2")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name")
                        .value("검증A대학교"))
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].totalScore")
                        .value(3_000_000_000_000L))
                .andExpect(jsonPath("$.data[0].latitude")
                        .value(37.123456))
                .andExpect(jsonPath("$.data[0].longitude")
                        .value(127.123456))
                .andExpect(jsonPath("$.data[1].name")
                        .value("검증B대학교"))
                .andExpect(jsonPath("$.data[1].rank").value(2))
                .andExpect(jsonPath("$.data[1].latitude").isEmpty())
                .andExpect(jsonPath("$.data[1].longitude").isEmpty());

        // 6. Redis에 지도 캐시가 생성되었는지 확인
        UniversityMapResponse[] cached =
                redisHandler.get(
                        CACHE_KEY,
                        UniversityMapResponse[].class
                ).orElseThrow();

        // limit=2로 요청했더라도 상위 300개까지 캐싱
        assertThat(cached).hasSizeGreaterThanOrEqualTo(3);

        assertThat(cached[0].name())
                .isEqualTo("검증A대학교");

        assertThat(cached[2].name())
                .isEqualTo("검증C대학교");

        // 7. 캐시에 TTL이 설정되었는지 확인
        Long ttl = redis.getExpire(
                CACHE_KEY,
                TimeUnit.MILLISECONDS
        );

        assertThat(ttl).isNotNull();
        assertThat(ttl).isPositive();
        assertThat(ttl).isLessThanOrEqualTo(5000L);

        // 8. 다른 limit으로 다시 요청 (캐시 재사용)
        mockMvc.perform(
                        get("/api/v1/map/universities")
                                .param("limit", "1")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name")
                        .value("검증A대학교"));
    }
}