package com.back.nbe1214finalsour.domain.university;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class UniversityDetailIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void getUniversityDetail_withRealDbAndRedis() throws Exception {

        // 1. PostgreSQL에 테스트 학교와 학과 저장
        University university = universityRepository.saveAndFlush(
                new University("통합테스트대학교")
        );

        Department department = departmentRepository.saveAndFlush(
                new Department(university, "컴퓨터공학과")
        );

        Long universityId = university.getId();
        Long departmentId = department.getId();

        // 2. 실제 Redis에 학교 점수와 학과 점수 저장
        String universityKey = RedisKey.RANK_UNIV.of();
        String departmentKey = RedisKey.RANK_DEPT.of(universityId);
        String memberCountKey =
                RedisKey.CACHE_UNIV_MEMBER_COUNT.of(universityId);

        try {
            redisTemplate.opsForZSet().add(
                    universityKey, universityId, 15000.0
            );

            redisTemplate.opsForZSet().add(
                    departmentKey, departmentId, 5000.0
            );

            // 회원 수는 0명으로 캐싱
            redisTemplate.opsForValue().set(
                    memberCountKey, 0L, Duration.ofMinutes(1)
            );

            // 3. 실제 HTTP 요청
            mockMvc.perform(
                            get("/api/v1/universities/{id}", universityId)
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id")
                            .value(universityId))
                    .andExpect(jsonPath("$.data.name")
                            .value("통합테스트대학교"))
                    .andExpect(jsonPath("$.data.totalScore")
                            .value(15000))
                    .andExpect(jsonPath("$.data.rank")
                            .value(1))
                    .andExpect(jsonPath("$.data.memberCount")
                            .value(0))
                    .andExpect(jsonPath("$.data.isMyUniversity")
                            .value(false))
                    .andExpect(jsonPath("$.data.topDepartments[0].id")
                            .value(departmentId))
                    .andExpect(jsonPath("$.data.topDepartments[0].name")
                            .value("컴퓨터공학과"))
                    .andExpect(jsonPath("$.data.topDepartments[0].totalScore")
                            .value(5000))
                    .andExpect(jsonPath("$.data.topContributors")
                            .isEmpty());

        } finally {
            // 4. 테스트에서 사용한 Redis 데이터 정리
            redisTemplate.opsForZSet()
                    .remove(universityKey, universityId);

            redisTemplate.opsForZSet()
                    .remove(departmentKey, departmentId);

            redisTemplate.delete(memberCountKey);
        }
    }

    @Test
    void getUniversityDetail_memberCountCacheMissQueriesDbAndWritesRedis() throws Exception {
        University university = universityRepository.saveAndFlush(
                new University("회원수캐시미스테스트대학교")
        );
        Long universityId = university.getId();
        String memberCountKey = RedisKey.CACHE_UNIV_MEMBER_COUNT.of(universityId);
        String universityKey = RedisKey.RANK_UNIV.of();

        // 테스트마다 독립된 학교 ID를 사용하고, Redis 데이터는 직접 청소한다.
        redisTemplate.delete(memberCountKey);
        try {
            mockMvc.perform(get("/api/v1/universities/{id}", universityId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.memberCount").value(0))
                    .andExpect(jsonPath("$.data.isMyUniversity").value(false));

            // DB에 회원이 없어서 조회한 0도 캐싱돼야 한다.
            Object cached = redisTemplate.opsForValue().get(memberCountKey);
            org.assertj.core.api.Assertions.assertThat(cached).isNotNull();
            org.assertj.core.api.Assertions.assertThat(Long.parseLong(cached.toString())).isZero();
            Long ttlSeconds = redisTemplate.getExpire(memberCountKey);
            org.assertj.core.api.Assertions.assertThat(ttlSeconds).isBetween(1L, 60L);
        } finally {
            redisTemplate.delete(memberCountKey);
            redisTemplate.opsForZSet().remove(universityKey, universityId);
        }
    }
}
