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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

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

    @InjectMocks
    private ClickActionGuard clickActionGuard;

    @Test
    @DisplayName("클릭 점수를 계산하면 loadIfAbsent가 반환한 레벨의 clickPower에 클릭 수를 곱한다")
    void verifyAndGetBaseScore_usesLoadedLevel() {
        // given
        given(userStatusCache.find(USER_ID)).willReturn(Optional.of(UserStatus.ACTIVE));
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        given(userClickerLevelCache.loadIfAbsent(USER_ID)).willReturn(2L);
        given(clickerService.getClickerById(2L)).willReturn(new Clicker(2L, 20, 300L));
        given(scoreRestoreService.isReady()).willReturn(true);

        // when
        long score = clickActionGuard.verifyAndGetBaseScore(USER_ID, "sub-1", 5);

        // then
        assertThat(score).isEqualTo(100L);
        then(valueOperations).should(never()).set(anyString(), anyString());
    }

    @Test
    @DisplayName("Redis 복구가 끝나지 않았으면 RESTORE_SCORE_RESTORING 예외, 제출 ID와 요청 한도는 쓰지 않는다")
    void verifyAndGetBaseScore_rejectsWhileRestoring() {
        // given
        given(scoreRestoreService.isReady()).willReturn(false);

        // when & then
        assertThatThrownBy(() -> clickActionGuard.verifyAndGetBaseScore(USER_ID, "sub-1", 5))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RESTORE_SCORE_RESTORING);
        then(stringRedisTemplate).shouldHaveNoInteractions();
        then(actionRateLimiter).shouldHaveNoInteractions();
        then(clickCountLimiter).shouldHaveNoInteractions();
    }
}
