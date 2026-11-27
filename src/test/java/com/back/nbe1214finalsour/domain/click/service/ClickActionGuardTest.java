package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.abuse.service.AbuseVerificationService;
import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import com.back.nbe1214finalsour.domain.clicker.repository.UserClickerLevelCache;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.domain.user.repository.UserStatusCache;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ClickActionGuardTest {

    private static final Long USER_ID = 1L;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UserStatusCache userStatusCache;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ActionRateLimiter actionRateLimiter;

    @Mock
    private ClickCountLimiter clickCountLimiter;

    @Mock
    private ClickerService clickerService;

    @Mock
    private AbuseVerificationService abuseVerificationService;

    @Mock
    private UserClickerLevelCache userClickerLevelCache;

    @Mock
    private ScoreRestoreService scoreRestoreService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ClickActionGuard clickActionGuard;

    @BeforeEach
    void setUpRates() {
        ReflectionTestUtils.setField(clickActionGuard, "minDamageRate", 100);
        ReflectionTestUtils.setField(clickActionGuard, "maxDamageRate", 100);
    }

    @Test
    @DisplayName("클릭 점수를 계산하면 loadIfAbsent가 반환한 레벨의 clickPower에 클릭 수를 곱한다")
    void verifyAndGetBaseScore_usesLoadedLevel() {
        // given
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        given(userClickerLevelCache.loadIfAbsent(USER_ID)).willReturn(2L);
        given(clickerService.getClickerById(2L)).willReturn(new Clicker(2L, 20, 600L));
        given(scoreRestoreService.isReady()).willReturn(true);
        given(objectMapper.writeValueAsString(any())).willReturn("temp");

        // when
        ClickActionGuard.Score scoreVo = clickActionGuard.verifyAndGetBaseScore(USER_ID, "sub-1", 5, ClickActionGuard.ClickType.CLICK);

        // then
        assertThat(scoreVo.score()).isEqualTo(100L);
        then(valueOperations).should(never()).set(anyString(), anyString());
    }

    @Test
    @DisplayName("크리티컬 확률이 100이면 다음 배치의 0~19번 위치를 모두 크리티컬로 정해 돌려준다")
    void verifyAndGetBaseScore_picksEveryIndexAtFullRate() {
        // given
        ReflectionTestUtils.setField(clickActionGuard, "criticalRate", 100);
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        given(userClickerLevelCache.loadIfAbsent(USER_ID)).willReturn(1L);
        given(clickerService.getClickerById(1L)).willReturn(new Clicker(1L, 10, 0L));
        given(scoreRestoreService.isReady()).willReturn(true);
        given(objectMapper.writeValueAsString(any())).willReturn("[]");

        // when
        ClickActionGuard.Score scoreVo = clickActionGuard.verifyAndGetBaseScore(USER_ID, "sub-1", 3, ClickActionGuard.ClickType.CLICK);

        // then: 이번 배치는 저장된 위치가 없어 크리티컬 없이 계산한다
        assertThat(scoreVo.score()).isEqualTo(30L);
        assertThat(scoreVo.criticalIndex()).containsExactlyElementsOf(IntStream.range(0, 20).boxed().toList());
    }

    @Test
    @DisplayName("Redis 복구가 끝나지 않았으면 RESTORE_SCORE_RESTORING 예외, 제출 ID와 요청 한도는 쓰지 않는다")
    void verifyAndGetBaseScore_rejectsWhileRestoring() {
        // given
        given(scoreRestoreService.isReady()).willReturn(false);

        // when & then
        assertThatThrownBy(() -> clickActionGuard.verifyAndGetBaseScore(USER_ID, "sub-1", 5, ClickActionGuard.ClickType.CLICK))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RESTORE_SCORE_RESTORING);
        then(stringRedisTemplate).shouldHaveNoInteractions();
        then(actionRateLimiter).shouldHaveNoInteractions();
        then(clickCountLimiter).shouldHaveNoInteractions();
    }
}
