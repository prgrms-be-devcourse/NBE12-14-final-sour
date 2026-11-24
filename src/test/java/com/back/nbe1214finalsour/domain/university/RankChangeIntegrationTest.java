package com.back.nbe1214finalsour.domain.university;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.university.service.RankSnapshotService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import com.back.nbe1214finalsour.global.time.ClockConfig;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SCRUM-184: 00:00 KST 복사본 대비 순위 변동을 실제 Postgres·Redis로 확인한다 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class RankChangeIntegrationTest {

    // 다른 테스트가 남긴 멤버(최대 수천 점)보다 항상 위에 오도록 큰 점수를 쓴다
    private static final double BASE = 1_000_000_000d;

    @Autowired private MockMvc mockMvc;
    @Autowired private RankSnapshotService rankSnapshotService;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EmailHasher emailHasher;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redis;

    private final List<String> cleanupKeys = new ArrayList<>();
    private final List<String> univMembers = new ArrayList<>();

    @BeforeEach
    void clearMarker() {
        redis.delete(List.of(RedisKey.RANK_SNAPSHOT_DATE.of(), RedisKey.LOCK_RANK_SNAPSHOT.of()));
    }

    @AfterEach
    void cleanup() {
        if (!univMembers.isEmpty()) {
            redis.opsForZSet().remove(RedisKey.RANK_UNIV.of(), univMembers.toArray());
        }
        cleanupKeys.add(RedisKey.RANK_UNIV_SNAPSHOT.of());
        cleanupKeys.add(RedisKey.RANK_SNAPSHOT_DATE.of());
        cleanupKeys.add(RedisKey.LOCK_RANK_SNAPSHOT.of());
        redis.delete(cleanupKeys);
    }

    @Test
    @DisplayName("학교 순위: 상승·하락은 이전 순위 − 지금 순위, 복사본 뒤에 생긴 학교는 null")
    void universityRankChange() throws Exception {
        University a = university("변동테스트A대학교", BASE + 100);
        University b = university("변동테스트B대학교", BASE + 200);
        University c = university("변동테스트C대학교", BASE + 300);

        // 어제: C 1위, B 2위, A 3위
        assertThat(rankSnapshotService.snapshotIfMissing()).isTrue();
        // 하루에 한 번만 만든다 (다른 서버·재시작이 덮어쓰지 않는다)
        assertThat(rankSnapshotService.snapshotIfMissing()).isFalse();

        // 오늘: A 1위(▲2), C 2위(▼1), B 3위(▼1), 새 학교 D 4위(null)
        redis.opsForZSet().add(RedisKey.RANK_UNIV.of(), a.getId().toString(), BASE + 400);
        University d = university("변동테스트D대학교", BASE + 50);

        String token = token(b.getId(), null, 999L);
        mockMvc.perform(get("/api/v1/rankings/universities").param("size", "4")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].universityId").value(a.getId()))
                .andExpect(jsonPath("$.data.items[0].rankChange").value(2))
                .andExpect(jsonPath("$.data.items[1].universityId").value(c.getId()))
                .andExpect(jsonPath("$.data.items[1].rankChange").value(-1))
                .andExpect(jsonPath("$.data.items[2].universityId").value(b.getId()))
                .andExpect(jsonPath("$.data.items[2].rankChange").value(-1))
                .andExpect(jsonPath("$.data.items[3].universityId").value(d.getId()))
                .andExpect(jsonPath("$.data.items[3].rankChange").isEmpty())
                .andExpect(jsonPath("$.data.myUniversity.rank").value(3))
                .andExpect(jsonPath("$.data.myUniversity.rankChange").value(-1));
    }

    @Test
    @DisplayName("학교 상세(방문 화면)도 순위 화면과 같은 변동을 내려준다 (SCRUM-189)")
    void universityDetailRankChange() throws Exception {
        University a = university("변동상세A대학교", BASE + 100);
        University b = university("변동상세B대학교", BASE + 200);
        rankSnapshotService.snapshotIfMissing();

        // 오늘: A 1위(▲1), B 2위(▼1), 복사본 뒤에 생긴 C 3위(null)
        redis.opsForZSet().add(RedisKey.RANK_UNIV.of(), a.getId().toString(), BASE + 300);
        University c = university("변동상세C대학교", BASE + 50);

        mockMvc.perform(get("/api/v1/universities/{id}", a.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rank").value(1))
                .andExpect(jsonPath("$.data.rankChange").value(1));
        mockMvc.perform(get("/api/v1/universities/{id}", b.getId()))
                .andExpect(jsonPath("$.data.rank").value(2))
                .andExpect(jsonPath("$.data.rankChange").value(-1));
        mockMvc.perform(get("/api/v1/universities/{id}", c.getId()))
                .andExpect(jsonPath("$.data.rank").value(3))
                .andExpect(jsonPath("$.data.rankChange").isEmpty());

        // 자정 실행을 놓친 날은 순위 화면처럼 숨긴다
        redis.opsForValue().set(RedisKey.RANK_SNAPSHOT_DATE.of(),
                LocalDate.now(ClockConfig.KST).minusDays(1).toString());
        mockMvc.perform(get("/api/v1/universities/{id}", a.getId()))
                .andExpect(jsonPath("$.data.rank").value(1))
                .andExpect(jsonPath("$.data.rankChange").isEmpty());
    }

    @Test
    @DisplayName("기록된 날짜가 오늘이 아니면(자정 실행을 놓친 날) 변동을 숨긴다")
    void staleSnapshotHidesChange() throws Exception {
        University a = university("변동테스트E대학교", BASE + 100);
        university("변동테스트F대학교", BASE + 200);
        rankSnapshotService.snapshotIfMissing();
        redis.opsForZSet().add(RedisKey.RANK_UNIV.of(), a.getId().toString(), BASE + 300);

        redis.opsForValue().set(RedisKey.RANK_SNAPSHOT_DATE.of(),
                LocalDate.now(ClockConfig.KST).minusDays(1).toString());

        mockMvc.perform(get("/api/v1/rankings/universities").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].universityId").value(a.getId()))
                .andExpect(jsonPath("$.data.items[0].rankChange").isEmpty())
                .andExpect(jsonPath("$.data.items[1].rankChange").isEmpty());
    }

    @Test
    @DisplayName("다른 서버가 만드는 중(락 보유)이면 건너뛰고 날짜를 쓰지 않는다")
    void lockedSnapshotIsSkipped() {
        redis.opsForValue().set(RedisKey.LOCK_RANK_SNAPSHOT.of(), "other-server");

        assertThat(rankSnapshotService.snapshotIfMissing()).isFalse();
        assertThat(rankSnapshotService.isTodaySnapshot()).isFalse();
    }

    @Test
    @DisplayName("학과 순위와 내 학과에도 변동을 내려준다")
    void departmentRankChange() throws Exception {
        University univ = universityRepository.saveAndFlush(new University("변동학과테스트대학교"));
        Department first = departmentRepository.saveAndFlush(new Department(univ, "변동컴퓨터공학과"));
        Department second = departmentRepository.saveAndFlush(new Department(univ, "변동디자인학과"));
        String key = RedisKey.RANK_DEPT.of(univ.getId());
        cleanupKeys.add(key);
        cleanupKeys.add(RedisKey.RANK_DEPT_SNAPSHOT.of(univ.getId()));
        redis.opsForZSet().add(key, first.getId().toString(), 50);
        redis.opsForZSet().add(key, second.getId().toString(), 100);

        rankSnapshotService.snapshotIfMissing();
        redis.opsForZSet().add(key, first.getId().toString(), 150);

        String token = token(univ.getId(), first.getId(), 999L);
        mockMvc.perform(get("/api/v1/rankings/universities/{id}/departments", univ.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].departmentId").value(first.getId()))
                .andExpect(jsonPath("$.data.items[0].rankChange").value(1))
                .andExpect(jsonPath("$.data.items[1].rankChange").value(-1))
                .andExpect(jsonPath("$.data.myDepartment.rankChange").value(1));
    }

    @Test
    @DisplayName("개인 순위: 비공개가 된 회원은 어제 순위에서도 빼고 센다")
    void userRankChangeSkipsExcludedMembers() throws Exception {
        University univ = universityRepository.saveAndFlush(new University("변동개인테스트대학교"));
        Department dept = departmentRepository.saveAndFlush(new Department(univ, "변동개인학과"));
        User leaver = user(univ, dept, "변동차단예정", "rank-change-leaver@test.ac.kr");
        User me = user(univ, dept, "변동나", "rank-change-me@test.ac.kr");
        User rival = user(univ, dept, "변동라이벌", "rank-change-rival@test.ac.kr");
        String key = RedisKey.RANK_USER.of(univ.getId());
        cleanupKeys.add(key);
        cleanupKeys.add(RedisKey.RANK_USER_SNAPSHOT.of(univ.getId()));
        redis.opsForZSet().add(key, leaver.getId().toString(), 1000);
        redis.opsForZSet().add(key, me.getId().toString(), 500);
        redis.opsForZSet().add(key, rival.getId().toString(), 300);

        // 어제 공개 순위: 나 1위, 라이벌 2위 (오늘 차단된 회원을 빼면)
        rankSnapshotService.snapshotIfMissing();
        jdbcTemplate.update("update users set status = 'BANNED' where id = ?", leaver.getId());
        redis.opsForZSet().add(key, rival.getId().toString(), 600);

        String token = token(univ.getId(), dept.getId(), me.getId());
        mockMvc.perform(get("/api/v1/rankings/universities/{id}/users", univ.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].userId").value(rival.getId()))
                .andExpect(jsonPath("$.data.items[0].rankChange").value(1))
                .andExpect(jsonPath("$.data.items[1].userId").value(me.getId()))
                .andExpect(jsonPath("$.data.items[1].rankChange").value(-1))
                .andExpect(jsonPath("$.data.me.rank").value(2))
                .andExpect(jsonPath("$.data.me.rankChange").value(-1));
    }

    private University university(String name, double score) {
        University university = universityRepository.saveAndFlush(new University(name));
        univMembers.add(university.getId().toString());
        redis.opsForZSet().add(RedisKey.RANK_UNIV.of(), university.getId().toString(), score);
        return university;
    }

    private User user(University university, Department department, String nickname, String email) {
        return userRepository.saveAndFlush(User.create(
                university.getId(), department.getId(), nickname, email,
                emailHasher.hash(email), "{noop}unused", OffsetDateTime.now()));
    }

    private String token(Long universityId, Long departmentId, Long userId) {
        return jwtProvider.createAccessToken(new AuthUser(userId, universityId, departmentId, Role.USER), null);
    }
}
