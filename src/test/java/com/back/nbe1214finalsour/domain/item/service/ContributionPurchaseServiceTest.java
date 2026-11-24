package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.credit.repository.CreditWallet;
import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import com.back.nbe1214finalsour.domain.item.repository.PotionPurchaseOutcome;
import com.back.nbe1214finalsour.domain.item.repository.RedisPotionPurchaser;
import com.back.nbe1214finalsour.domain.userItem.entity.UserItem;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ContributionPurchaseServiceTest {

    @Mock
    private RedisPotionPurchaser redisPotionPurchaser;

    @Mock
    private CreditWallet creditWallet;

    @Mock
    private PotionPurchaseRecorder potionPurchaseRecorder;

    private static final Instant NOW = Instant.parse("2026-11-14T03:00:00Z");

    @Spy
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @InjectMocks
    private ContributionPurchaseService contributionPurchaseService;

    private static final Long USER_ID = 1L;
    private static final Long UNIVERSITY_ID = 12L;
    private static final UUID KEY = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    // 고정 시계 기준 1시간 뒤 (물약 duration 3600초)
    private static final OffsetDateTime EXPIRED_AT = NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC);

    private static final Item POTION_X2 =
            new Item(1L, "2배 물약", "1시간 동안 클릭 점수 2배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 500L, 3600, 2.0);
    private static final Item POTION_X4 =
            new Item(2L, "4배 물약", "1시간 동안 클릭 점수 4배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 1000L, 3600, 4.0);

    @Test
    @DisplayName("물약을 사면 아이템 가격만큼 회원의 기여도를 차감한다")
    void purchase_deductsContribution() {
        // given
        given(this.redisPotionPurchaser.purchase(USER_ID, UNIVERSITY_ID, 2L, 1000L, 4.0, EXPIRED_AT, KEY))
                .willReturn(new PotionPurchaseOutcome(1500L, ""));
        given(this.potionPurchaseRecorder.record(any())).willAnswer(returnsFirstArg());

        // when
        this.contributionPurchaseService.purchase(USER_ID, UNIVERSITY_ID, POTION_X4, KEY);

        // then
        then(this.redisPotionPurchaser).should().purchase(USER_ID, UNIVERSITY_ID, 2L, 1000L, 4.0, EXPIRED_AT, KEY);
    }

    @Test
    @DisplayName("잔여 기여도보다 비싼 물약을 사면 ITEM_NOT_ENOUGH_SCORE, 구매 이력이 없다")
    void purchase_notEnoughScore() {
        // given
        given(this.redisPotionPurchaser.purchase(USER_ID, UNIVERSITY_ID, 1L, 500L, 2.0, EXPIRED_AT, KEY))
                .willReturn(new PotionPurchaseOutcome(-1L, ""));

        // when
        Throwable thrown = catchThrowable(() -> this.contributionPurchaseService.purchase(USER_ID, UNIVERSITY_ID, POTION_X2, KEY));

        // then
        assertThat(thrown)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_NOT_ENOUGH_SCORE);
        then(this.potionPurchaseRecorder).should(never()).record(any());
    }

    @Test
    @DisplayName("물약을 사면 구매 이력을 저장한다")
    void purchase_saveUserItem() {
        // given
        given(this.redisPotionPurchaser.purchase(USER_ID, UNIVERSITY_ID, 2L, 1000L, 4.0, EXPIRED_AT, KEY))
                .willReturn(new PotionPurchaseOutcome(1500L, ""));
        given(this.potionPurchaseRecorder.record(any())).willAnswer(returnsFirstArg());

        // when
        this.contributionPurchaseService.purchase(USER_ID, UNIVERSITY_ID, POTION_X4, KEY);

        // then
        ArgumentCaptor<UserItem> captor = ArgumentCaptor.forClass(UserItem.class);
        then(this.potionPurchaseRecorder).should().record(captor.capture());

        UserItem saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getItemId()).isEqualTo(2L);
        assertThat(saved.getPrice()).isEqualTo(1000L);
        assertThat(saved.getExpiredAt()).isAtSameInstantAs(NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC));
        assertThat(saved.getLogoImageUrl()).isNull();
    }

    @Test
    @DisplayName("구매 이력 저장이 실패하면 보상 Lua로 기여도와 효과를 되돌리고 원래 예외를 다시 던진다")
    void purchase_compensatesWhenSaveFails() {
        // given
        String previousEffect = "{\"itemId\":\"1\"}";
        given(this.redisPotionPurchaser.purchase(USER_ID, UNIVERSITY_ID, 2L, 1000L, 4.0, EXPIRED_AT, KEY))
                .willReturn(new PotionPurchaseOutcome(1500L, previousEffect));
        DataIntegrityViolationException failure = new DataIntegrityViolationException("user_item 저장 실패");
        given(this.potionPurchaseRecorder.record(any())).willThrow(failure);

        // when
        Throwable thrown = catchThrowable(() -> this.contributionPurchaseService.purchase(USER_ID, UNIVERSITY_ID, POTION_X4, KEY));

        // then
        assertThat(thrown).isSameAs(failure);
        // Redis는 롤백되지 않으므로 보상 Lua로 기여도와 효과를 되돌림 (이전 효과는 Lua가 준 문자열 그대로)
        then(this.redisPotionPurchaser).should().compensate(USER_ID, UNIVERSITY_ID, 1000L, KEY, previousEffect);
    }

    @Test
    @DisplayName("보상 Lua까지 실패해도 던지는 예외는 원래 저장 실패 예외이고, 보상 예외는 suppressed로 남긴다")
    void purchase_compensateAlsoFails_keepsOriginalFailure() {
        // given
        given(this.redisPotionPurchaser.purchase(USER_ID, UNIVERSITY_ID, 2L, 1000L, 4.0, EXPIRED_AT, KEY))
                .willReturn(new PotionPurchaseOutcome(1500L, ""));
        DataIntegrityViolationException failure = new DataIntegrityViolationException("user_item 저장 실패");
        given(this.potionPurchaseRecorder.record(any())).willThrow(failure);
        RedisSystemException compensateFailure = new RedisSystemException("보상 Lua 실패", new RuntimeException("Redis 연결 끊김"));
        willThrow(compensateFailure).given(this.redisPotionPurchaser).compensate(any(), any(), anyLong(), any(), any());

        // when
        Throwable thrown = catchThrowable(() -> this.contributionPurchaseService.purchase(USER_ID, UNIVERSITY_ID, POTION_X4, KEY));

        // then
        assertThat(thrown).isSameAs(failure);
        assertThat(thrown.getSuppressed()).containsExactly(compensateFailure);
    }

    @Test
    @DisplayName("구매 이력 저장이 끝난 뒤 크레딧 조회가 실패하면 그 예외를 그대로 던지고 커밋된 구매를 보상하지 않는다")
    void purchase_balanceLookupFails_noCompensation() {
        // given
        given(this.redisPotionPurchaser.purchase(USER_ID, UNIVERSITY_ID, 2L, 1000L, 4.0, EXPIRED_AT, KEY))
                .willReturn(new PotionPurchaseOutcome(1500L, "{\"itemId\":\"1\"}"));
        given(this.potionPurchaseRecorder.record(any())).willAnswer(returnsFirstArg());
        IllegalStateException lookupFailure = new IllegalStateException("크레딧 조회 실패");
        given(this.creditWallet.findBalance(USER_ID)).willThrow(lookupFailure);

        // when
        Throwable thrown = catchThrowable(() -> this.contributionPurchaseService.purchase(USER_ID, UNIVERSITY_ID, POTION_X4, KEY));

        // then
        assertThat(thrown).isSameAs(lookupFailure);
        then(this.redisPotionPurchaser).should(never()).compensate(any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("같은 멱등 키로 이미 처리한 요청이면 ITEM_DUPLICATE_PURCHASE, 구매 이력을 저장하지 않는다")
    void purchase_duplicateKey() {
        // given
        given(this.redisPotionPurchaser.purchase(USER_ID, UNIVERSITY_ID, 2L, 1000L, 4.0, EXPIRED_AT, KEY))
                .willReturn(new PotionPurchaseOutcome(-2L, ""));

        // when
        Throwable thrown = catchThrowable(() -> this.contributionPurchaseService.purchase(USER_ID, UNIVERSITY_ID, POTION_X4, KEY));

        // then
        assertThat(thrown)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_DUPLICATE_PURCHASE);
        then(this.potionPurchaseRecorder).should(never()).record(any());
    }

    @Test
    @DisplayName("만료 시각은 밀리초로 잘라 Lua와 구매 이력에 같은 값을 쓴다 (Redis는 ms, DB는 µs)")
    void purchase_truncatesExpiredAtToMillis() {
        // given
        willReturn(Instant.parse("2026-11-14T03:00:00.123456789Z")).given(this.clock).instant();
        OffsetDateTime expected = Instant.parse("2026-11-14T03:00:00.123Z").plusSeconds(3600).atOffset(ZoneOffset.UTC);
        given(this.redisPotionPurchaser.purchase(any(), any(), any(), anyLong(), anyDouble(), any(), any()))
                .willReturn(new PotionPurchaseOutcome(1500L, ""));
        given(this.potionPurchaseRecorder.record(any())).willAnswer(returnsFirstArg());

        // when
        this.contributionPurchaseService.purchase(USER_ID, UNIVERSITY_ID, POTION_X4, KEY);

        // then
        then(this.redisPotionPurchaser).should().purchase(USER_ID, UNIVERSITY_ID, 2L, 1000L, 4.0, expected, KEY);
        ArgumentCaptor<UserItem> captor = ArgumentCaptor.forClass(UserItem.class);
        then(this.potionPurchaseRecorder).should().record(captor.capture());
        assertThat(captor.getValue().getExpiredAt()).isEqualTo(expected);
    }

    @Test
    @DisplayName("물약 구매 결과로 구매 이력 ID·아이템·가격·만료 시각·차감 후 기여도·현재 크레딧을 반환")
    void purchase_returnsResult() {
        // given
        given(this.redisPotionPurchaser.purchase(USER_ID, UNIVERSITY_ID, 2L, 1000L, 4.0, EXPIRED_AT, KEY))
                .willReturn(new PotionPurchaseOutcome(1500L, ""));
        // IDENTITY 저장 시 DB가 같은 객체에 id를 채워 주는 것을 흉내
        given(this.potionPurchaseRecorder.record(any()))
                .willAnswer(invocation -> {
                    UserItem userItem = invocation.getArgument(0);
                    ReflectionTestUtils.setField(userItem, "id", 5530L);
                    return userItem;
                });
        given(this.creditWallet.findBalance(USER_ID))
                .willReturn(1100L);

        // when
        ItemPurchaseResult result = this.contributionPurchaseService.purchase(USER_ID, UNIVERSITY_ID, POTION_X4, KEY);

        // then
        assertThat(result.userItemId()).isEqualTo(5530L);
        assertThat(result.item()).isSameAs(POTION_X4);
        assertThat(result.price()).isEqualTo(1000L);
        assertThat(result.expiredAt()).isAtSameInstantAs(NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC));
        assertThat(result.myContribScore()).isEqualTo(1500L);
        assertThat(result.myCreditBalance()).isEqualTo(1100L);
    }
}