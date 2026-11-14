package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.contribution.ContributionWallet;
import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import com.back.nbe1214finalsour.domain.item.event.PotionPurchasedEvent;
import com.back.nbe1214finalsour.domain.userItem.entity.UserItem;
import com.back.nbe1214finalsour.domain.userItem.repository.UserItemRepository;
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
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class ItemPurchaseServiceTest {

    @Mock
    private ItemService itemService;

    @Mock
    private ContributionWallet contributionWallet;

    @Mock
    private UserItemRepository userItemRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private static final Instant NOW = Instant.parse("2026-11-14T03:00:00Z");

    @Spy
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @InjectMocks
    private ItemPurchaseService itemPurchaseService;

    private static final Long USER_ID = 1L;
    private static final Long UNIVERSITY_ID = 12L;

    private static final List<Item> POTIONS = List.of(
            new Item(1L, "2배 물약", "1시간 동안 클릭 점수 2배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 500L, 3600, 2.0),
            new Item(2L, "4배 물약", "1시간 동안 클릭 점수 4배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 1000L, 3600,  4.0),
            new Item(3L, "학교 로고 변경권", "우리 학교 로고 변경", ItemType.LOGO_UNIVERSITY, PayType.CREDIT, 3900L, null, null)
    );


    @Test
    @DisplayName("기여도로 아이템 구매 시 아이템 가격만큼 회원의 기여도 차감")
    void purchase_deductsContribution() {
        // given: 아이템 목록에 기여도 아이템이 있고 기여도가 충분해서 차감 후 1500이 남는다.
        given(this.itemService.getItems())
                .willReturn(POTIONS);
        given(this.contributionWallet.tryDeduct(USER_ID, UNIVERSITY_ID, 1000L))
                .willReturn(OptionalLong.of(1500L));

        // when
        this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, 2L);

        // then: 해당 회원의 기여도에서 물약 가격만큼 차감 요청을 했음
        verify(this.contributionWallet).tryDeduct(USER_ID, UNIVERSITY_ID, 1000L);
    }

    @Test
    @DisplayName("기여도로 존재하지 않는 아이템 구매 시 ITEM_NOT_FOUND 반환")
    void purchase_ItemNotFound() {
        // given
        given(this.itemService.getItems())
                .willReturn(POTIONS);

        // when & then: 아이템 목록에 없는 아이템 구매 시도 시 404
        assertThatThrownBy(() -> this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, 3980L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_NOT_FOUND);

        // then: 차감은 시도하지 않음
        verify(this.contributionWallet, never())
                .tryDeduct(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("기여도로 잔여 기여도보다 가격이 비싼 아이템 구매 시 ITEM_NOT_ENOUGH_SCORE 반환")
    void purchase_ItemNotEnoughScore() {
        // given
        given(this.itemService.getItems())
                .willReturn(POTIONS);
        given(this.contributionWallet.tryDeduct(USER_ID, UNIVERSITY_ID, 500L))
                .willReturn(OptionalLong.empty());

        // when & then
        assertThatThrownBy(() -> this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_NOT_ENOUGH_SCORE);
    }

    @Test
    @DisplayName("크레딧 아이템 구매는 기여도를 차감하지 않는다")
    void purchase_creditItemByContribution() {
        // given
        given(this.itemService.getItems())
                .willReturn(POTIONS);

        // when
        this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, 3L);

        // then
        verify(this.contributionWallet, never())
                .tryDeduct(anyLong(), anyLong(), anyLong());
    }


    @Test
    @DisplayName("기여도로 아이템 구매 시 구매 이력을 저장")
    void purchase_saveUserItem() {
        // given
        given(this.itemService.getItems())
                .willReturn(POTIONS);
        given(this.contributionWallet.tryDeduct(USER_ID, UNIVERSITY_ID, 1000L))
                .willReturn(OptionalLong.of(1500L));

        // when
        this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, 2L);

        // then
        ArgumentCaptor<UserItem> captor = ArgumentCaptor.forClass(UserItem.class);
        verify(this.contributionWallet).tryDeduct(USER_ID, UNIVERSITY_ID, 1000L);
        verify(this.userItemRepository).save(captor.capture());

        UserItem saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getItemId()).isEqualTo(2L);
        assertThat(saved.getPrice()).isEqualTo(1000L);
        assertThat(saved.getExpiredAt()).isAtSameInstantAs(NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC));
        assertThat(saved.getLogoImageUrl()).isNull();
    }

    @Test
    @DisplayName("물약을 사면 배율과 만료 시각을 담은 효과 적용 이벤트를 발행한다")
    void purchase_publishesPotionPurchasedEvent() {
        // given: 2번 물약(4배, 1000, 3600초), 기여도 충분
        given(this.itemService.getItems())
                .willReturn(POTIONS);
        given(this.contributionWallet.tryDeduct(USER_ID, UNIVERSITY_ID, 1000L))
                .willReturn(OptionalLong.of(1500L));

        // when
        this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, 2L);

        // then: 리스너가 Redis(user:{userId}:effect)에 쓸 내용을 담아 발행
        ArgumentCaptor<PotionPurchasedEvent> captor = ArgumentCaptor.forClass(PotionPurchasedEvent.class);
        verify(this.eventPublisher).publishEvent(captor.capture());

        PotionPurchasedEvent event = captor.getValue();
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.itemId()).isEqualTo(2L);
        assertThat(event.multiplier()).isEqualTo(4.0);
        assertThat(event.expiredAt()).isAtSameInstantAs(NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC));
    }
}
