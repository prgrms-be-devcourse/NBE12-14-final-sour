package com.back.nbe1214finalsour.domain.click;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.click.service.ScoreFlushService;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.domain.user.repository.projections.UserUniv;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 1분 배치가 Redis 점수를 DB에 반영하는지 PostgreSQL·Redis(Testcontainers)로 확인한다.
 *
 * <p>DB 변경은 {@code @Transactional}로 롤백되지만 Redis는 롤백되지 않으므로, 테스트가 쓴 키를 직접 지운다.
 * {@code dirty:*}는 모든 테스트가 함께 쓰는 키라 남기면 다른 테스트에 섞인다.
 */
@IntegrationTest
@Transactional
class ScoreFlushIntegrationTest {

    @Autowired
    private ScoreFlushService scoreFlushService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UniversityRepository universityRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    /** 배치 도중에 클릭이 들어오는 순간을 만들기 위해 감싼다. 따로 설정하지 않으면 실제 메서드가 그대로 돈다. */
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailHasher emailHasher;

    private final List<String> usedKeys = new ArrayList<>(List.of(
            RedisKey.DIRTY_USER.of(), RedisKey.DIRTY_UNIV.of(), RedisKey.DIRTY_DEPT.of(),
            RedisKey.DIRTY_CLICKER.of(), RedisKey.DIRTY_ALLIANCE.of(), RedisKey.DIRTY_MATCH.of(),
            // 테스트가 중간에 실패하면 배치가 떼어 낸 키가 남는다
            RedisKey.DIRTY_USER.of() + ":flushing", RedisKey.DIRTY_UNIV.of() + ":flushing",
            RedisKey.DIRTY_DEPT.of() + ":flushing", RedisKey.DIRTY_CLICKER.of() + ":flushing",
            RedisKey.DIRTY_ALLIANCE.of() + ":flushing", RedisKey.DIRTY_MATCH.of() + ":flushing"));
    /** {@code rank:univ}는 키가 하나뿐이라 통째로 지우지 않고 테스트가 넣은 학교만 뺀다. */
    private final List<String> addedUnivRankMembers = new ArrayList<>();
    /** {@code score:user:attack}도 Hash 하나를 모두가 같이 쓰므로 테스트가 넣은 회원 필드만 뺀다. */
    private final List<String> addedAttackFields = new ArrayList<>();

    @AfterEach
    void cleanUpRedis() {
        stringRedisTemplate.delete(usedKeys);
        if (!addedUnivRankMembers.isEmpty()) {
            stringRedisTemplate.opsForZSet().remove(RedisKey.RANK_UNIV.of(), addedUnivRankMembers.toArray());
        }
        if (!addedAttackFields.isEmpty()) {
            stringRedisTemplate.opsForHash().delete(RedisKey.SCORE_USER_ATTACK.of(), addedAttackFields.toArray());
        }
    }

    @Test
    @DisplayName("dirty 회원의 Redis 기여도로 DB 기여도를 덮어쓴다")
    void flushesDirtyUserScore() {
        // given
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "배치학과"));
        User user = saveUser(university, department, "배치회원", "flush-user@test.ac.kr");

        String rankKey = RedisKey.RANK_USER.of(university.getId());
        usedKeys.add(rankKey);
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_USER.of(), String.valueOf(user.getId()));
        stringRedisTemplate.opsForZSet().add(rankKey, String.valueOf(user.getId()), 500);

        // when
        scoreFlushService.flush();

        // then
        assertThat(contribScoreOf(user.getId())).isEqualTo(500L);
    }

    @Test
    @DisplayName("공격만 한 회원은 Redis 공격 점수로 DB 공격 점수를 덮어쓰고, 기여도는 그대로 둔다")
    void flushesAttackScoreOfAttackOnlyUser() {
        // given: 기여도 순위(rank:user)에는 없고 공격 점수만 있다
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "배치학과"));
        User user = saveUser(university, department, "공격회원", "flush-attack@test.ac.kr");
        givenDbScores(user.getId(), 100, 5);

        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_USER.of(), String.valueOf(user.getId()));
        givenAttackScore(user.getId(), 70);

        // when
        scoreFlushService.flush();

        // then
        assertThat(attackScoreOf(user.getId())).isEqualTo(70L);
        assertThat(contribScoreOf(user.getId())).isEqualTo(100L);
    }

    @Test
    @DisplayName("클릭만 한 회원은 기여도만 덮어쓰고, Redis에 공격 점수가 없어도 DB 공격 점수를 지우지 않는다")
    void keepsAttackScoreOfClickOnlyUser() {
        // given: 공격 점수 Hash에는 없고 기여도만 있다
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "배치학과"));
        User user = saveUser(university, department, "클릭회원", "flush-click@test.ac.kr");
        givenDbScores(user.getId(), 100, 5);

        String rankKey = RedisKey.RANK_USER.of(university.getId());
        usedKeys.add(rankKey);
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_USER.of(), String.valueOf(user.getId()));
        stringRedisTemplate.opsForZSet().add(rankKey, String.valueOf(user.getId()), 400);

        // when
        scoreFlushService.flush();

        // then
        assertThat(contribScoreOf(user.getId())).isEqualTo(400L);
        assertThat(attackScoreOf(user.getId())).isEqualTo(5L);
    }

    @Test
    @DisplayName("기여도와 공격 점수가 모두 있으면 둘 다 덮어쓰고, 다른 회원의 점수와 섞이지 않는다")
    void flushesBothScoresWithoutMixingUsers() {
        // given: 같은 학교에 둘 다 있는 회원, 기여도만 있는 회원, 공격 점수만 있는 회원
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "배치학과"));
        User both = saveUser(university, department, "둘다회원", "flush-both@test.ac.kr");
        User clickOnly = saveUser(university, department, "클릭만회원", "flush-click-only@test.ac.kr");
        User attackOnly = saveUser(university, department, "공격만회원", "flush-attack-only@test.ac.kr");
        givenDbScores(both.getId(), 1, 1);
        givenDbScores(clickOnly.getId(), 2, 2);
        givenDbScores(attackOnly.getId(), 3, 3);

        String rankKey = RedisKey.RANK_USER.of(university.getId());
        usedKeys.add(rankKey);
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_USER.of(),
                String.valueOf(both.getId()), String.valueOf(clickOnly.getId()), String.valueOf(attackOnly.getId()));
        stringRedisTemplate.opsForZSet().add(rankKey, String.valueOf(both.getId()), 1000);
        stringRedisTemplate.opsForZSet().add(rankKey, String.valueOf(clickOnly.getId()), 2000);
        givenAttackScore(both.getId(), 10);
        givenAttackScore(attackOnly.getId(), 30);

        // when
        scoreFlushService.flush();

        // then
        assertThat(List.of(contribScoreOf(both.getId()), attackScoreOf(both.getId()))).containsExactly(1000L, 10L);
        assertThat(List.of(contribScoreOf(clickOnly.getId()), attackScoreOf(clickOnly.getId()))).containsExactly(2000L, 2L);
        assertThat(List.of(contribScoreOf(attackOnly.getId()), attackScoreOf(attackOnly.getId()))).containsExactly(3L, 30L);
    }

    @Test
    @DisplayName("dirty 학교의 Redis 점수로 DB 학교 점수를 덮어쓴다")
    void flushesDirtyUniversityScore() {
        // given
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        String univId = String.valueOf(university.getId());

        addedUnivRankMembers.add(univId);
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_UNIV.of(), univId);
        stringRedisTemplate.opsForZSet().add(RedisKey.RANK_UNIV.of(), univId, 1200);

        // when
        scoreFlushService.flush();

        // then
        assertThat(universityScoreOf(university.getId())).isEqualTo(1200L);
    }

    @Test
    @DisplayName("dirty 학과의 Redis 점수로 DB 학과 점수를 덮어쓴다")
    void flushesDirtyDepartmentScore() {
        // given
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "배치학과"));
        String deptId = String.valueOf(department.getId());

        String rankKey = RedisKey.RANK_DEPT.of(university.getId());
        usedKeys.add(rankKey);
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_DEPT.of(), deptId);
        stringRedisTemplate.opsForZSet().add(rankKey, deptId, 300);

        // when
        scoreFlushService.flush();

        // then
        assertThat(departmentScoreOf(department.getId())).isEqualTo(300L);
    }

    @Test
    @DisplayName("반영한 뒤에는 dirty 표시를 지워 다음 배치가 다시 쓰지 않게 한다")
    void clearsDirtySetsAfterFlush() {
        // given
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "배치학과"));
        User user = saveUser(university, department, "배치회원", "flush-clear@test.ac.kr");
        String univId = String.valueOf(university.getId());
        String deptId = String.valueOf(department.getId());
        String userId = String.valueOf(user.getId());

        String deptRankKey = RedisKey.RANK_DEPT.of(university.getId());
        String userRankKey = RedisKey.RANK_USER.of(university.getId());
        usedKeys.add(deptRankKey);
        usedKeys.add(userRankKey);
        addedUnivRankMembers.add(univId);

        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_UNIV.of(), univId);
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_DEPT.of(), deptId);
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_USER.of(), userId);
        stringRedisTemplate.opsForZSet().add(RedisKey.RANK_UNIV.of(), univId, 100);
        stringRedisTemplate.opsForZSet().add(deptRankKey, deptId, 100);
        stringRedisTemplate.opsForZSet().add(userRankKey, userId, 100);

        // when
        scoreFlushService.flush();

        // then
        assertThat(stringRedisTemplate.opsForSet().size(RedisKey.DIRTY_UNIV.of())).isZero();
        assertThat(stringRedisTemplate.opsForSet().size(RedisKey.DIRTY_DEPT.of())).isZero();
        assertThat(stringRedisTemplate.opsForSet().size(RedisKey.DIRTY_USER.of())).isZero();
    }

    @Test
    @DisplayName("배치가 도는 중에 dirty가 된 회원은 지우지 않고 다음 배치로 넘긴다")
    void keepsUsersMarkedDirtyDuringFlush() {
        // given
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "배치학과"));
        User before = saveUser(university, department, "배치전회원", "flush-before@test.ac.kr");
        User during = saveUser(university, department, "배치중회원", "flush-during@test.ac.kr");
        String duringId = String.valueOf(during.getId());

        String rankKey = RedisKey.RANK_USER.of(university.getId());
        usedKeys.add(rankKey);
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_USER.of(), String.valueOf(before.getId()));
        stringRedisTemplate.opsForZSet().add(rankKey, String.valueOf(before.getId()), 500);

        // 배치가 dirty 회원을 읽어 DB를 조회하는 순간, 다른 회원의 클릭이 들어온 것처럼 만든다.
        // 레포지토리는 JDK 프록시라 실제 메서드를 부를 수 없어서, 조회 결과(모두 이 학교 소속)를 직접 돌려준다.
        doAnswer(invocation -> {
            stringRedisTemplate.opsForZSet().add(rankKey, duringId, 70);
            stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_USER.of(), duringId);
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(id -> new UserUniv(id, university.getId())).toList();
        }).when(userRepository).findUniversityIdsByIdIn(anyCollection());

        // when
        scoreFlushService.flush();

        // then
        assertThat(stringRedisTemplate.opsForSet().members(RedisKey.DIRTY_USER.of()))
                .containsExactly(duringId);
    }

    @Test
    @DisplayName("반영 중에 실패한 회원은 잃지 않고 다음 배치에서 다시 반영한다")
    void retriesUsersFromFailedFlush() {
        // given
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "배치학과"));
        User failed = saveUser(university, department, "실패회원", "flush-failed@test.ac.kr");
        User next = saveUser(university, department, "다음회원", "flush-next@test.ac.kr");

        String rankKey = RedisKey.RANK_USER.of(university.getId());
        usedKeys.add(rankKey);
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_USER.of(), String.valueOf(failed.getId()));
        stringRedisTemplate.opsForZSet().add(rankKey, String.valueOf(failed.getId()), 500);

        // 첫 배치는 회원을 조회하다 실패하고, 그다음부터는 정상 조회(모두 이 학교 소속)를 돌려준다.
        // DB 오류로 실패시키면 테스트 트랜잭션이 깨져 두 번째 배치를 돌릴 수 없어서 조회 단계에서 실패시킨다.
        doThrow(new IllegalStateException("DB 장애"))
                .doAnswer(invocation -> {
                    Collection<Long> ids = invocation.getArgument(0);
                    return ids.stream().map(id -> new UserUniv(id, university.getId())).toList();
                })
                .when(userRepository).findUniversityIdsByIdIn(anyCollection());

        assertThatThrownBy(() -> scoreFlushService.flush()).isInstanceOf(IllegalStateException.class);

        // 실패한 뒤 다른 회원이 클릭한다
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_USER.of(), String.valueOf(next.getId()));
        stringRedisTemplate.opsForZSet().add(rankKey, String.valueOf(next.getId()), 70);

        // when
        scoreFlushService.flush();

        // then
        assertThat(contribScoreOf(failed.getId())).isEqualTo(500L);
        assertThat(contribScoreOf(next.getId())).isEqualTo(70L);
    }

    @Test
    @DisplayName("Redis 점수가 없는 dirty 학과는 건너뛰고 나머지 학과는 반영한다")
    void skipsDepartmentsMissingFromRanking() {
        // given
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        Department scored = departmentRepository.saveAndFlush(new Department(university, "점수있는학과"));
        Department missing = departmentRepository.saveAndFlush(new Department(university, "점수없는학과"));

        String rankKey = RedisKey.RANK_DEPT.of(university.getId());
        usedKeys.add(rankKey);
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_DEPT.of(),
                String.valueOf(scored.getId()), String.valueOf(missing.getId()));
        // missing은 순위 ZSET에 없다 (Redis 재시작 등으로 점수가 사라진 경우)
        stringRedisTemplate.opsForZSet().add(rankKey, String.valueOf(scored.getId()), 300);

        // when
        scoreFlushService.flush();

        // then
        assertThat(departmentScoreOf(scored.getId())).isEqualTo(300L);
        assertThat(departmentScoreOf(missing.getId())).isZero();
    }

    @Test
    @DisplayName("dirty:clicker에 있는 회원의 Redis 레벨로 DB 클리커 레벨을 올린다")
    void flushesDirtyClickerLevel() {
        // given
        ensureClickerLevels();
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "배치학과"));
        User user = saveUser(university, department, "레벨회원", "flush-level@test.ac.kr");
        String userId = String.valueOf(user.getId());

        String levelKey = RedisKey.USER_CLICKER.of(user.getId());
        usedKeys.add(levelKey);
        stringRedisTemplate.opsForValue().set(levelKey, "2");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_CLICKER.of(), userId);

        // when
        scoreFlushService.flush();

        // then
        assertThat(clickerLevelOf(user.getId())).isEqualTo(2L);
    }

    @Test
    @DisplayName("DB 레벨이 Redis 레벨보다 높으면 DB 레벨을 낮추지 않는다")
    void doesNotLowerClickerLevel() {
        // given
        ensureClickerLevels();
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "배치학과"));
        User user = saveUser(university, department, "레벨회원", "flush-level-keep@test.ac.kr");
        jdbcTemplate.update("UPDATE users SET clicker_id = 3 WHERE id = ?", user.getId());

        String levelKey = RedisKey.USER_CLICKER.of(user.getId());
        usedKeys.add(levelKey);
        stringRedisTemplate.opsForValue().set(levelKey, "2");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_CLICKER.of(), String.valueOf(user.getId()));

        // when
        scoreFlushService.flush();

        // then
        assertThat(clickerLevelOf(user.getId())).isEqualTo(3L);
    }

    @Test
    @DisplayName("레벨을 반영한 뒤에는 dirty:clicker와 떼어 낸 dirty:clicker:flushing을 지운다")
    void clearsDirtyClickerAfterFlush() {
        // given
        ensureClickerLevels();
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "배치학과"));
        User user = saveUser(university, department, "레벨회원", "flush-level-clear@test.ac.kr");

        String levelKey = RedisKey.USER_CLICKER.of(user.getId());
        usedKeys.add(levelKey);
        stringRedisTemplate.opsForValue().set(levelKey, "2");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_CLICKER.of(), String.valueOf(user.getId()));

        // when
        scoreFlushService.flush();

        // then
        assertThat(stringRedisTemplate.opsForSet().size(RedisKey.DIRTY_CLICKER.of())).isZero();
        assertThat(stringRedisTemplate.hasKey(RedisKey.DIRTY_CLICKER.of() + ":flushing")).isFalse();
    }

    @Test
    @DisplayName("Redis 레벨이 없는 dirty:clicker 회원은 건너뛰고 나머지 회원은 반영한다")
    void skipsUsersMissingClickerLevel() {
        // given
        ensureClickerLevels();
        University university = universityRepository.saveAndFlush(new University("배치테스트대학교"));
        Department department = departmentRepository.saveAndFlush(new Department(university, "배치학과"));
        User scored = saveUser(university, department, "레벨있는회원", "flush-level-a@test.ac.kr");
        User missing = saveUser(university, department, "레벨없는회원", "flush-level-b@test.ac.kr");

        String scoredLevelKey = RedisKey.USER_CLICKER.of(scored.getId());
        usedKeys.add(scoredLevelKey);
        stringRedisTemplate.opsForValue().set(scoredLevelKey, "2");
        // missing은 레벨 키가 없다 (Redis 재시작 등으로 사라진 경우)
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_CLICKER.of(),
                String.valueOf(scored.getId()), String.valueOf(missing.getId()));

        // when
        scoreFlushService.flush();

        // then
        assertThat(clickerLevelOf(scored.getId())).isEqualTo(2L);
        assertThat(clickerLevelOf(missing.getId())).isEqualTo(1L);
    }

    /** DB 레벨 2·3이 FK(users.clicker_id → clicker.id)를 통과하도록 한다. 시드에 있으면 건너뛰고, 트랜잭션 롤백으로 사라진다. */
    // --- 연합 점수 ---
    // alliance:{a}:score HASH(field = 학교 ID)의 합을 alliance.total_score에 쓴다

    @Test
    @DisplayName("dirty 연합의 학교별 Redis 점수를 합쳐 DB 연합 점수를 덮어쓴다")
    void flushesDirtyAllianceTotalScore() {
        // given
        long allianceId = givenAlliance(0);
        givenAllianceScore(allianceId, "101", "30", "102", "12");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_ALLIANCE.of(), String.valueOf(allianceId));

        // when
        scoreFlushService.flush();

        // then
        assertThat(allianceScoreOf(allianceId)).isEqualTo(42L);
    }

    @Test
    @DisplayName("Redis 연합 점수가 없는 dirty 연합은 0으로 덮지 않고 건너뛰고, 나머지 연합은 반영한다")
    void skipsAllianceWithoutRedisScore() {
        // given: Redis가 유실된 연합(DB 100점)과 정상 연합
        long lostAllianceId = givenAlliance(100);
        long allianceId = givenAlliance(0);
        usedKeys.add(RedisKey.ALLIANCE_SCORE.of(lostAllianceId));
        givenAllianceScore(allianceId, "101", "7");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_ALLIANCE.of(),
                String.valueOf(lostAllianceId), String.valueOf(allianceId));

        // when
        scoreFlushService.flush();

        // then
        assertThat(allianceScoreOf(lostAllianceId)).isEqualTo(100L);
        assertThat(allianceScoreOf(allianceId)).isEqualTo(7L);
    }

    @Test
    @DisplayName("연합 점수를 반영한 뒤에는 dirty:alliance와 떼어 낸 dirty:alliance:flushing을 지운다")
    void clearsDirtyAllianceAfterFlush() {
        // given
        long allianceId = givenAlliance(0);
        givenAllianceScore(allianceId, "101", "5");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_ALLIANCE.of(), String.valueOf(allianceId));

        // when
        scoreFlushService.flush();

        // then
        assertThat(stringRedisTemplate.hasKey(RedisKey.DIRTY_ALLIANCE.of())).isFalse();
        assertThat(stringRedisTemplate.hasKey(RedisKey.DIRTY_ALLIANCE.of() + ":flushing")).isFalse();
    }

    // 학교별 점수는 alliance_university(신청·가입·탈퇴 이력)의 ACCEPTED·LEFT 중 가장 최근 행에 쓴다

    @Test
    @DisplayName("dirty 연합의 학교별 Redis 점수를 각 학교의 소속 행(alliance_university)에 쓴다")
    void flushesAllianceScorePerUniversity() {
        // given
        University univ1 = universityRepository.saveAndFlush(new University("배치연합1대학교"));
        University univ2 = universityRepository.saveAndFlush(new University("배치연합2대학교"));
        long userId = saveUser(univ1, departmentRepository.saveAndFlush(new Department(univ1, "배치학과")),
                "배치연합회원", "flush-alliance@test.ac.kr").getId();
        long allianceId = givenAlliance(0);
        long member1 = givenMember(allianceId, univ1.getId(), userId, "ACCEPTED");
        long member2 = givenMember(allianceId, univ2.getId(), userId, "ACCEPTED");
        givenAllianceScore(allianceId, String.valueOf(univ1.getId()), "30", String.valueOf(univ2.getId()), "12");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_ALLIANCE.of(), String.valueOf(allianceId));

        // when
        scoreFlushService.flush();

        // then
        assertThat(memberScoreOf(member1)).isEqualTo(30L);
        assertThat(memberScoreOf(member2)).isEqualTo(12L);
        assertThat(allianceScoreOf(allianceId)).isEqualTo(42L);
    }

    @Test
    @DisplayName("연합이 만료돼 소속 행이 LEFT가 된 뒤에 배치가 돌아도 그 행에 쓴다")
    void flushesToLeftMemberAfterExpiry() {
        // given: 만료 스케줄러가 먼저 돌아 ACCEPTED → LEFT
        University univ = universityRepository.saveAndFlush(new University("배치연합1대학교"));
        long userId = saveUser(univ, departmentRepository.saveAndFlush(new Department(univ, "배치학과")),
                "배치연합회원", "flush-alliance@test.ac.kr").getId();
        long allianceId = givenAlliance(0);
        long member = givenMember(allianceId, univ.getId(), userId, "LEFT");
        givenAllianceScore(allianceId, String.valueOf(univ.getId()), "8");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_ALLIANCE.of(), String.valueOf(allianceId));

        // when
        scoreFlushService.flush();

        // then
        assertThat(memberScoreOf(member)).isEqualTo(8L);
    }

    @Test
    @DisplayName("같은 학교의 이력 행이 여럿이면 가장 최근 ACCEPTED·LEFT 행에만 쓰고, 거절·대기 행은 건드리지 않는다")
    void flushesOnlyToLatestMembershipRow() {
        // given: 거절 → 가입 후 탈퇴 → 재가입
        University univ = universityRepository.saveAndFlush(new University("배치연합1대학교"));
        long userId = saveUser(univ, departmentRepository.saveAndFlush(new Department(univ, "배치학과")),
                "배치연합회원", "flush-alliance@test.ac.kr").getId();
        long allianceId = givenAlliance(0);
        long rejected = givenMember(allianceId, univ.getId(), userId, "REJECTED");
        long oldLeft = givenMember(allianceId, univ.getId(), userId, "LEFT");
        long current = givenMember(allianceId, univ.getId(), userId, "ACCEPTED");
        givenAllianceScore(allianceId, String.valueOf(univ.getId()), "15");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_ALLIANCE.of(), String.valueOf(allianceId));

        // when
        scoreFlushService.flush();

        // then
        assertThat(memberScoreOf(current)).isEqualTo(15L);
        assertThat(memberScoreOf(oldLeft)).isZero();
        assertThat(memberScoreOf(rejected)).isZero();
    }

    @Test
    @DisplayName("탈퇴한 뒤 같은 연합에 다시 신청 중(PENDING)이면, 가장 최근 행인 신청 행이 아니라 LEFT 행에 쓴다")
    void skipsNewerPendingRow() {
        // given
        University univ = universityRepository.saveAndFlush(new University("배치연합1대학교"));
        long userId = saveUser(univ, departmentRepository.saveAndFlush(new Department(univ, "배치학과")),
                "배치연합회원", "flush-alliance@test.ac.kr").getId();
        long allianceId = givenAlliance(0);
        long left = givenMember(allianceId, univ.getId(), userId, "LEFT");
        long pending = jdbcTemplate.queryForObject("""
                INSERT INTO alliance_university (alliance_id, university_id, applicant_user_id, status)
                VALUES (?, ?, ?, 'PENDING') RETURNING id""", Long.class, allianceId, univ.getId(), userId);
        givenAllianceScore(allianceId, String.valueOf(univ.getId()), "6");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_ALLIANCE.of(), String.valueOf(allianceId));

        // when
        scoreFlushService.flush();

        // then
        assertThat(memberScoreOf(left)).isEqualTo(6L);
        assertThat(memberScoreOf(pending)).isZero();
    }

    private long givenMember(long allianceId, long universityId, long userId, String status) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO alliance_university (alliance_id, university_id, applicant_user_id, approver_user_id, status)
                VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class, allianceId, universityId, userId, userId, status);
    }

    private Long memberScoreOf(long memberId) {
        return jdbcTemplate.queryForObject("SELECT total_score FROM alliance_university WHERE id = ?", Long.class, memberId);
    }

    // --- 대결 점수 ---
    // match:{m}:score HASH(field = A, B)를 alliance_match.a_score·b_score에 쓴다.
    // 끝난 대결의 점수는 정산이 확정하므로 IN_PROGRESS일 때만 쓴다.

    @Test
    @DisplayName("진행 중인 dirty 대결의 Redis A·B 점수로 DB 대결 점수를 덮어쓴다")
    void flushesInProgressMatchScore() {
        // given
        long matchId = givenMatch("IN_PROGRESS", 0, 0);
        givenMatchScore(matchId, "A", "40", "B", "25");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_MATCH.of(), String.valueOf(matchId));

        // when
        scoreFlushService.flush();

        // then
        assertThat(matchScoresOf(matchId)).containsExactly(40L, 25L);
    }

    @Test
    @DisplayName("한쪽 편만 점수가 있으면 다른 편은 0으로 쓴다")
    void missingSideIsZero() {
        // given: DB에는 이전 배치 값이 있다고 가정. B는 아직 Redis에 쌓인 적이 없다
        long matchId = givenMatch("IN_PROGRESS", 3, 0);
        givenMatchScore(matchId, "A", "9");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_MATCH.of(), String.valueOf(matchId));

        // when
        scoreFlushService.flush();

        // then
        assertThat(matchScoresOf(matchId)).containsExactly(9L, 0L);
    }

    @Test
    @DisplayName("끝난(FINISHED) 대결은 정산이 확정한 점수를 덮지 않는다")
    void doesNotOverwriteFinishedMatch() {
        // given: 정산이 50:20으로 확정한 뒤, 마감 직전 클릭으로 dirty가 남은 상황
        long matchId = givenMatch("FINISHED", 50, 20);
        givenMatchScore(matchId, "A", "55", "B", "20");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_MATCH.of(), String.valueOf(matchId));

        // when
        scoreFlushService.flush();

        // then
        assertThat(matchScoresOf(matchId)).containsExactly(50L, 20L);
    }

    @Test
    @DisplayName("Redis 대결 점수가 없는 dirty 대결은 0으로 덮지 않고 건너뛰고, 나머지 대결은 반영한다")
    void skipsMatchWithoutRedisScore() {
        // given
        long lostMatchId = givenMatch("IN_PROGRESS", 30, 10);
        long matchId = givenMatch("IN_PROGRESS", 0, 0);
        usedKeys.add(RedisKey.MATCH_SCORE.of(lostMatchId));
        givenMatchScore(matchId, "B", "4");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_MATCH.of(),
                String.valueOf(lostMatchId), String.valueOf(matchId));

        // when
        scoreFlushService.flush();

        // then
        assertThat(matchScoresOf(lostMatchId)).containsExactly(30L, 10L);
        assertThat(matchScoresOf(matchId)).containsExactly(0L, 4L);
    }

    @Test
    @DisplayName("대결 점수를 반영한 뒤에는 dirty:match와 떼어 낸 dirty:match:flushing을 지운다")
    void clearsDirtyMatchAfterFlush() {
        // given
        long matchId = givenMatch("IN_PROGRESS", 0, 0);
        givenMatchScore(matchId, "A", "1");
        stringRedisTemplate.opsForSet().add(RedisKey.DIRTY_MATCH.of(), String.valueOf(matchId));

        // when
        scoreFlushService.flush();

        // then
        assertThat(stringRedisTemplate.hasKey(RedisKey.DIRTY_MATCH.of())).isFalse();
        assertThat(stringRedisTemplate.hasKey(RedisKey.DIRTY_MATCH.of() + ":flushing")).isFalse();
    }

    /** 3시간짜리 대결. 시작 1시간 전, 종료 2시간 뒤 */
    private long givenMatch(String status, long aScore, long bScore) {
        long allianceA = givenAlliance(0);
        long allianceB = givenAlliance(0);
        return jdbcTemplate.queryForObject("""
                INSERT INTO alliance_match (alliance_a_id, alliance_b_id, started_at, ended_at, duration_hours,
                                            request_expired_at, status, a_score, b_score)
                VALUES (?, ?, now() - INTERVAL '1 hour', now() + INTERVAL '2 hours', 3, now(), ?, ?, ?)
                RETURNING id""", Long.class, allianceA, allianceB, status, aScore, bScore);
    }

    /** 편(A/B)과 점수를 번갈아 받는다 */
    private void givenMatchScore(long matchId, String... sideAndScore) {
        String key = RedisKey.MATCH_SCORE.of(matchId);
        usedKeys.add(key);
        for (int i = 0; i < sideAndScore.length; i += 2) {
            stringRedisTemplate.opsForHash().put(key, sideAndScore[i], sideAndScore[i + 1]);
        }
    }

    private List<Long> matchScoresOf(long matchId) {
        return jdbcTemplate.queryForObject("SELECT a_score, b_score FROM alliance_match WHERE id = ?",
                (rs, n) -> List.of(rs.getLong("a_score"), rs.getLong("b_score")), matchId);
    }

    private long givenAlliance(long totalScore) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO alliance (name, duration_hours, expired_at, total_score)
                VALUES ('배치테스트연합', 24, now() + INTERVAL '24 hours', ?) RETURNING id""", Long.class, totalScore);
    }

    /** 학교 ID와 점수를 번갈아 받는다 */
    private void givenAllianceScore(long allianceId, String... univAndScore) {
        String key = RedisKey.ALLIANCE_SCORE.of(allianceId);
        usedKeys.add(key);
        for (int i = 0; i < univAndScore.length; i += 2) {
            stringRedisTemplate.opsForHash().put(key, univAndScore[i], univAndScore[i + 1]);
        }
    }

    private Long allianceScoreOf(long allianceId) {
        return jdbcTemplate.queryForObject("SELECT total_score FROM alliance WHERE id = ?", Long.class, allianceId);
    }

    private void ensureClickerLevels() {
        jdbcTemplate.update("""
                INSERT INTO clicker (id, click_power, required_score)
                VALUES (2, 20, 300), (3, 40, 1000)
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private Long clickerLevelOf(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT clicker_id FROM users WHERE id = ?", Long.class, userId);
    }

    private Long departmentScoreOf(Long departmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT total_score FROM department WHERE id = ?", Long.class, departmentId);
    }

    /** JPA 1차 캐시를 거치지 않도록 DB 값을 직접 읽는다. */
    private Long contribScoreOf(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT total_contrib_score FROM users WHERE id = ?", Long.class, userId);
    }

    private Long attackScoreOf(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT total_attack_score FROM users WHERE id = ?", Long.class, userId);
    }

    /** 배치 전 DB 값. 배치가 덮어쓰지 않아야 할 값이 0이 아니어야 "지우지 않았다"를 구분할 수 있다. */
    private void givenDbScores(Long userId, long contribScore, long attackScore) {
        jdbcTemplate.update("UPDATE users SET total_contrib_score = ?, total_attack_score = ? WHERE id = ?",
                contribScore, attackScore, userId);
    }

    /** apply-attack.lua처럼 score:user:attack Hash의 회원 필드에 공격 점수를 둔다. */
    private void givenAttackScore(Long userId, long attackScore) {
        String field = String.valueOf(userId);
        addedAttackFields.add(field);
        stringRedisTemplate.opsForHash().put(RedisKey.SCORE_USER_ATTACK.of(), field, String.valueOf(attackScore));
    }

    private Long universityScoreOf(Long universityId) {
        return jdbcTemplate.queryForObject(
                "SELECT total_score FROM university WHERE id = ?", Long.class, universityId);
    }

    private User saveUser(University university, Department department, String nickname, String email) {
        return userRepository.saveAndFlush(User.create(
                university.getId(),
                department.getId(),
                nickname,
                email,
                emailHasher.hash(email),
                "{noop}unused",
                OffsetDateTime.now()));
    }
}
