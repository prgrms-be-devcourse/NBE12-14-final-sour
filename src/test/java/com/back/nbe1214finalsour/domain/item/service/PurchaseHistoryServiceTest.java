package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import com.back.nbe1214finalsour.domain.userItem.entity.UserItem;
import com.back.nbe1214finalsour.domain.userItem.repository.UserItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
public class PurchaseHistoryServiceTest {

    private static final Item POTION =
            new Item(2L, "4배 물약", "1시간 동안 클릭 점수 4배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 1000L, 3600, 4.0);

    private static final Item UNIVERSITY_LOGO =
            new Item(3L, "학교 로고 변경권", "우리 학교 로고 변경", ItemType.LOGO_UNIVERSITY, PayType.CREDIT, 3900L, null, null);

    private static final String LOGO_URL = "http://localhost:4566/local-logo-bucket/custom-logo/1/a.png";

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 17, 3, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private UserItemRepository userItemRepository;

    @Mock
    private ItemService itemService;

    @InjectMocks
    private PurchaseHistoryService purchaseHistoryService;

    private static UserItem saved(UserItem userItem, Long id, OffsetDateTime createdAt) {
        ReflectionTestUtils.setField(userItem, "id", id);
        ReflectionTestUtils.setField(userItem, "createdAt", createdAt);
        return userItem;
    }

    @Test
    @DisplayName("구매 내역에 아이템 정보를 붙여 리포지토리가 준 순서 그대로 돌려준다")
    void findHistory_withItem(){
        // given
        given(itemService.getItems()).willReturn(List.of(POTION, UNIVERSITY_LOGO));
        UserItem logo = saved(UserItem.ofLogo(1L, UNIVERSITY_LOGO, LOGO_URL), 7001L, NOW.plusHours(1));
        UserItem potion = saved(UserItem.ofPotion(1L, POTION, NOW.plusSeconds(3600)), 5530L, NOW);
        given(userItemRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(1L, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(logo, potion), PageRequest.of(0, 20), 2));

        // when
        Page<PurchaseHistory> result = purchaseHistoryService.findHistory(1L, 0, 20);

        // then
        assertThat(result.getContent()).containsExactly(
                new PurchaseHistory(7001L, UNIVERSITY_LOGO, 3900L, NOW.plusHours(1), null),
                new PurchaseHistory(5530L, POTION, 1000L, NOW, NOW.plusSeconds(3600))
        );

    }

    @Test
    @DisplayName("요청한 페이지·크기로 조회하고 전체 개수와 다음 페이지 여부를 그대로 전달한다")
    void findHistory_paging() {
        // given: 전체 7건 중 2페이지(0부터 셈, 5개씩) → 마지막 페이지
        given(itemService.getItems()).willReturn(List.of(POTION));
        // 7건을 5개씩 나눈 2페이지라 2건이 들어 있다
        UserItem second = saved(UserItem.ofPotion(1L, POTION, NOW.plusHours(1)), 5531L, NOW.plusHours(1));
        UserItem first = saved(UserItem.ofPotion(1L, POTION, NOW), 5530L, NOW);
        given(userItemRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(1L, PageRequest.of(1, 5)))
                .willReturn(new PageImpl<>(List.of(second, first), PageRequest.of(1, 5), 7));
        // when
        Page<PurchaseHistory> result = purchaseHistoryService.findHistory(1L, 1, 5);

        // then
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(5);
        assertThat(result.getTotalElements()).isEqualTo(7);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("아이템 캐시에 없는 아이템의 구매는 item을 null로 돌려준다")
    void findHistory_unknownItem() {
        // given: 캐시에는 물약만 있음
        given(itemService.getItems()).willReturn(List.of(POTION));
        UserItem logo = saved(UserItem.ofLogo(1L, UNIVERSITY_LOGO, LOGO_URL), 7001L, NOW);
        given(userItemRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(1L, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(logo), PageRequest.of(0, 20), 1));

        // when
        Page<PurchaseHistory> result = purchaseHistoryService.findHistory(1L, 0, 20);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().userItemId()).isEqualTo(7001L);
        assertThat(result.getContent().getFirst().item()).isNull();
    }
}
