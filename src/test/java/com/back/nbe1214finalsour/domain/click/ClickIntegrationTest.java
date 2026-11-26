package com.back.nbe1214finalsour.domain.click;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProperties;
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
import java.util.ArrayList;
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
 *
 * <p>테스트 학교는 서버가 뜬 뒤 만들어지므로 setUp에서 {@link UniversityCache}를 다시 불러오고,
 * 도서관 보너스 테스트는 좌표를 넣은 뒤 한 번 더 불러온다. 캐시는 공유 싱글턴이라 tearDown에서 학교를 지운 뒤
 * 다시 불러와 다른 테스트에 남기지 않는다.
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
    private JwtProperties jwtProperties;
    @Autowired
    private ClickerService clickerService;
    @Autowired
    private UniversityCache universityCache;

    /** 도서관 보너스 테스트에서 테스트 학교에 넣는 중앙도서관 좌표 (연세대 시드 좌표와 같은 값) */
    private static final double LIB_LAT = 37.563764;
    private static final double LIB_LNG = 126.937012;

    private long universityId;
    private long departmentId;
    /** 토큰의 학과 ID가 DB 값 대신 쓰이는지 확인할 때만 만든다 */
    private Long otherDepartmentId;
    private User user;
    private String token;
    /** 연합·대결 테스트가 만든 연합·대결. tearDown에서 점수 키를 지운다 */
    private final List<Long> allianceIds = new ArrayList<>();
    private final List<Long> matchIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clickerService.loadLevelTable();

        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name, logo_url) VALUES ('클릭테스트대', 'https://cdn.test/u.png') RETURNING id",
                Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        // 운영에서는 학교가 마이그레이션으로만 들어가 서버가 뜰 때 캐시에 있다. 테스트 학교도 그 상태로 맞춘다
        universityCache.load();
        user = userRepository.saveAndFlush(User.create(universityId, departmentId, "클릭왕", EMAIL,
                emailHasher.hash(EMAIL), "{noop}unused", OffsetDateTime.now()));
        token = jwtProvider.createAccessToken(new AuthUser(user.getId(), universityId, departmentId, Role.USER), null);
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

        redis.delete(List.of(RedisKey.UNIV_ALLIANCE.of(universityId), RedisKey.UNIV_MATCH.of(universityId)));
        redis.delete(allianceIds.stream().map(RedisKey.ALLIANCE_SCORE::of).toList());
        if (!allianceIds.isEmpty()) {
            // dirty:alliance는 공유 키라 이 테스트가 넣은 연합만 뺀다. 남기면 배치 테스트에서 이 연합을 반영하려 한다
            Object[] members = allianceIds.stream().map(String::valueOf).toArray();
            redis.opsForSet().remove(RedisKey.DIRTY_ALLIANCE.of(), members);
            redis.opsForSet().remove(RedisKey.DIRTY_ALLIANCE.of() + ":flushing", members);
        }
        if (!matchIds.isEmpty()) {
            Object[] members = matchIds.stream().map(String::valueOf).toArray();
            redis.opsForSet().remove(RedisKey.DIRTY_MATCH.of(), members);
            redis.opsForSet().remove(RedisKey.DIRTY_MATCH.of() + ":flushing", members);
        }
        redis.delete(matchIds.stream().map(RedisKey.MATCH_SCORE::of).toList());
        // 연합 행은 회원(applicant/approver)을 참조하므로 회원보다 먼저 지운다
        jdbcTemplate.update("DELETE FROM alliance_match WHERE alliance_a_id IN (SELECT id FROM alliance WHERE name = '클릭테스트연합')");
        jdbcTemplate.update("DELETE FROM alliance_university WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM alliance_chat_room WHERE alliance_id IN (SELECT id FROM alliance WHERE name = '클릭테스트연합')");
        jdbcTemplate.update("DELETE FROM alliance WHERE name = '클릭테스트연합'");

        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
        universityCache.load();
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
        String tokenWithOtherDepartment = jwtProvider.createAccessToken(new AuthUser(user.getId(), universityId, otherDepartmentId, Role.USER), null);
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
        String legacyToken = jwtProvider.createAccessToken(new AuthUser(user.getId(), universityId, null, Role.USER), null);
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
    @DisplayName("상태 캐시가 ACTIVE로 채워진 뒤 탈퇴하면, 남은 토큰의 클릭은 403 USER_SUSPENDED이고 점수는 오르지 않는다 (#47)")
    void withdrawnUserIsRejectedEvenAfterStatusWasCached() throws Exception {
        click("sub-1", 3).andExpect(status().isOk());
        assertThat(redis.opsForValue().get(RedisKey.USER_STATUS.of(user.getId()))).isEqualTo("ACTIVE");

        withdraw("unused").andExpect(status().isNoContent());

        assertThat(redis.opsForValue().get(RedisKey.USER_STATUS.of(user.getId()))).isEqualTo("WITHDRAWN");
        click("sub-2", 3)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
        assertThat(redis.opsForZSet().score(RedisKey.RANK_USER.of(universityId), String.valueOf(user.getId())))
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("탈퇴가 실패하면(비밀번호 불일치) 상태 캐시는 ACTIVE 그대로이고 클릭도 계속 된다")
    void failedWithdrawalKeepsStatusCache() throws Exception {
        click("sub-1", 3).andExpect(status().isOk());

        withdraw("wrong-password").andExpect(status().isBadRequest());

        assertThat(redis.opsForValue().get(RedisKey.USER_STATUS.of(user.getId()))).isEqualTo("ACTIVE");
        click("sub-2", 3).andExpect(status().isOk());
    }

    @Test
    @DisplayName("클릭이 채운 상태 캐시는 jwt.access-token-ttl 동안만 유지된다 (갱신을 빠뜨려도 DB 값으로 돌아옴)")
    void statusCacheExpiresWithAccessTokenTtl() throws Exception {
        click("sub-1", 3).andExpect(status().isOk());

        Long ttlSeconds = redis.getExpire(RedisKey.USER_STATUS.of(user.getId()));
        long configured = jwtProperties.accessTokenTtl().toSeconds();
        assertThat(ttlSeconds).isBetween(configured - 10, configured);
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

    // --- 도서관 이스터에그 ---

    @Test
    @DisplayName("도서관 반경 안에서 클릭하면 1.2배가 붙고 libraryBonus가 true다")
    void clickInsideLibraryGetsBonus() throws Exception {
        givenLibraryOfMyUniversity();

        clickAt("sub-1", 10, LIB_LAT, LIB_LNG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.earnedScore").value(12)) // 10 * 1 * 1.2
                .andExpect(jsonPath("$.data.multiplier").value(1.2))
                .andExpect(jsonPath("$.data.libraryBonus").value(true))
                .andExpect(jsonPath("$.data.university.totalScore").value(12));

        assertThat(redis.opsForZSet().score(RedisKey.RANK_UNIV.of(), String.valueOf(universityId)))
                .isEqualTo(12.0);
    }

    @Test
    @DisplayName("도서관에서 약 111m(위도 +0.0010°) 떨어져 클릭하면 보너스가 없다")
    void clickOutsideLibraryGetsNoBonus() throws Exception {
        givenLibraryOfMyUniversity();

        clickAt("sub-1", 10, LIB_LAT + 0.0010, LIB_LNG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.earnedScore").value(10))
                .andExpect(jsonPath("$.data.multiplier").value(1.0))
                .andExpect(jsonPath("$.data.libraryBonus").value(false));
    }

    @Test
    @DisplayName("위치를 보내지 않으면 도서관 좌표가 있는 학교여도 보너스가 없다")
    void clickWithoutLocationGetsNoBonus() throws Exception {
        givenLibraryOfMyUniversity();

        click("sub-1", 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.earnedScore").value(10))
                .andExpect(jsonPath("$.data.libraryBonus").value(false));
    }

    @Test
    @DisplayName("내 학교에 도서관 좌표가 없으면 그 자리에서 클릭해도 보너스가 없다")
    void clickAtUniversityWithoutLibraryGetsNoBonus() throws Exception {
        // 테스트 학교는 좌표 없이 만들어진다
        clickAt("sub-1", 10, LIB_LAT, LIB_LNG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.earnedScore").value(10))
                .andExpect(jsonPath("$.data.libraryBonus").value(false));
    }

    @Test
    @DisplayName("아이템 1.5배와 도서관 보너스가 겹치면 multiplier는 1.8이다")
    void libraryBonusStacksWithItemMultiplier() throws Exception {
        givenLibraryOfMyUniversity();
        redisHandler.setHash(RedisKey.USER_EFFECT.of(user.getId()), "multiplier", 1.5);

        clickAt("sub-1", 10, LIB_LAT, LIB_LNG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.earnedScore").value(18)) // 10 * 1 * 1.5 * 1.2
                .andExpect(jsonPath("$.data.multiplier").value(1.8))
                .andExpect(jsonPath("$.data.libraryBonus").value(true));
    }

    // --- 연합·대결 점수 ---
    // 기본 클리커 레벨 1이라 인정 점수 = clickCount다.

    @Test
    @DisplayName("연합이 없는 학교는 \"none\"을 캐시하고 연합·대결 점수 키를 만들지 않는다")
    void clickWithoutAllianceAddsNoAllianceScore() throws Exception {
        click("sub-1", 3).andExpect(status().isOk());
        click("sub-2", 2).andExpect(status().isOk());   // "none" 캐시를 읽는 두 번째 클릭도 성공해야 한다

        assertThat(redis.opsForValue().get(RedisKey.UNIV_ALLIANCE.of(universityId))).isEqualTo("none");
        assertThat(redis.hasKey(RedisKey.ALLIANCE_SCORE.of(0))).isFalse();
        assertThat(redis.hasKey(RedisKey.MATCH_SCORE.of(0))).isFalse();
    }

    @Test
    @DisplayName("연합만 있으면 연합 점수 HASH의 내 학교 field에 쌓이고, 대결 점수는 없다")
    void clickInAllianceAddsAllianceScoreByUniversity() throws Exception {
        long myAlliance = givenMyAlliance("now() + INTERVAL '24 hours'");

        click("sub-1", 3).andExpect(status().isOk());
        click("sub-2", 2).andExpect(status().isOk());   // 두 번째는 캐시에서 연합을 읽는다

        assertThat(redis.opsForHash().get(RedisKey.ALLIANCE_SCORE.of(myAlliance), String.valueOf(universityId)))
                .isEqualTo("5");
        assertThat(redis.opsForValue().get(RedisKey.UNIV_ALLIANCE.of(universityId)))
                .isEqualTo(String.valueOf(myAlliance));
        assertThat(redis.getExpire(RedisKey.UNIV_ALLIANCE.of(universityId))).isPositive();
        assertThat(redis.opsForValue().get(RedisKey.UNIV_MATCH.of(universityId))).isEqualTo("none");
        // 1분 배치가 DB 연합 점수에 반영하도록 표시한다
        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_ALLIANCE.of(), String.valueOf(myAlliance))).isTrue();
    }

    @Test
    @DisplayName("진행 중 대결의 B 연합이면 대결 점수 HASH의 B에 쌓이고 A에는 쌓이지 않는다")
    void clickInMatchAddsToMySide() throws Exception {
        long myAlliance = givenMyAlliance("now() + INTERVAL '24 hours'");
        long rivalAlliance = givenAlliance("now() + INTERVAL '24 hours'");
        long matchId = givenMatch(rivalAlliance, myAlliance, "now() - INTERVAL '1 hour'");

        click("sub-1", 3).andExpect(status().isOk());
        click("sub-2", 2).andExpect(status().isOk());   // 두 번째는 캐시에서 대결을 읽는다

        assertThat(redis.opsForHash().get(RedisKey.MATCH_SCORE.of(matchId), "B")).isEqualTo("5");
        assertThat(redis.opsForHash().get(RedisKey.MATCH_SCORE.of(matchId), "A")).isNull();
        assertThat(redis.opsForValue().get(RedisKey.UNIV_MATCH.of(universityId))).isEqualTo(matchId + ":B");
        // 1분 배치가 DB 대결 점수에 반영하도록 표시한다
        assertThat(redis.opsForSet().isMember(RedisKey.DIRTY_MATCH.of(), String.valueOf(matchId))).isTrue();
        assertThat(redis.getExpire(RedisKey.UNIV_MATCH.of(universityId))).isPositive();
        // 대결 중에도 연합 점수는 따로 쌓인다
        assertThat(redis.opsForHash().get(RedisKey.ALLIANCE_SCORE.of(myAlliance), String.valueOf(universityId)))
                .isEqualTo("5");
    }

    @Test
    @DisplayName("종료 시각이 지난 대결은 상태가 IN_PROGRESS여도 점수를 쌓지 않는다 (정산 전 클릭)")
    void clickAfterMatchEndAddsNoMatchScore() throws Exception {
        long myAlliance = givenMyAlliance("now() + INTERVAL '24 hours'");
        long rivalAlliance = givenAlliance("now() + INTERVAL '24 hours'");
        long matchId = givenMatch(myAlliance, rivalAlliance, "now() - INTERVAL '4 hours'");

        click("sub-1", 3).andExpect(status().isOk());

        assertThat(redis.hasKey(RedisKey.MATCH_SCORE.of(matchId))).isFalse();
        assertThat(redis.opsForValue().get(RedisKey.UNIV_MATCH.of(universityId))).isEqualTo("none");
    }

    @Test
    @DisplayName("만료 시각이 지난 연합은 만료 배치 전이라 ACCEPTED여도 연합이 없는 것으로 보고 클릭은 성공한다")
    void clickInExpiredAllianceIsTreatedAsNoAlliance() throws Exception {
        long expiredAlliance = jdbcTemplate.queryForObject("""
                INSERT INTO alliance (name, duration_hours, created_at, expired_at)
                VALUES ('클릭테스트연합', 24, now() - INTERVAL '2 days', now() - INTERVAL '1 day') RETURNING id""",
                Long.class);
        allianceIds.add(expiredAlliance);
        joinAlliance(expiredAlliance);

        click("sub-1", 3).andExpect(status().isOk());

        assertThat(redis.opsForValue().get(RedisKey.UNIV_ALLIANCE.of(universityId))).isEqualTo("none");
        assertThat(redis.hasKey(RedisKey.ALLIANCE_SCORE.of(expiredAlliance))).isFalse();
    }

    private long givenAlliance(String expiredAtSql) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO alliance (name, duration_hours, expired_at)
                VALUES ('클릭테스트연합', 24, %s) RETURNING id""".formatted(expiredAtSql), Long.class);
        allianceIds.add(id);
        return id;
    }

    /** 연합을 만들고 테스트 학교를 승인된 소속으로 넣는다 */
    private long givenMyAlliance(String expiredAtSql) {
        long id = givenAlliance(expiredAtSql);
        joinAlliance(id);
        return id;
    }

    private void joinAlliance(long allianceId) {
        jdbcTemplate.update("""
                INSERT INTO alliance_university (alliance_id, university_id, applicant_user_id, approver_user_id, status)
                VALUES (?, ?, ?, ?, 'ACCEPTED')""", allianceId, universityId, user.getId(), user.getId());
    }

    /** 3시간짜리 IN_PROGRESS 대결. 종료 시각 = 시작 시각 + 3시간 */
    private long givenMatch(long allianceAId, long allianceBId, String startedAtSql) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO alliance_match (alliance_a_id, alliance_b_id, started_at, ended_at, duration_hours,
                                            request_expired_at, status)
                VALUES (?, ?, %1$s, %1$s + INTERVAL '3 hours', 3, now(), 'IN_PROGRESS') RETURNING id"""
                .formatted(startedAtSql), Long.class, allianceAId, allianceBId);
        matchIds.add(id);
        return id;
    }

    private void givenLibraryOfMyUniversity() {
        jdbcTemplate.update("UPDATE university SET library_lat = ?, library_lng = ? WHERE id = ?",
                LIB_LAT, LIB_LNG, universityId);
        universityCache.load();
    }

    /** 명세(도서관 이스터에그)의 요청 필드 이름 latitude·longitude로 보낸다 */
    private ResultActions clickAt(String submissionId, int clickCount, double latitude, double longitude)
            throws Exception {
        return mockMvc.perform(post("/api/v1/clicks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"submissionId":"%s","clickCount":%d,"latitude":%s,"longitude":%s}"""
                        .formatted(submissionId, clickCount, latitude, longitude)));
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

    /** 회원 비밀번호는 {@code {noop}unused}라 "unused"가 맞는 비밀번호다 */
    private ResultActions withdraw(String password) throws Exception {
        return mockMvc.perform(post("/api/v1/users/me/withdrawal")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"password":"%s"}""".formatted(password)));
    }
}
