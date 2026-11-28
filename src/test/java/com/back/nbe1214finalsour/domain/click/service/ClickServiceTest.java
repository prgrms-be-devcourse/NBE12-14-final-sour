package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.abuse.service.AbuseVerificationService;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceStandingResolver;
import com.back.nbe1214finalsour.domain.click.dto.ClickRequest;
import com.back.nbe1214finalsour.domain.click.dto.ClickResponse;
import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import com.back.nbe1214finalsour.domain.clicker.repository.UserClickerLevelCache;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.domain.university.service.library.LibraryBonusChecker;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.domain.user.repository.UserStatusCache;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

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
 * 점수 계산과 호출 순서를 검증한다. Redis·DB는 모두 목이므로 Lua 자체는 여기서 검증하지 않는다.
 *
 * <p>인정 점수 = {@code floor(clickCount * clicker.clickPower * 아이템 배율 * 도서관 배율)}. 버림은 모두 곱한 뒤 한 번만 한다.
 * 도서관 배율은 반경 안이면 1.2, 아니면 1.0이고, 응답의 {@code multiplier}는 두 배율을 곱한 값이다.
 */
@ExtendWith(MockitoExtension.class)
class ClickServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long UNIVERSITY_ID = 12L;
    private static final Long DEPARTMENT_ID = 5L;
    private static final String SUBMISSION_ID = "sub-1";

    private static final Clicker CLICKER_LV3 = new Clicker(3L, 30, 2400L);
    private static final Clicker CLICKER_LV1 = new Clicker(1L, 10, 0L);

    /**
     * 클릭한 위치. 반경 판정 자체는 {@code LibraryBonusCheckerTest}에서 보고, 여기서는 판정 결과만 목으로 정한다
     */
    private static final Double LAT = 37.563764;
    private static final Double LNG = 126.937012;

    /**
     * apply-click.lua 반환: univTotal, deptTotal, myContrib, univRank, 직전 1초 학교 클릭·공격·지원 수
     */
    private static final List<String> SCRIPT_RESULT = List.of("100", "50", "30", "4", "7", "2", "6");

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private RedisHandler redisHandler;
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
    private AbuseVerificationService abuseVerificationService;
    @Mock
    private UserClickerLevelCache userClickerLevelCache;
    @Mock
    private LibraryBonusChecker libraryBonusChecker;
    @Mock
    private User user;
    @Mock
    private AllianceStandingResolver allianceStandingResolver;
    @Mock
    private UniversityCache universityCache;
    @Mock
    private ScoreRestoreService scoreRestoreService;
    @Mock
    private ObjectMapper objectMapper;

    private ClickService clickService;

    @BeforeEach
    void setUp() {
        ClickActionGuard clickActionGuard = new ClickActionGuard(
                stringRedisTemplate, userStatusCache, userRepository,
                actionRateLimiter, clickCountLimiter, clickerService, abuseVerificationService,
                userClickerLevelCache, scoreRestoreService, objectMapper);
        clickService = new ClickService(stringRedisTemplate, userRepository, redisHandler, clickActionGuard,
                libraryBonusChecker, allianceStandingResolver, universityCache);
        ReflectionTestUtils.setField(clickActionGuard, "minDamageRate", 100);
        ReflectionTestUtils.setField(clickActionGuard, "maxDamageRate", 100);
        lenient().when(allianceStandingResolver.resolve(UNIVERSITY_ID))
                .thenReturn(AllianceStandingResolver.AllianceStanding.NONE);
        lenient().when(scoreRestoreService.isReady()).thenReturn(true);
    }

    private ClickRequest request(int clickCount) {
        return new ClickRequest(SUBMISSION_ID, clickCount, null, null);
    }

    private ClickRequest requestAt(int clickCount, Double lat, Double lng) {
        return new ClickRequest(SUBMISSION_ID, clickCount, lat, lng);
    }

    /**
     * 활성 회원 → 중복 아님 → 레벨 조회(loadIfAbsent, 3레벨) → 유저 조회 성공까지의 공통 경로
     */
    private void givenHappyPathBeforeScript() {
        givenHappyPathBeforeScript(CLICKER_LV3);
    }

    private void givenHappyPathBeforeScript(Clicker clicker) {
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        given(userClickerLevelCache.loadIfAbsent(USER_ID)).willReturn(clicker.getId());
        given(clickerService.getClickerById(clicker.getId())).willReturn(clicker);
    }

    private void givenItemMultiplier(Double multiplier) {
        given(redisHandler.getHash("user:1:effect", "multiplier", Double.class)).willReturn(Optional.ofNullable(multiplier));
    }

    private void givenScriptResult() {
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);
    }

    // --- 0단계: 회원 상태 ---

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "BANNED", "WITHDRAWN"})
    @DisplayName("회원 상태가 ACTIVE가 아니면 USER_SUSPENDED 예외, 이후 단계는 진행하지 않는다")
    void click_inactiveUser(String status) {
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.valueOf(status)));

        assertThatThrownBy(() -> clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_SUSPENDED);

        // 상태 검사가 중복 검사보다 먼저다
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(actionRateLimiter, never()).check(anyLong());
        verify(clickCountLimiter, never()).check(anyLong(), anyInt());
        verify(stringRedisTemplate, never()).execute(any(), anyList(), any());
    }

    @Test
    @DisplayName("상태 캐시가 없으면 DB 상태로 채우고, 채우기가 돌려준 값(먼저 들어온 탈퇴 등)으로 판단한다")
    void click_statusCacheMissUsesValueInCache() {
        given(userStatusCache.find(USER_ID)).willReturn(Optional.empty());
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        given(userStatusCache.fillIfAbsent(USER_ID, UserStatus.ACTIVE)).willReturn(UserStatus.WITHDRAWN);

        assertThatThrownBy(() -> clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_SUSPENDED);

        verify(stringRedisTemplate, never()).execute(any(), anyList(), any());
    }

    @Test
    @DisplayName("상태 캐시가 없고 회원도 없으면 USER_NOT_FOUND, 캐시는 채우지 않는다")
    void click_statusCacheMissAndUserNotFound() {
        given(userStatusCache.find(USER_ID)).willReturn(Optional.empty());
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(userStatusCache, never()).fillIfAbsent(anyLong(), any());
    }

    // --- 0.5단계: 어뷰징 의심 표시 (SCRUM-118) ---

    @Test
    @DisplayName("의심 표시가 있으면 ABUSE_VERIFICATION_REQUIRED, 중복 제출 키를 쓰지 않고 한도도 세지 않는다")
    void click_flaggedUserBlockedBeforeDedupe() {
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        willThrow(new BusinessException(ErrorCode.ABUSE_VERIFICATION_REQUIRED))
                .given(abuseVerificationService).requireNotFlagged(USER_ID);

        assertThatThrownBy(() -> clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ABUSE_VERIFICATION_REQUIRED);

        // 막힌 요청이 submissionId를 써 버리지 않도록 중복 검사보다 먼저다
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(actionRateLimiter, never()).check(anyLong());
        verify(clickCountLimiter, never()).check(anyLong(), anyInt());
    }

    @Test
    @DisplayName("요청 한도를 넘으면 의심 표시를 남기고 CLICK_TOO_FREQUENT를 그대로 던진다")
    void click_actionLimitFlags() {
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        willThrow(new BusinessException(ErrorCode.CLICK_TOO_FREQUENT)).given(actionRateLimiter).check(USER_ID);

        assertThatThrownBy(() -> clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3)))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLICK_TOO_FREQUENT);

        verify(abuseVerificationService).flag(USER_ID);
    }

    @Test
    @DisplayName("클릭 수 한도를 넘으면 의심 표시를 남기고 CLICK_COUNT_EXCEEDED를 그대로 던진다")
    void click_clickCountLimitFlags() {
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        willThrow(new BusinessException(ErrorCode.CLICK_COUNT_EXCEEDED)).given(clickCountLimiter).check(USER_ID, 3);

        assertThatThrownBy(() -> clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3)))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLICK_COUNT_EXCEEDED);

        verify(abuseVerificationService).flag(USER_ID);
    }

    @Test
    @DisplayName("한도 안이면 의심 표시를 남기지 않는다")
    void click_withinLimitsDoesNotFlag() {
        givenHappyPathBeforeScript();
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        verify(abuseVerificationService).requireNotFlagged(USER_ID);
        verify(abuseVerificationService, never()).flag(anyLong());
    }

    // --- 1단계: 중복 제출 ---

    @Test
    @DisplayName("중복 제출이면 CLICK_DUPLICATE_SUBMISSION 예외, 한도 검사와 점수 반영은 하지 않는다")
    void click_duplicateSubmission() {
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(valueOperations.setIfAbsent("submit:1:sub-1", SUBMISSION_ID, Duration.ofSeconds(10L)))
                .willReturn(false);

        assertThatThrownBy(() -> clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLICK_DUPLICATE_SUBMISSION);

        // 중복 검사가 한도 검사보다 먼저다
        verify(actionRateLimiter, never()).check(anyLong());
        verify(clickCountLimiter, never()).check(anyLong(), anyInt());
        verify(userRepository, never()).findById(anyLong());
        verify(stringRedisTemplate, never()).execute(any(), anyList(), any());
    }

    @Test
    @DisplayName("setIfAbsent가 null이면 중복으로 보지 않고 진행한다")
    void click_proceedsWhenSetIfAbsentReturnsNull() {
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(null);
        given(userClickerLevelCache.loadIfAbsent(USER_ID)).willReturn(3L);
        given(clickerService.getClickerById(3L)).willReturn(CLICKER_LV3);
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        verify(actionRateLimiter).check(USER_ID);
    }

    // --- 2단계: 요청 한도 ---

    @Test
    @DisplayName("한도 초과 예외는 그대로 전파되고 점수 반영은 하지 않는다")
    void click_rateLimitExceeded() {
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        willThrow(new BusinessException(ErrorCode.CLICK_TOO_FREQUENT))
                .given(actionRateLimiter).check(USER_ID);

        assertThatThrownBy(() -> clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLICK_TOO_FREQUENT);

        // 요청 한도에 걸린 요청은 클릭 수 합계에 더하지 않는다
        verify(clickCountLimiter, never()).check(anyLong(), anyInt());
        verify(stringRedisTemplate, never()).execute(any(), anyList(), any());
    }

    @Test
    @DisplayName("요청 한도를 통과하면 clickCount를 그대로 클릭 수 한도에 넘긴다")
    void click_passesClickCountToClickCountLimiter() {
        givenHappyPathBeforeScript();
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(7));

        verify(clickCountLimiter).check(USER_ID, 7);
    }

    @Test
    @DisplayName("클릭 수 한도 초과 예외는 그대로 전파되고 점수 반영은 하지 않는다")
    void click_clickCountExceeded() {
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        willThrow(new BusinessException(ErrorCode.CLICK_COUNT_EXCEEDED))
                .given(clickCountLimiter).check(USER_ID, 3);

        assertThatThrownBy(() -> clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLICK_COUNT_EXCEEDED);

        verify(actionRateLimiter).check(USER_ID);
        verify(userRepository, never()).findById(anyLong());
        verify(stringRedisTemplate, never()).execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any());
    }

    // --- 3단계: 클리커 레벨 캐시 ---

    @Test
    @DisplayName("레벨은 loadIfAbsent 값을 쓰고 레벨 키를 직접 쓰지 않는다")
    void click_cacheHitDoesNotWriteCache() {
        givenHappyPathBeforeScript();
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        verify(valueOperations, never()).set(eq("user:1:clicker"), anyString());
        verify(clickerService).getClickerById(3L);
    }

    @Test
    @DisplayName("토큰의 학과 ID가 있으면 회원을 DB에서 조회하지 않는다")
    void click_cacheHitWithDepartmentClaimSkipsUserLookup() {
        givenHappyPathBeforeScript();
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("토큰에 학과 ID가 없으면(배포 전 발급) DB의 학과 ID를 쓰고, 레벨 캐시는 다시 쓰지 않는다")
    void click_tokenWithoutDepartmentUsesDepartmentFromDb() {
        givenHappyPathBeforeScript();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(user.getDepartmentId()).willReturn(9L);
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, null, request(3));

        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.captor();
        verify(stringRedisTemplate).execute(any(), anyList(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture());
        assertThat(argsCaptor.getAllValues().get(1)).isEqualTo("9");
        assertThat(response).extracting("department.id").isEqualTo(9L);
        verify(valueOperations, never()).set(eq("user:1:clicker"), anyString());
    }

    // --- 4단계: 점수 계산 ---

    @Test
    @DisplayName("아이템 효과가 없으면 배율 1.0으로 clickCount * clickPower만 적용한다")
    void click_scoreWithoutMultiplier() {
        givenHappyPathBeforeScript();
        given(redisHandler.getHash("user:1:effect", "multiplier", Double.class)).willReturn(Optional.empty());
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        assertThat(response.multiplier()).isEqualTo(1.0);
        assertThat(response.earnedScore()).isEqualTo(90L); // 3 * 30 * 1.0
    }

    @Test
    @DisplayName("아이템 배율을 곱한다")
    void click_scoreWithMultiplier() {
        givenHappyPathBeforeScript();
        given(redisHandler.getHash("user:1:effect", "multiplier", Double.class)).willReturn(Optional.of(1.5));
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        assertThat(response.multiplier()).isEqualTo(1.5);
        assertThat(response.earnedScore()).isEqualTo(135L); // 3 * 30 * 1.5
    }

    @Test
    @DisplayName("소수점이 생기면 버린다 (반올림이 아니다)")
    void click_scoreIsFloored() {
        givenHappyPathBeforeScript();
        given(redisHandler.getHash("user:1:effect", "multiplier", Double.class)).willReturn(Optional.of(1.09));
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        // 3 * 30 * 1.09 = 98.1 → 98
        assertThat(response.earnedScore()).isEqualTo(98L);
    }

    // --- 4.5단계: 도서관 보너스 ---

    @Test
    @DisplayName("도서관 반경 안이면 1.2배를 곱하고 libraryBonus가 true, multiplier는 1.2다")
    void click_libraryBonusMultipliesScore() {
        givenHappyPathBeforeScript();
        givenItemMultiplier(null);
        given(libraryBonusChecker.isInLibrary(UNIVERSITY_ID, LAT, LNG)).willReturn(true);
        givenScriptResult();

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, requestAt(3, LAT, LNG));

        assertThat(response.libraryBonus()).isTrue();
        assertThat(response.multiplier()).isEqualTo(1.2);
        assertThat(response.earnedScore()).isEqualTo(108L); // 3 * 30 * 1.2
    }

    @Test
    @DisplayName("도서관 반경 밖이면 보너스 없이 libraryBonus가 false다")
    void click_outsideLibraryHasNoBonus() {
        givenHappyPathBeforeScript();
        givenItemMultiplier(null);
        given(libraryBonusChecker.isInLibrary(UNIVERSITY_ID, LAT, LNG)).willReturn(false);
        givenScriptResult();

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, requestAt(3, LAT, LNG));

        assertThat(response.libraryBonus()).isFalse();
        assertThat(response.multiplier()).isEqualTo(1.0);
        assertThat(response.earnedScore()).isEqualTo(90L);
    }

    @Test
    @DisplayName("판정에는 토큰의 학교 ID와 요청의 위치를 그대로 넘긴다")
    void click_passesUniversityAndLocationToLibraryCheck() {
        givenHappyPathBeforeScript();
        givenItemMultiplier(null);
        givenScriptResult();

        clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, requestAt(3, LAT, LNG));

        verify(libraryBonusChecker).isInLibrary(UNIVERSITY_ID, LAT, LNG);
    }

    @Test
    @DisplayName("아이템 1.5배와 겹치면 multiplier는 1.8이고 점수는 162다 (부동소수점 1.7999...로 161이 되면 안 된다)")
    void click_libraryBonusStacksWithItemMultiplier() {
        givenHappyPathBeforeScript();
        givenItemMultiplier(1.5);
        given(libraryBonusChecker.isInLibrary(UNIVERSITY_ID, LAT, LNG)).willReturn(true);
        givenScriptResult();

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, requestAt(3, LAT, LNG));

        assertThat(response.multiplier()).isEqualTo(1.8);
        assertThat(response.earnedScore()).isEqualTo(162L); // 3 * 30 * 1.5 * 1.2 = 162
    }

    @Test
    @DisplayName("배율을 모두 곱한 뒤 한 번만 버린다 (단계마다 버리지 않는다)")
    void click_flooredOnceAfterAllMultipliers() {
        givenHappyPathBeforeScript(CLICKER_LV1);
        givenItemMultiplier(1.15);
        given(libraryBonusChecker.isInLibrary(UNIVERSITY_ID, LAT, LNG)).willReturn(true);
        givenScriptResult();

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, requestAt(3, LAT, LNG));

        // 3 * 10 * 1.15 * 1.2 = 41.4 → 41. 아이템 배율에서 먼저 버리면 floor(34.5)=34 → floor(34 * 1.2) = 40
        assertThat(response.earnedScore()).isEqualTo(41L);
    }

    @Test
    @DisplayName("Redis에 반영하는 점수도 도서관 보너스가 붙은 값이다")
    void click_passesBonusScoreToScript() {
        givenHappyPathBeforeScript();
        givenItemMultiplier(null);
        given(libraryBonusChecker.isInLibrary(UNIVERSITY_ID, LAT, LNG)).willReturn(true);
        givenScriptResult();

        clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, requestAt(3, LAT, LNG));

        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.captor();
        verify(stringRedisTemplate).execute(any(), anyList(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture());

        // ARGV: universityId, departmentId, userId, earnedScore, clickCount, allianceId(없음 0), team(없음 ""), matchId(없음 0)
        assertThat(argsCaptor.getAllValues()).containsExactly("12", "5", "1", "108", "3", "0", "", "0");
    }

    // --- 4.7단계: 연합·대결 점수 ---
    // 연합·대결 조회와 캐시는 AllianceStandingResolverTest에서 본다. 여기서는 내 학교로 조회하고 결과를 스크립트에 넘기는지만 본다.

    /**
     * 스크립트에 넘긴 KEYS[9], KEYS[10]과 ARGV[6], ARGV[7]
     */
    private List<String> allianceKeysAndArgs() {
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.captor();
        verify(stringRedisTemplate).execute(any(), keysCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture());
        List<String> keys = keysCaptor.getValue();
        List<Object> args = argsCaptor.getAllValues();
        return List.of(keys.get(8), keys.get(9), (String) args.get(5), (String) args.get(6));
    }

    @Test
    @DisplayName("내 학교 ID로 연합·대결을 찾고, 결과를 KEYS[9·10]과 ARGV[6·7]로 넘긴다")
    void click_passesAllianceStandingToScript() {
        givenHappyPathBeforeScript();
        givenItemMultiplier(null);
        givenScriptResult();
        given(allianceStandingResolver.resolve(UNIVERSITY_ID))
                .willReturn(new AllianceStandingResolver.AllianceStanding(7L, 42L, "B"));

        clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        verify(allianceStandingResolver).resolve(UNIVERSITY_ID);
        assertThat(allianceKeysAndArgs()).containsExactly("alliance:7:score", "match:42:score", "7", "B");
    }

    @Test
    @DisplayName("연합이 없으면(NONE) 0 키와 \"0\", \"\"를 넘겨 스크립트가 연합·대결 점수를 건너뛰게 한다")
    void click_noAlliancePassesZeroKeys() {
        givenHappyPathBeforeScript();
        givenItemMultiplier(null);
        givenScriptResult();

        clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        assertThat(allianceKeysAndArgs()).containsExactly("alliance:0:score", "match:0:score", "0", "");
    }

    // --- 5단계: 스크립트 호출과 응답 매핑 ---

    @Test
    @DisplayName("apply-click.lua의 KEYS 순서와 ARGV 값을 그대로 넘긴다")
    void click_passesKeysAndArgsInLuaOrder() {
        givenHappyPathBeforeScript();
        given(redisHandler.getHash("user:1:effect", "multiplier", Double.class)).willReturn(Optional.of(1.5));
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.captor();
        verify(stringRedisTemplate).execute(any(), keysCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture());

        List<String> keys = keysCaptor.getValue();
        assertThat(keys).hasSize(14);
        assertThat(keys.subList(0, 6)).containsExactly(
                "rank:univ",            // KEYS[1]
                "rank:univ:12:dept",    // KEYS[2]
                "rank:univ:12:user",    // KEYS[3]
                "dirty:univ",           // KEYS[4]
                "dirty:dept",           // KEYS[5]
                "dirty:user"            // KEYS[6]
        );
        // KEYS[7]은 이번 초, KEYS[8]은 직전 1초 (현재 시각이라 값은 고정할 수 없다)
        assertThat(keys.get(6)).matches("clicks:univ:12:\\d+");
        assertThat(keys.get(7)).matches("clicks:univ:12:\\d+");
        // KEYS[13]은 공격 수를 읽는 직전 1초 (클릭 수 KEYS[8]과 같은 초)
        assertThat(keys.get(12)).matches("attacks:univ:12:\\d+");
        assertThat(keys.get(12).split(":")[3]).isEqualTo(keys.get(7).split(":")[3]);
        // KEYS[14]는 지원 수를 읽는 직전 1초 (클릭 수 KEYS[8]과 같은 초)
        assertThat(keys.get(13)).matches("assists:univ:12:\\d+");
        assertThat(keys.get(13).split(":")[3]).isEqualTo(keys.get(7).split(":")[3]);
        assertThat(Long.parseLong(keys.get(6).split(":")[3]) - Long.parseLong(keys.get(7).split(":")[3]))
                .isEqualTo(1L);
        // KEYS[9], KEYS[10]: 연합·대결이 없으면 0번 자리 키를 넘기고 Lua가 ARGV로 건너뛴다
        assertThat(keys.subList(8, 10)).containsExactly("alliance:0:score", "match:0:score");

        // ARGV: universityId, departmentId, userId, earnedScore, clickCount, allianceId, team, matchId
        assertThat(argsCaptor.getAllValues()).containsExactly("12", "5", "1", "135", "3", "0", "", "0");
    }

    @Test
    @DisplayName("스크립트 반환값을 응답 필드에 순서대로 매핑한다")
    void click_mapsScriptResultToResponse() {
        givenHappyPathBeforeScript();
        given(redisHandler.getHash("user:1:effect", "multiplier", Double.class)).willReturn(Optional.of(1.5));
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        assertThat(response)
                .extracting("acceptedClicks", "earnedScore", "multiplier",
                        "myContribScore", "schoolClicksLastSecond", "schoolAttacksLastSecond", "schoolAssistsLastSecond")
                .containsExactly(3L, 135L, 1.5, 30L, 7L, 2L, 6L);

        assertThat(response)
                .extracting("university.id", "university.totalScore", "university.rank", "university.logoUrl")
                .containsExactly(UNIVERSITY_ID, 100L, 4L, "tempUrl");

        assertThat(response)
                .extracting("department.id", "department.totalScore")
                .containsExactly(DEPARTMENT_ID, 50L);
    }
}
