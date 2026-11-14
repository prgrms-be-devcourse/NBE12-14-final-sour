package com.back.nbe1214finalsour.domain.university;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class UniversityRankingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("실제 DB와 Redis로 학교 랭킹, 공동 순위, 페이지 조회를 검증한다")
    void getUniversityRankings() throws Exception {

        // 1. PostgreSQL에 학교 3개 저장
        University first = universityRepository.saveAndFlush(
                new University("랭킹테스트A대학교"));
        University second = universityRepository.saveAndFlush(
                new University("랭킹테스트B대학교"));
        University third = universityRepository.saveAndFlush(
                new University("랭킹테스트C대학교"));

        String key = RedisKey.RANK_UNIV.of();

        try {
            // 2. Redis에 학교별 점수 저장
            redis.opsForZSet().add(
                    key, first.getId().toString(), 5000.0);
            redis.opsForZSet().add(
                    key, second.getId().toString(), 3000.0);
            redis.opsForZSet().add(
                    key, third.getId().toString(), 3000.0);

            // 3. 첫 페이지: 1위, 공동 2위
            mockMvc.perform(get("/api/v1/rankings/universities")
                            .param("page", "0")
                            .param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.items[0].rank").value(1))
                    .andExpect(jsonPath("$.data.items[0].name")
                            .value("랭킹테스트A대학교"))
                    .andExpect(jsonPath("$.data.items[0].totalScore")
                            .value(5000))
                    .andExpect(jsonPath("$.data.items[1].rank").value(2))
                    .andExpect(jsonPath("$.data.totalCount").value(3))
                    .andExpect(jsonPath("$.data.hasNext").value(true))
                    .andExpect(jsonPath("$.data.myUniversity").isEmpty());

            // 4. 두 번째 페이지: 공동 2위 유지
            mockMvc.perform(get("/api/v1/rankings/universities")
                            .param("page", "1")
                            .param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].rank").value(2))
                    .andExpect(jsonPath("$.data.hasNext").value(false));

            // 5. 로그인한 경우 내 학교 순위 반환
            String token = jwtProvider.createAccessToken(
                    new AuthUser(
                            999L, second.getId(), null, Role.USER
                    )
            );

            mockMvc.perform(get("/api/v1/rankings/universities")
                            .header(HttpHeaders.AUTHORIZATION,
                                    "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.myUniversity.rank")
                            .value(2))
                    .andExpect(jsonPath("$.data.myUniversity.universityId")
                            .value(second.getId()))
                    .andExpect(jsonPath("$.data.myUniversity.totalScore")
                            .value(3000));

        } finally {
            // 6. 테스트에서 넣은 Redis 데이터만 제거
            redis.opsForZSet().remove(
                    key,
                    first.getId().toString(),
                    second.getId().toString(),
                    third.getId().toString()
            );
        }
    }
}
