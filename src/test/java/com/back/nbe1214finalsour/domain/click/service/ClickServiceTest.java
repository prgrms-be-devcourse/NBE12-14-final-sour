package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.click.dto.ClickRequest;
import com.back.nbe1214finalsour.domain.click.dto.ClickResponse;
import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.domain.user.repository.UserStatusCache;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 점수 계산과 호출 순서를 검증한다. Redis·DB는 모두 목이므로 Lua 자체는 여기서 검증하지 않는다.
 *
 * <p>인정 점수 = {@code floor(clickCount * clicker.clickPower * multiplier)}
 */
@ExtendWith(MockitoExtension.class)
class ClickServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long UNIVERSITY_ID = 12L;
    private static final Long DEPARTMENT_ID = 5L;
    private static final String SUBMISSION_ID = "sub-1";

    private static final Clicker CLICKER_LV3 = new Clicker(3L, 10, 1000L);

    /** apply-click.lua 반환: univTotal, deptTotal, myContrib, univRank, lastSecond */
    private static final List<String> SCRIPT_RESULT = List.of("100", "50", "30", "4", "7");

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
    private User user;

    @InjectMocks
    private ClickService clickService;

    private ClickRequest request(int clickCount) {
        return new ClickRequest(SUBMISSION_ID, clickCount);
    }

    /** 활성 회원 → 중복 아님 → 레벨 캐시 히트(3레벨) → 유저 조회 성공까지의 공통 경로 */
    private void givenHappyPathBeforeScript() {
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        given(valueOperations.get("user:1:clicker")).willReturn("3");
        given(clickerService.getClickerById(3L)).willReturn(CLICKER_LV3);
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
        given(valueOperations.get("user:1:clicker")).willReturn("3");
        given(clickerService.getClickerById(3L)).willReturn(CLICKER_LV3);
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any()))
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
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any()))
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
        verify(stringRedisTemplate, never()).execute(any(), anyList(), any(), any(), any(), any(), any());
    }

    // --- 3단계: 클리커 레벨 캐시 ---

    @Test
    @DisplayName("레벨 캐시가 없으면 DB의 clickerId로 캐시를 채우고 그 레벨을 쓴다")
    void click_cacheMissWritesClickerLevelCache() {
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        given(valueOperations.get("user:1:clicker")).willReturn(null);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(user.getClickerId()).willReturn(2L);
        given(clickerService.getClickerById(2L)).willReturn(new Clicker(2L, 20, 300L));
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        verify(valueOperations).set("user:1:clicker", "2");
        verify(clickerService).getClickerById(2L);
        assertThat(response.earnedScore()).isEqualTo(60L); // 3 * 20
    }

    @Test
    @DisplayName("레벨 캐시가 있으면 캐시 값을 쓰고 캐시를 다시 쓰지 않는다")
    void click_cacheHitDoesNotWriteCache() {
        givenHappyPathBeforeScript();
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        verify(valueOperations, never()).set(eq("user:1:clicker"), anyString());
        verify(clickerService).getClickerById(3L);
    }

    @Test
    @DisplayName("레벨 캐시와 토큰의 학과 ID가 있으면 회원을 DB에서 조회하지 않는다")
    void click_cacheHitWithDepartmentClaimSkipsUserLookup() {
        givenHappyPathBeforeScript();
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any()))
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
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, null, request(3));

        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.captor();
        verify(stringRedisTemplate).execute(any(), anyList(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture());
        assertThat(argsCaptor.getAllValues().get(1)).isEqualTo("9");
        assertThat(response).extracting("department.id").isEqualTo(9L);
        verify(valueOperations, never()).set(eq("user:1:clicker"), anyString());
    }

    @Test
    @DisplayName("회원을 조회해야 하는데(레벨 캐시 없음) 회원이 없으면 USER_NOT_FOUND 예외")
    void click_userNotFound() {
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        given(valueOperations.get("user:1:clicker")).willReturn(null);
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(stringRedisTemplate, never()).execute(any(), anyList(), any());
    }

    // --- 4단계: 점수 계산 ---

    @Test
    @DisplayName("아이템 효과가 없으면 배율 1.0으로 clickCount * clickPower만 적용한다")
    void click_scoreWithoutMultiplier() {
        givenHappyPathBeforeScript();
        given(redisHandler.getHash("user:1:effect", "multiplier", Double.class)).willReturn(Optional.empty());
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        assertThat(response.multiplier()).isEqualTo(1.0);
        assertThat(response.earnedScore()).isEqualTo(30L); // 3 * 10 * 1.0
    }

    @Test
    @DisplayName("아이템 배율을 곱한다")
    void click_scoreWithMultiplier() {
        givenHappyPathBeforeScript();
        given(redisHandler.getHash("user:1:effect", "multiplier", Double.class)).willReturn(Optional.of(1.5));
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        assertThat(response.multiplier()).isEqualTo(1.5);
        assertThat(response.earnedScore()).isEqualTo(45L); // floor(3 * 10 * 1.5)
    }

    @Test
    @DisplayName("소수점이 생기면 버린다 (반올림이 아니다)")
    void click_scoreIsFloored() {
        givenHappyPathBeforeScript();
        given(redisHandler.getHash("user:1:effect", "multiplier", Double.class)).willReturn(Optional.of(1.09));
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        // 3 * 10 * 1.09 = 32.7 → 32
        assertThat(response.earnedScore()).isEqualTo(32L);
    }

    // --- 5단계: 스크립트 호출과 응답 매핑 ---

    @Test
    @DisplayName("apply-click.lua의 KEYS 순서와 ARGV 값을 그대로 넘긴다")
    void click_passesKeysAndArgsInLuaOrder() {
        givenHappyPathBeforeScript();
        given(redisHandler.getHash("user:1:effect", "multiplier", Double.class)).willReturn(Optional.of(1.5));
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.captor();
        verify(stringRedisTemplate).execute(any(), keysCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture());

        List<String> keys = keysCaptor.getValue();
        assertThat(keys).hasSize(8);
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
        assertThat(Long.parseLong(keys.get(6).split(":")[3]) - Long.parseLong(keys.get(7).split(":")[3]))
                .isEqualTo(1L);

        // ARGV: universityId, departmentId, userId, earnedScore, clickCount
        assertThat(argsCaptor.getAllValues()).containsExactly("12", "5", "1", "45", "3");
    }

    @Test
    @DisplayName("스크립트 반환값을 응답 필드에 순서대로 매핑한다")
    void click_mapsScriptResultToResponse() {
        givenHappyPathBeforeScript();
        given(redisHandler.getHash("user:1:effect", "multiplier", Double.class)).willReturn(Optional.of(1.5));
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any()))
                .willReturn(SCRIPT_RESULT);

        ClickResponse response = clickService.click(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, request(3));

        assertThat(response)
                .extracting("acceptedClicks", "earnedScore", "multiplier",
                        "myContribScore", "schoolClicksLastSecond")
                .containsExactly(3L, 45L, 1.5, 30L, 7L);

        assertThat(response)
                .extracting("university.id", "university.totalScore", "university.rank", "university.logoUrl")
                .containsExactly(UNIVERSITY_ID, 100L, 4L, "tempUrl");

        assertThat(response)
                .extracting("department.id", "department.totalScore")
                .containsExactly(DEPARTMENT_ID, 50L);
    }
}
