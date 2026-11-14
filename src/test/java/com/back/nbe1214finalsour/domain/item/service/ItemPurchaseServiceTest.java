package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.contribution.ContributionWallet;
import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.OptionalLong;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class ItemPurchaseServiceTest {

    @Mock
    private ItemService itemService;

    @Mock
    private ContributionWallet contributionWallet;

    @InjectMocks
    private ItemPurchaseService itemPurchaseService;

    private static final Long USER_ID = 1L;
    private static final Long UNIVERSITY_ID = 12L;

    private static final List<Item> POTIONS = List.of(
            new Item(1L, "2배 물약", "1시간 동안 클릭 점수 2배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 500L, 3600),
            new Item(2L, "4배 물약", "1시간 동안 클릭 점수 4배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 1000L, 3600)
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


}
