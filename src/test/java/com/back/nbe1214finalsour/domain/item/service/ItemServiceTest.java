package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import com.back.nbe1214finalsour.domain.item.repository.ItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class ItemServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @InjectMocks
    private ItemService itemService;

    @Test
    @DisplayName("아이템 목록을 리포지토리 조회 순서 그대로 반환")
    void getItems(){
        // given
        List<Item> items = List.of(
                new Item(1L, "2배 클릭", "10분 동안 클릭 점수 2배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 3000L, 600, 2.0),
                new Item(2L, "학교 로고 변경권", "우리 학교 로고 변경", ItemType.LOGO_UNIVERSITY, PayType.CREDIT, 3900L, null,  null)
        );
        given(itemRepository.findAllByOrderByIdAsc()).willReturn(items);

        // when
        List<Item> result = this.itemService.getItems();

        // then
        assertThat(result).containsExactlyElementsOf(items);
    }

    @Test
    @DisplayName("아이템 목록을 여러 번 조회해도 DB는 한 번만 조회 (캐시)")
    void getItems_cached() {
        // given
        given(itemRepository.findAllByOrderByIdAsc()).willReturn(List.of(
                new Item(1L, "2배 클릭", "10분 동안 클릭 점수 2배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 3000L, 600, 2.0)
        ));

        // when: 목록을 세 번 조회
        itemService.getItems();
        itemService.getItems();
        itemService.getItems();

        // then: 리포지토리(DB)는 한 번만 호출
        verify(itemRepository, times(1)).findAllByOrderByIdAsc();
    }
}
