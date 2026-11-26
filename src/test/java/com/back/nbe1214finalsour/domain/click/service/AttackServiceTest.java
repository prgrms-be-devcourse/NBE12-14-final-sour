package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.abuse.service.AbuseVerificationService;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceAllyChecker;
import com.back.nbe1214finalsour.domain.click.dto.AttackRequest;
import com.back.nbe1214finalsour.domain.click.dto.AttackResponse;
import com.back.nbe1214finalsour.domain.university.service.rival.RivalChecker;
import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import com.back.nbe1214finalsour.domain.clicker.repository.UserClickerLevelCache;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.domain.user.repository.UserStatusCache;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

/**
 * 공격 검증 순서, 피해량 계산, 스크립트 호출과 응답 매핑을 검증한다.
 * Redis·DB는 모두 목이므로 Lua(0 아래로 내려가지 않음 등)는 {@code AttackIntegrationTest}에서 검증한다.
 *
 * <p>요청 피해량 = {@code floor(clickCount * clicker.clickPower * 0.3 * 경쟁 배율)}, 아이템 배율은 곱하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class AttackServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long UNIVERSITY_ID = 12L;
    private static final Long TARGET_UNIVERSITY_ID = 9L;
    private static final String TARGET_NAME = "연세대학교";
    private static final String SUBMISSION_ID = "sub-1";

    private static final Clicker CLICKER_LV3 = new Clicker(3L, 30, 2400L);

    /** apply-attack.lua 반환: damage(실제 깎인 양), univTotal, univRank, myAttackScore */
    private static final List<String> SCRIPT_RESULT = List.of("7", "93", "2", "150");

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ActionRateLimiter actionRateLimiter;
    @Mock
    private ClickCountLimiter clickCountLimiter;
    @Mock
    private ClickerService clickerService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserStatusCache userStatusCache;
    @Mock
    private RivalChecker rivalChecker;
    @Mock
    private UniversityCache universityCache;
    @Mock
    private AllianceAllyChecker allianceAllyChecker;
    @Mock
    private AbuseVerificationService abuseVerificationService;
    @Mock
    private UserClickerLevelCache userClickerLevelCache;
    @Mock
    private ScoreRestoreService scoreRestoreService;

    private AttackService attackService;

    @BeforeEach
    void setUp() {
        ClickActionGuard clickActionGuard = new ClickActionGuard(
                stringRedisTemplate, userStatusCache, userRepository,
                actionRateLimiter, clickCountLimiter, clickerService, abuseVerificationService,
                userClickerLevelCache, scoreRestoreService);
        attackService = new AttackService(clickActionGuard, stringRedisTemplate, rivalChecker, universityCache, allianceAllyChecker);

        lenient().when(scoreRestoreService.isReady()).thenReturn(true);
    }

    private AttackRequest request(Long targetUniversityId, int clickCount) {
        return new AttackRequest(SUBMISSION_ID, clickCount, targetUniversityId);
    }

    /** 대상 학교 있음 → 활성 회원 → 중복 아님 → 레벨 조회(loadIfAbsent)까지의 공통 경로 */
    private void givenHappyPathBeforeScript(Clicker clicker, boolean isRival) {
        given(universityCache.getName(TARGET_UNIVERSITY_ID)).willReturn(TARGET_NAME);
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        given(userClickerLevelCache.loadIfAbsent(USER_ID)).willReturn(clicker.getId());
        given(clickerService.getClickerById(clicker.getId())).willReturn(clicker);
        given(rivalChecker.isRival(UNIVERSITY_ID, TARGET_UNIVERSITY_ID)).willReturn(isRival);
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);
    }

    /** 스크립트에 넘긴 ARGV: targetUniversityId, requestedDamage, userId */
    private List<Object> capturedScriptArgs() {
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.captor();
        verify(stringRedisTemplate).execute(any(), anyList(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture());
        return argsCaptor.getAllValues();
    }

    // --- 0단계: 대상 학교 ---

    @Test
    @DisplayName("내 학교를 공격하면 ATTACK_OWN_UNIVERSITY 예외, 학교 조회와 회원 검증은 하지 않는다")
    void attack_ownUniversity() {
        assertThatThrownBy(() -> attackService.attack(USER_ID, UNIVERSITY_ID, request(UNIVERSITY_ID, 3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ATTACK_OWN_UNIVERSITY);

        verify(universityCache, never()).getName(anyLong());
        verifyNoInteractions(allianceAllyChecker);
        verify(userStatusCache, never()).find(anyLong());
        verify(stringRedisTemplate, never()).execute(any(), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("없는 학교를 공격하면 UNIV_NOT_FOUND 예외, 중복 제출 키와 요청 한도는 쓰지 않는다")
    void attack_targetUniversityNotFound() {
        willThrow(new BusinessException(ErrorCode.UNIV_NOT_FOUND))
                .given(universityCache).getName(TARGET_UNIVERSITY_ID);

        assertThatThrownBy(() -> attackService.attack(USER_ID, UNIVERSITY_ID, request(TARGET_UNIVERSITY_ID, 3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNIV_NOT_FOUND);

        // 대상 학교 확인이 소속·회원 검증보다 먼저다
        verifyNoInteractions(allianceAllyChecker);
        verify(userStatusCache, never()).find(anyLong());
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(actionRateLimiter, never()).check(anyLong());
        verify(stringRedisTemplate, never()).execute(any(), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("동맹 공격은 ATTACK_ALLY_UNIVERSITY, guard와 점수 Redis를 호출하지 않는다")
    void attack_allyUniversity() {
        given(universityCache.getName(TARGET_UNIVERSITY_ID)).willReturn(TARGET_NAME);
        given(allianceAllyChecker.areAllies(UNIVERSITY_ID, TARGET_UNIVERSITY_ID)).willReturn(true);

        assertThatThrownBy(() -> attackService.attack(USER_ID, UNIVERSITY_ID, request(TARGET_UNIVERSITY_ID, 3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ATTACK_ALLY_UNIVERSITY);

        verifyNoInteractions(stringRedisTemplate, userStatusCache, userRepository, actionRateLimiter,
                clickCountLimiter, abuseVerificationService, userClickerLevelCache, clickerService, rivalChecker);
    }

    // --- 1단계: 클릭과 같은 검증 (ClickActionGuard) ---

    @Test
    @DisplayName("중복 제출이면 CLICK_DUPLICATE_SUBMISSION 예외, 점수 반영은 하지 않는다")
    void attack_duplicateSubmission() {
        given(universityCache.getName(TARGET_UNIVERSITY_ID)).willReturn(TARGET_NAME);
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(valueOperations.setIfAbsent("submit:1:sub-1", SUBMISSION_ID, Duration.ofSeconds(10L)))
                .willReturn(false);

        assertThatThrownBy(() -> attackService.attack(USER_ID, UNIVERSITY_ID, request(TARGET_UNIVERSITY_ID, 3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLICK_DUPLICATE_SUBMISSION);

        verify(stringRedisTemplate, never()).execute(any(), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("요청 한도 초과 예외는 그대로 전파되고 점수 반영은 하지 않는다")
    void attack_rateLimitExceeded() {
        given(universityCache.getName(TARGET_UNIVERSITY_ID)).willReturn(TARGET_NAME);
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        willThrow(new BusinessException(ErrorCode.CLICK_TOO_FREQUENT))
                .given(actionRateLimiter).check(USER_ID);

        assertThatThrownBy(() -> attackService.attack(USER_ID, UNIVERSITY_ID, request(TARGET_UNIVERSITY_ID, 3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLICK_TOO_FREQUENT);

        verify(stringRedisTemplate, never()).execute(any(), anyList(), any(), any(), any());
    }

    // --- 2단계: 피해량 계산 ---

    @Test
    @DisplayName("경쟁 학교가 아니면 clickCount * clickPower * 0.3을 요청 피해량으로 넘긴다")
    void attack_damageWithoutRival() {
        givenHappyPathBeforeScript(CLICKER_LV3, false);

        attackService.attack(USER_ID, UNIVERSITY_ID, request(TARGET_UNIVERSITY_ID, 4));

        // 4 * 30 * 0.3 = 36
        assertThat(capturedScriptArgs().get(1)).isEqualTo("36");
    }

    @Test
    @DisplayName("경쟁 학교면 1.1배를 더 곱하고 소수점은 버린다 (39.6 → 39)")
    void attack_damageWithRival() {
        givenHappyPathBeforeScript(CLICKER_LV3, true);

        attackService.attack(USER_ID, UNIVERSITY_ID, request(TARGET_UNIVERSITY_ID, 4));

        // 4 * 30 * 0.3 * 1.1 = 39.6 → 39
        assertThat(capturedScriptArgs().get(1)).isEqualTo("39");
    }

    @Test
    @DisplayName("소수점이 생기면 버린다 (반올림이 아니다)")
    void attack_damageIsFloored() {
        givenHappyPathBeforeScript(new Clicker(2L, 20, 600L), true);

        attackService.attack(USER_ID, UNIVERSITY_ID, request(TARGET_UNIVERSITY_ID, 3));

        // 3 * 20 * 0.3 * 1.1 = 19.8 → 19 (반올림이면 20). 클릭 세기가 10의 배수라 경쟁 배율이 없으면 소수점이 생기지 않는다
        assertThat(capturedScriptArgs().get(1)).isEqualTo("19");
    }

    // --- 3단계: 스크립트 호출과 응답 매핑 ---

    @Test
    @DisplayName("apply-attack.lua의 KEYS 순서와 ARGV 값을 그대로 넘긴다")
    void attack_passesKeysAndArgsInLuaOrder() {
        givenHappyPathBeforeScript(CLICKER_LV3, false);

        attackService.attack(USER_ID, UNIVERSITY_ID, request(TARGET_UNIVERSITY_ID, 4));

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.captor();
        verify(stringRedisTemplate).execute(any(), keysCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture());

        assertThat(keysCaptor.getValue()).containsExactly(
                "rank:univ",            // KEYS[1]
                "dirty:univ",           // KEYS[2]
                "score:user:attack",     // KEYS[3]
                "dirty:user"            // KEYS[4]
        );
        // ARGV: targetUniversityId, requestedDamage, userId
        assertThat(argsCaptor.getAllValues()).containsExactly("9", "36", "1");
    }

    @Test
    @DisplayName("응답의 damage와 myAttackScore는 요청 피해량이 아니라 스크립트가 돌려준 값을 쓴다")
    void attack_mapsScriptResultToResponse() {
        givenHappyPathBeforeScript(CLICKER_LV3, false);

        // 요청 피해량은 36이지만 스크립트는 남은 점수만큼인 7만 깎았다고 돌려준다
        AttackResponse response = attackService.attack(USER_ID, UNIVERSITY_ID, request(TARGET_UNIVERSITY_ID, 4));

        assertThat(response)
                .extracting("acceptedClicks", "damage", "multiplier", "isRival", "myAttackScore")
                .containsExactly(4L, 7L, 1.0, false, 150L);

        assertThat(response)
                .extracting("targetUniversity.id", "targetUniversity.name",
                        "targetUniversity.totalScore", "targetUniversity.rank")
                .containsExactly(TARGET_UNIVERSITY_ID, TARGET_NAME, 93L, 2L);
    }

    @Test
    @DisplayName("경쟁 학교면 응답에 isRival=true, multiplier=1.1을 담는다")
    void attack_rivalIsReflectedInResponse() {
        givenHappyPathBeforeScript(CLICKER_LV3, true);

        AttackResponse response = attackService.attack(USER_ID, UNIVERSITY_ID, request(TARGET_UNIVERSITY_ID, 4));

        assertThat(response)
                .extracting("isRival", "multiplier")
                .containsExactly(true, 1.1);
    }
}
