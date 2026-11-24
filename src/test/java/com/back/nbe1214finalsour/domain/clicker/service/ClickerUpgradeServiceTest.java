package com.back.nbe1214finalsour.domain.clicker.service;

import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import com.back.nbe1214finalsour.domain.clicker.repository.RedisClickerUpgrader;
import com.back.nbe1214finalsour.domain.clicker.repository.UserClickerLevelCache;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
public class ClickerUpgradeServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long UNIVERSITY_ID = 12L;

    // 레벨표: 1레벨(기본) → 2레벨(300) → 3레벨(1000)
    private static final List<Clicker> LEVEL_TABLE = List.of(
            new Clicker(1L, 10, 0L),
            new Clicker(2L, 20, 300L),
            new Clicker(3L, 40, 1000L)
    );

    @Mock
    private ClickerService clickerService;

    @Mock
    private UserClickerLevelCache userClickerLevelCache;

    @Mock
    private RedisClickerUpgrader clickerUpgrader;

    @InjectMocks
    private ClickerUpgradeService clickerUpgradeService;

    @Nested
    @DisplayName("이미 최고 레벨일 때")
    class WhenMaxLevel {

        @Test
        @DisplayName("업그레이드하면 CLICKER_MAX_LEVEL 예외가 나고, Redis 업그레이드는 하지 않는다")
        void upgrade_maxLevel() {
            // given: 레벨표는 3레벨이 최고 레벨, 유저는 이미 3레벨
            given(clickerService.getLevelTable()).willReturn(LEVEL_TABLE);
            given(userClickerLevelCache.loadIfAbsent(USER_ID)).willReturn(3L);

            // when
            Throwable thrown = catchThrowable(
                    () -> clickerUpgradeService.upgrade(USER_ID, UNIVERSITY_ID));

            // then
            assertThat(thrown)
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.CLICKER_MAX_LEVEL);

            then(clickerUpgrader).should(never()).upgrade(anyLong(), anyLong(), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("기여도가 부족할 때")
    class WhenNotEnoughScore {

        @Test
        @DisplayName("업그레이드하면 CLICKER_NOT_ENOUGH_SCORE 예외가 난다")
        void upgrade_notEnoughScore() {
            // given: 유저는 1레벨, 다음 레벨(2) 비용은 300인데 잔액 부족(-1)
            given(clickerService.getLevelTable()).willReturn(LEVEL_TABLE);
            given(userClickerLevelCache.loadIfAbsent(USER_ID)).willReturn(1L);
            given(clickerUpgrader.upgrade(USER_ID, UNIVERSITY_ID, 1L, 300L)).willReturn(-1L);

            // when
            Throwable thrown = catchThrowable(
                    () -> clickerUpgradeService.upgrade(USER_ID, UNIVERSITY_ID));

            // then
            assertThat(thrown)
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.CLICKER_NOT_ENOUGH_SCORE);
        }
    }

    @Nested
    @DisplayName("업그레이드에 성공할 때")
    class WhenSuccess {

        @Test
        @DisplayName("업그레이드하면 현재 레벨을 기대 레벨로 다음 레벨 비용을 Redis에서 차감하고, 올린 결과를 반환한다")
        void upgrade_success() {
            // given
            given(clickerService.getLevelTable()).willReturn(LEVEL_TABLE);
            given(userClickerLevelCache.loadIfAbsent(USER_ID)).willReturn(1L);
            given(clickerUpgrader.upgrade(USER_ID, UNIVERSITY_ID, 1L, 300L)).willReturn(700L);

            // when
            ClickerUpgradeResult result = clickerUpgradeService.upgrade(USER_ID, UNIVERSITY_ID);

            // then
            assertThat(result.upgradedClicker().getId()).isEqualTo(2L);
            assertThat(result.upgradedClicker().getClickPower()).isEqualTo(20);
            assertThat(result.nextLevelRequiredScore()).isEqualTo(1000L);
            assertThat(result.remainingScore()).isEqualTo(700L);

            then(clickerUpgrader).should().upgrade(USER_ID, UNIVERSITY_ID, 1L, 300L);
        }
    }

    @Nested
    @DisplayName("동시 요청이 먼저 레벨을 올렸을 때")
    class WhenConflict {

        @Test
        @DisplayName("업그레이드하면 CLICKER_UPGRADE_CONFLICT 예외가 난다")
        void upgrade_conflict() {
            // given: 서버가 비용을 계산한 뒤 다른 요청이 먼저 레벨을 올려 Lua가 -2 반환
            given(clickerService.getLevelTable()).willReturn(LEVEL_TABLE);
            given(userClickerLevelCache.loadIfAbsent(USER_ID)).willReturn(1L);
            given(clickerUpgrader.upgrade(USER_ID, UNIVERSITY_ID, 1L, 300L)).willReturn(-2L);

            // when
            Throwable thrown = catchThrowable(
                    () -> clickerUpgradeService.upgrade(USER_ID, UNIVERSITY_ID));

            // then
            assertThat(thrown)
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.CLICKER_UPGRADE_CONFLICT);
        }
    }
}
