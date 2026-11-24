package com.back.nbe1214finalsour.domain.university;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceMatchRedisSettlementService;
import com.back.nbe1214finalsour.domain.click.service.ScoreFlushService;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 운영용 숨김 학교(V14 와플대학교와 같은 hidden=true 학교)가 순위·지도·검색에 나오지 않는지 확인한다 (SCRUM-236).
 *
 * <p>숨김 학교 점수는 {@link RedisKey#RANK_UNIV_HIDDEN}에 쌓이고 {@link RedisKey#RANK_UNIV}에는 들어가지 않는다.
 * 순위·지도·어제 순위는 RANK_UNIV만 읽으므로, 숨김 학교는 목록에서 빠지고 다른 학교의 순위 번호도 밀지 않는다.
 * 공개 목록 밖에서는 일반 학교와 같다: 공격·지원·연합 대결·학교 상세를 쓸 수 있다 (SCRUM-238).
 *
 * <p>테스트 학교는 서버가 뜬 뒤 만들어지므로 학교 캐시를 다시 불러온다. 운영에서는 숨김 학교가 마이그레이션으로만 들어가
 * 서버가 뜰 때 캐시에 있다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class HiddenUniversityIntegrationTest {

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
    @Autowired
    private ClickerService clickerService;
    @Autowired
    private UniversityCache universityCache;
    @Autowired
    private AllianceMatchRedisSettlementService settlementService;
    @Autowired
    private ScoreFlushService scoreFlushService;

    private long hiddenUnivId;
    private long publicUnivId;
    private String hiddenToken;
    private String publicToken;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> departmentIds = new ArrayList<>();
    /** 정산 테스트가 쓴 캐시 밖 학교 ID와 대결 ID */
    private final List<Long> settledIds = new ArrayList<>();
    private final List<Long> settledMatches = new ArrayList<>();
    private Long allianceId;

    @BeforeEach
    void setUp() {
        clickerService.loadLevelTable();
        hiddenUnivId = insertUniversity("숨김테스트대학교", true);
        publicUnivId = insertUniversity("공개테스트대학교", false);
        universityCache.load();
        hiddenToken = tokenOf(hiddenUnivId, "숨김테스터", "hidden@hidden-test.ac.kr");
        publicToken = tokenOf(publicUnivId, "공개테스터", "public@hidden-test.ac.kr");
        // 지도는 5초 캐시라, 다른 테스트가 만든 캐시를 지우고 이 테스트의 점수로 다시 만든다
        redis.delete(RedisKey.CACHE_MAP_UNIVERSITIES.of());
    }

    @AfterEach
    void tearDown() {
        // 공유 키는 이 테스트가 넣은 member만, 전용 키는 통째로 지운다
        for (long univId : List.of(hiddenUnivId, publicUnivId)) {
            redis.opsForZSet().remove(RedisKey.RANK_UNIV.of(), String.valueOf(univId));
            redis.opsForZSet().remove(RedisKey.RANK_UNIV_HIDDEN.of(), String.valueOf(univId));
            redis.opsForSet().remove(RedisKey.DIRTY_UNIV.of(), String.valueOf(univId));
            redis.delete(List.of(RedisKey.RANK_DEPT.of(univId), RedisKey.RANK_USER.of(univId),
                    RedisKey.UNIV_ALLIANCE.of(univId), RedisKey.UNIV_MATCH.of(univId)));
            long nowSec = Instant.now().getEpochSecond();
            redis.delete(LongStream.rangeClosed(nowSec - 8, nowSec + 2)
                    .mapToObj(sec -> RedisKey.CLICKS_UNIV_SECOND.of(univId, sec)).toList());
        }
        departmentIds.forEach(id -> redis.opsForSet().remove(RedisKey.DIRTY_DEPT.of(), String.valueOf(id)));
        for (long userId : userIds) {
            redis.opsForSet().remove(RedisKey.DIRTY_USER.of(), String.valueOf(userId));
            redis.opsForHash().delete(RedisKey.SCORE_USER_ATTACK.of(), String.valueOf(userId));
            redis.delete(List.of(RedisKey.USER_STATUS.of(userId), RedisKey.USER_CLICKER.of(userId),
                    RedisKey.USER_EFFECT.of(userId), RedisKey.RATELIMIT_ACTION.of(userId),
                    RedisKey.RATELIMIT_CLICK.of(userId)));
        }
        redis.delete(RedisKey.CACHE_MAP_UNIVERSITIES.of());
        settledIds.forEach(id -> {
            redis.opsForZSet().remove(RedisKey.RANK_UNIV.of(), String.valueOf(id));
            redis.opsForSet().remove(RedisKey.DIRTY_UNIV.of(), String.valueOf(id));
        });
        redis.delete(settledMatches.stream().map(id -> "match:" + id + ":settled").toList());
        if (allianceId != null) {
            redis.delete(RedisKey.ALLIANCE_SCORE.of(allianceId));
            redis.opsForSet().remove(RedisKey.DIRTY_ALLIANCE.of(), String.valueOf(allianceId));
            redis.opsForSet().remove(RedisKey.DIRTY_ALLIANCE.of() + ":flushing", String.valueOf(allianceId));
            jdbcTemplate.update("DELETE FROM alliance_university WHERE alliance_id = ?", allianceId);
            jdbcTemplate.update("DELETE FROM alliance WHERE id = ?", allianceId);
        }

        jdbcTemplate.update("DELETE FROM users WHERE university_id IN (?, ?)", hiddenUnivId, publicUnivId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id IN (?, ?)", hiddenUnivId, publicUnivId);
        jdbcTemplate.update("DELETE FROM university WHERE id IN (?, ?)", hiddenUnivId, publicUnivId);
        universityCache.load();
    }

    @Test
    @DisplayName("숨김 학교 회원이 클릭하면 점수는 숨김 학교 순위에만 쌓이고, 학교 순위와 지도에는 나오지 않는다")
    void hiddenClickStaysOutOfPublicRanking() throws Exception {
        click(hiddenToken, "hidden-1", 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.university.id").value(hiddenUnivId))
                .andExpect(jsonPath("$.data.university.totalScore").value(5));
        click(publicToken, "public-1", 3).andExpect(status().isOk());

        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), String.valueOf(hiddenUnivId))).isNull();
        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV_HIDDEN.of(), String.valueOf(hiddenUnivId))).isEqualTo(5.0);
        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), String.valueOf(publicUnivId))).isEqualTo(3.0);

        mockMvc.perform(get("/api/v1/rankings/universities").param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hiddenToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].universityId", not(hasItem((int) hiddenUnivId))))
                .andExpect(jsonPath("$.data.myUniversity").doesNotExist());
        mockMvc.perform(get("/api/v1/map/universities").param("limit", "300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].universityId", hasItem((int) publicUnivId)))
                .andExpect(jsonPath("$.data[*].universityId", not(hasItem((int) hiddenUnivId))));
    }

    @Test
    @DisplayName("학교 검색에 숨김 학교(테스트 학교와 V14 와플대학교)는 나오지 않는다")
    void searchExcludesHiddenUniversities() throws Exception {
        search("숨김테스트").andExpect(jsonPath("$.data", empty()));
        search("와플").andExpect(jsonPath("$.data", empty()));
        search("공개테스트").andExpect(jsonPath("$.data[*].id", hasItem((int) publicUnivId)));
    }

    @Test
    @DisplayName("SCRUM-238: 숨김 학교도 공격·지원받지만, 점수는 숨김 학교 순위에만 반영된다")
    void hiddenUniversityCanBeAttackedAndAssisted() throws Exception {
        click(hiddenToken, "hidden-2", 10).andExpect(status().isOk());

        // 기본 클리커(클릭당 1점): 공격 피해와 지원 점수는 클릭 수의 0.3배 (10 → 3, 20 → 6)
        target("/api/v1/attacks", "attack-1", hiddenUnivId, 10).andExpect(status().isOk());
        assertThat(hiddenScore()).isEqualTo(7.0);

        joinAlliance();
        target("/api/v1/assists", "assist-1", hiddenUnivId, 20).andExpect(status().isOk());
        assertThat(hiddenScore()).isEqualTo(13.0);
        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), String.valueOf(hiddenUnivId))).isNull();
    }

    @Test
    @DisplayName("SCRUM-238: 숨김 학교 상세는 순위 없이 숨김 학교 점수를 보여 주고, 점수 배치가 DB 점수도 맞춘다")
    void hiddenUniversityDetailShowsScoreWithoutRank() throws Exception {
        click(hiddenToken, "hidden-3", 6).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/universities/{id}", hiddenUnivId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hiddenToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalScore").value(6))
                .andExpect(jsonPath("$.data.rank").doesNotExist())
                .andExpect(jsonPath("$.data.isMyUniversity").value(true));

        scoreFlushService.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT total_score FROM university WHERE id = ?", Long.class,
                hiddenUnivId)).isEqualTo(6L);
    }

    @Test
    @DisplayName("SCRUM-238: 대결 정산은 숨김 학교 점수를 숨김 학교 순위에서 차감·지급하고 공개 순위에 넣지 않는다")
    void settlementKeepsHiddenUniversityOutOfPublicRanking() {
        // 숨김 학교와 캐시에 없는 학교 ID(공개 학교로 취급)로 대결 두 번: 숨김 학교가 질 때와 이길 때
        long base = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        List<Long> others = List.of(base + 1, base + 2, base + 3);
        settledIds.addAll(others);
        settledMatches.addAll(List.of(base, base + 10));
        redis.opsForZSet().add(RedisKey.RANK_UNIV_HIDDEN.of(), String.valueOf(hiddenUnivId), 100);
        others.forEach(id -> redis.opsForZSet().add(RedisKey.RANK_UNIV.of(), String.valueOf(id), 50));

        // 패배: 숨김 학교 -30, base+1 -20 → 승리 두 학교에 25씩
        assertThat(settlementService.settleOnce(base, hiddenUnivId, base + 1, base + 2, base + 3, 30, 20)).isTrue();
        assertThat(hiddenScore()).isEqualTo(70.0);
        assertThat(publicScore(base + 2)).isEqualTo(75.0);
        assertThat(publicScore(base + 3)).isEqualTo(75.0);

        // 승리: base+1(30)과 base+2(75)에서 10씩 → 숨김 학교와 base+3에 10씩
        assertThat(settlementService.settleOnce(base + 10, base + 1, base + 2, hiddenUnivId, base + 3, 10, 10))
                .isTrue();
        assertThat(hiddenScore()).isEqualTo(80.0);
        assertThat(publicScore(base + 3)).isEqualTo(85.0);
        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), String.valueOf(hiddenUnivId))).isNull();
    }

    private long insertUniversity(String name, boolean hidden) {
        long id = jdbcTemplate.queryForObject(
                "INSERT INTO university (name, hidden) VALUES (?, ?) RETURNING id", Long.class, name, hidden);
        departmentIds.add(jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '테스트학과') RETURNING id", Long.class, id));
        return id;
    }

    private String tokenOf(long universityId, String nickname, String email) {
        long departmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM department WHERE university_id = ?", Long.class, universityId);
        User user = userRepository.saveAndFlush(User.create(universityId, departmentId, nickname, email,
                emailHasher.hash(email), "{noop}unused", OffsetDateTime.now()));
        userIds.add(user.getId());
        return jwtProvider.createAccessToken(new AuthUser(user.getId(), universityId, departmentId, Role.USER), null);
    }

    private ResultActions click(String token, String submissionId, int clickCount) throws Exception {
        return mockMvc.perform(post("/api/v1/clicks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"submissionId":"%s","clickCount":%d}""".formatted(submissionId, clickCount)));
    }

    private ResultActions target(String path, String submissionId, long targetId, int clickCount) throws Exception {
        return mockMvc.perform(post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + publicToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"submissionId":"%s","targetUniversityId":%d,"clickCount":%d}"""
                        .formatted(submissionId, targetId, clickCount)));
    }

    /** 숨김 학교와 공개 테스트 학교를 같은 연합에 넣는다 */
    private void joinAlliance() {
        allianceId = jdbcTemplate.queryForObject("""
                INSERT INTO alliance (name, duration_hours, expired_at)
                VALUES ('숨김테스트연합', 24, now() + INTERVAL '24 hours') RETURNING id""", Long.class);
        for (long univId : List.of(hiddenUnivId, publicUnivId)) {
            jdbcTemplate.update("""
                    INSERT INTO alliance_university (alliance_id, university_id, applicant_user_id, approver_user_id, status)
                    VALUES (?, ?, ?, ?, 'ACCEPTED')""", allianceId, univId, userIds.get(1), userIds.get(1));
        }
    }

    private Double hiddenScore() {
        return redis.opsForZSet().score(RedisKey.RANK_UNIV_HIDDEN.of(), String.valueOf(hiddenUnivId));
    }

    private Double publicScore(long universityId) {
        return redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), String.valueOf(universityId));
    }

    private ResultActions search(String keyword) throws Exception {
        return mockMvc.perform(get("/api/v1/universities").param("keyword", keyword)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + publicToken))
                .andExpect(status().isOk());
    }
}
