package com.back.nbe1214finalsour.domain.university;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class DepartmentRankingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("실제 DB와 Redis로 학과 랭킹, 공동 순위, 페이지 조회를 검증한다")
    void getDepartmentRankings() throws Exception {

        // 1. PostgreSQL에 학교 2개 생성
        University university = universityRepository.saveAndFlush(
                new University("학과랭킹테스트대학교")
        );

        University otherUniversity = universityRepository.saveAndFlush(
                new University("다른학과랭킹테스트대학교")
        );

        // 2. 첫 번째 학교에 학과 3개 생성
        Department first = departmentRepository.saveAndFlush(
                new Department(university, "컴퓨터공학과")
        );

        Department second = departmentRepository.saveAndFlush(
                new Department(university, "디자인학과")
        );

        Department third = departmentRepository.saveAndFlush(
                new Department(university, "경영학과")
        );

        // 다른 학교의 학과
        Department other = departmentRepository.saveAndFlush(
                new Department(otherUniversity, "전자공학과")
        );

        Long universityId = university.getId();

        String key = RedisKey.RANK_DEPT.of(universityId);
        String otherKey = RedisKey.RANK_DEPT.of(otherUniversity.getId());

        try {
            // 3. Redis에 학과별 점수 저장
            redisTemplate.opsForZSet().add(
                    key, first.getId(), 5000.0
            );

            redisTemplate.opsForZSet().add(
                    key, second.getId(), 3000.0
            );

            redisTemplate.opsForZSet().add(
                    key, third.getId(), 3000.0
            );

            // 다른 학교 학과는 더 높은 점수를 부여
            redisTemplate.opsForZSet().add(
                    otherKey, other.getId(), 9000.0
            );

            // 4. 첫 페이지: 1위, 공동 2위
            mockMvc.perform(
                            get("/api/v1/rankings/universities/{id}/departments",
                                    universityId)
                                    .param("page", "0")
                                    .param("size", "2")
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.items.length()").value(2))
                    .andExpect(jsonPath("$.data.items[0].rank").value(1))
                    .andExpect(jsonPath("$.data.items[0].departmentId")
                            .value(first.getId()))
                    .andExpect(jsonPath("$.data.items[0].name")
                            .value("컴퓨터공학과"))
                    .andExpect(jsonPath("$.data.items[0].totalScore")
                            .value(5000))
                    .andExpect(jsonPath("$.data.items[1].rank").value(2))
                    .andExpect(jsonPath("$.data.totalCount").value(3))
                    .andExpect(jsonPath("$.data.hasNext").value(true))
                    .andExpect(jsonPath("$.data.myDepartment").isEmpty());

            // 5. 두 번째 페이지: 공동 2위 유지
            mockMvc.perform(
                            get("/api/v1/rankings/universities/{id}/departments",
                                    universityId)
                                    .param("page", "1")
                                    .param("size", "2")
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].rank").value(2))
                    .andExpect(jsonPath("$.data.items[0].totalScore")
                            .value(3000))
                    .andExpect(jsonPath("$.data.totalCount").value(3))
                    .andExpect(jsonPath("$.data.hasNext").value(false));

            // 6. 로그인 사용자는 자기 학과 순위도 조회
            String token = jwtProvider.createAccessToken(
                    new AuthUser(
                            999L,
                            universityId,
                            second.getId(),
                            Role.USER
                    ),
                    null
            );

            mockMvc.perform(
                            get("/api/v1/rankings/universities/{id}/departments",
                                    universityId)
                                    .header(
                                            HttpHeaders.AUTHORIZATION,
                                            "Bearer " + token
                                    )
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.myDepartment.rank")
                            .value(2))
                    .andExpect(jsonPath("$.data.myDepartment.departmentId")
                            .value(second.getId()))
                    .andExpect(jsonPath("$.data.myDepartment.totalScore")
                            .value(3000));

        } finally {
            // 7. 테스트에서 추가한 Redis 데이터만 정리
            redisTemplate.opsForZSet().remove(
                    key,
                    first.getId(),
                    second.getId(),
                    third.getId()
            );

            redisTemplate.opsForZSet().remove(
                    otherKey,
                    other.getId()
            );
        }
    }

    @Test
    @DisplayName("존재하지 않는 학교의 학과 랭킹은 404를 반환한다")
    void getDepartmentRankings_notFound() throws Exception {
        mockMvc.perform(
                        get("/api/v1/rankings/universities/{id}/departments",
                                -1L)
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("UNIV_NOT_FOUND"));
    }
}