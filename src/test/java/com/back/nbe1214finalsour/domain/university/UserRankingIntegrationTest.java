package com.back.nbe1214finalsour.domain.university;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class UserRankingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RedisHandler redisHandler;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private EmailHasher emailHasher;

    @Autowired
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("개인 랭킹의 점수순 조회, 공동 순위, 페이지, 로그인 정보를 검증한다")
    void getUserRankings() throws Exception {

        // 1. PostgreSQL에 학교 생성
        University university = universityRepository.saveAndFlush(
                new University("개인랭킹통합테스트대학교")
        );

        University otherUniversity = universityRepository.saveAndFlush(
                new University("개인랭킹타학교")
        );

        // 2. 학과 생성
        Department computer = departmentRepository.saveAndFlush(
                new Department(university, "컴퓨터공학과")
        );

        Department design = departmentRepository.saveAndFlush(
                new Department(university, "디자인학과")
        );

        Department otherDepartment = departmentRepository.saveAndFlush(
                new Department(otherUniversity, "전자공학과")
        );

        // 3. 실제 사용자 생성
        User first = saveUser(
                university, computer,
                "랭킹클릭왕", "rank-first@test.ac.kr"
        );

        User second = saveUser(
                university, design,
                "랭킹디자인왕", "rank-second@test.ac.kr"
        );

        User third = saveUser(
                university, computer,
                "랭킹클릭장인", "rank-third@test.ac.kr"
        );

        User other = saveUser(
                otherUniversity, otherDepartment,
                "다른학교학생", "rank-other@test.ac.kr"
        );

        first.changeProfileImageUrl(
                "https://cdn.test/users/ranking.png"
        );
        userRepository.flush();

        Long universityId = university.getId();

        String key = RedisKey.RANK_USER.of(universityId);
        String otherKey = RedisKey.RANK_USER.of(otherUniversity.getId());

        try {
            // 4. Redis에 개인 기여도 저장
            redisHandler.addRank(key, first.getId(), 5000.0);
            redisHandler.addRank(key, second.getId(), 3000.0);
            redisHandler.addRank(key, third.getId(), 3000.0);

            // 다른 학교 학생은 더 높은 점수 부여
            redisHandler.addRank(otherKey, other.getId(), 9000.0);

            // 5. 첫 페이지 검증
            mockMvc.perform(
                            get("/api/v1/rankings/universities/{id}/users",
                                    universityId)
                                    .param("page", "0")
                                    .param("size", "2")
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.items.length()").value(2))
                    .andExpect(jsonPath("$.data.items[0].rank").value(1))
                    .andExpect(jsonPath("$.data.items[0].userId")
                            .value(first.getId()))
                    .andExpect(jsonPath("$.data.items[0].nickname")
                            .value("랭킹클릭왕"))
                    .andExpect(jsonPath("$.data.items[0].profileImageUrl")
                            .value("https://cdn.test/users/ranking.png"))
                    .andExpect(jsonPath("$.data.items[0].department.id")
                            .value(computer.getId()))
                    .andExpect(jsonPath("$.data.items[0].department.name")
                            .value("컴퓨터공학과"))
                    .andExpect(jsonPath("$.data.items[0].contribScore")
                            .value(5000))
                    .andExpect(jsonPath("$.data.items[1].rank")
                            .value(2))
                    .andExpect(jsonPath("$.data.totalCount").value(3))
                    .andExpect(jsonPath("$.data.hasNext").value(true))
                    .andExpect(jsonPath("$.data.me").isEmpty());

            // 6. 두 번째 페이지: 공동 2위 유지
            mockMvc.perform(
                            get("/api/v1/rankings/universities/{id}/users",
                                    universityId)
                                    .param("page", "1")
                                    .param("size", "2")
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].rank").value(2))
                    .andExpect(jsonPath("$.data.items[0].contribScore")
                            .value(3000))
                    .andExpect(jsonPath("$.data.hasNext").value(false));

            // 7. 로그인 사용자: 내 순위 조회
            String token = jwtProvider.createAccessToken(
                    new AuthUser(
                            third.getId(),
                            universityId,
                            computer.getId(),
                            Role.USER
                    ),
                    null
            );

            // 내 사용자가 첫 페이지 밖에 있어도
            // me 정보는 반환되어야 한다.
            mockMvc.perform(
                            get("/api/v1/rankings/universities/{id}/users",
                                    universityId)
                                    .param("size", "1")
                                    .header(
                                            HttpHeaders.AUTHORIZATION,
                                            "Bearer " + token
                                    )
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.me.rank").value(2))
                    .andExpect(jsonPath("$.data.me.contribScore")
                            .value(3000));

            // 8. 다른 학교 학생이 섞이지 않는지 검증
            mockMvc.perform(
                            get("/api/v1/rankings/universities/{id}/users",
                                    otherUniversity.getId())
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalCount").value(1))
                    .andExpect(jsonPath("$.data.items[0].userId")
                            .value(other.getId()))
                    .andExpect(jsonPath("$.data.items[0].rank").value(1));

        } finally {
            // 9. 테스트에서 넣은 Redis 데이터만 제거
            redisHandler.removeRank(key, first.getId());
            redisHandler.removeRank(key, second.getId());
            redisHandler.removeRank(key, third.getId());
            redisHandler.removeRank(otherKey, other.getId());
        }
    }

    @Test
    @DisplayName("탈퇴한 회원은 개인 랭킹 목록에 공개되지 않는다")
    void getUserRankings_withdrawnExcluded() throws Exception {

        University university = universityRepository.saveAndFlush(
                new University("탈퇴회원랭킹테스트대학교")
        );

        Department department = departmentRepository.saveAndFlush(
                new Department(university, "컴퓨터공학과")
        );

        User active = saveUser(
                university, department,
                "정상랭킹회원", "rank-active@test.ac.kr"
        );

        User withdrawn = saveUser(
                university, department,
                "탈퇴랭킹회원", "rank-withdrawn@test.ac.kr"
        );

        withdrawn.withdraw(OffsetDateTime.now());
        userRepository.flush();

        String key = RedisKey.RANK_USER.of(university.getId());

        try {
            redisHandler.addRank(key, withdrawn.getId(), 9000.0);
            redisHandler.addRank(key, active.getId(), 5000.0);

            mockMvc.perform(
                            get("/api/v1/rankings/universities/{id}/users",
                                    university.getId())
                                    .param("page", "0")
                                    .param("size", "1")
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].userId")
                            .value(active.getId()))
                    .andExpect(jsonPath("$.data.items[0].nickname")
                            .value("정상랭킹회원"))
                    .andExpect(jsonPath("$.data.items[0].rank").value(1))
                    .andExpect(jsonPath("$.data.totalCount").value(1))
                    .andExpect(jsonPath("$.data.hasNext").value(false));

            // 로그인한 정상 회원도 공개 순위 기준 1위를 반환한다.
            String token = jwtProvider.createAccessToken(
                    new AuthUser(active.getId(), university.getId(), department.getId(), Role.USER),
                    null
            );
            mockMvc.perform(get("/api/v1/rankings/universities/{id}/users", university.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.me.rank").value(1))
                    .andExpect(jsonPath("$.data.me.contribScore").value(5000));

            // 비공개 처리와 무관하게 Redis 기여도 잔액은 보존되어야 한다.
            org.assertj.core.api.Assertions.assertThat(
                    redisHandler.getRankScore(key, withdrawn.getId())
            ).hasValue(9000.0);

        } finally {
            redisHandler.removeRank(key, active.getId());
            redisHandler.removeRank(key, withdrawn.getId());
        }
    }

    @Test
    @DisplayName("정지 회원이 1위여도 정상 회원을 공개 1위로 표시한다")
    void getUserRankings_suspendedExcluded() throws Exception {
        University university = universityRepository.saveAndFlush(
                new University("정지회원랭킹테스트대학교"));
        Department department = departmentRepository.saveAndFlush(
                new Department(university, "컴퓨터공학과"));
        User active = saveUser(university, department, "정상활성랭킹회원", "rank-suspend-active@test.ac.kr");
        User suspended = saveUser(university, department, "정지랭킹회원", "rank-suspended@test.ac.kr");

        // User 엔티티는 공개적인 정지 메서드가 없으므로 테스트에서만 상태를 변경한다.
        jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE id = :id",
                new MapSqlParameterSource("id", suspended.getId()));

        String key = RedisKey.RANK_USER.of(university.getId());
        try {
            redisHandler.addRank(key, suspended.getId(), 9000.0);
            redisHandler.addRank(key, active.getId(), 5000.0);
            mockMvc.perform(get("/api/v1/rankings/universities/{id}/users", university.getId())
                            .param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].userId").value(active.getId()))
                    .andExpect(jsonPath("$.data.items[0].rank").value(1))
                    .andExpect(jsonPath("$.data.totalCount").value(1))
                    .andExpect(jsonPath("$.data.hasNext").value(false));
        } finally {
            redisHandler.removeRank(key, active.getId());
            redisHandler.removeRank(key, suspended.getId());
        }
    }

    @Test
    @DisplayName("존재하지 않는 학교는 404를 반환한다")
    void getUserRankings_notFound() throws Exception {
        mockMvc.perform(
                        get("/api/v1/rankings/universities/{id}/users", -1L)
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("UNIV_NOT_FOUND"));
    }

    // 테스트용 사용자 생성 공통 메서드
    private User saveUser(
            University university,
            Department department,
            String nickname,
            String email
    ) {
        return userRepository.saveAndFlush(
                User.create(
                        university.getId(),
                        department.getId(),
                        nickname,
                        email,
                        emailHasher.hash(email),
                        "{noop}unused",
                        OffsetDateTime.now()
                )
        );
    }
}
