package com.back.nbe1214finalsour.domain.item.repository;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@IntegrationTest
@Transactional
class ItemRepositoryTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("아이템 목록을 ID 오름차순으로 조회하고 종류·결제 수단을 Enum으로, 로고 변경권의 지속시간·배율은 null로 매핑")
    void findAllByOrderByIdAsc() {
        // given: 정렬 확인을 위해 ID가 큰 것부터 넣음 (시드와 겹치지 않게 큰 ID 사용)
        jdbcTemplate.update("""
                INSERT INTO item (id, name, content, type, pay_type, price, duration, multiplier)
                VALUES (9002, '테스트 학교 로고 변경권', '우리 학교 로고 변경', 'LOGO_UNIVERSITY', 'CREDIT', 3900, NULL, NULL),
                       (9001, '테스트 2배 클릭', '10분 동안 클릭 점수 2배', 'MULTIPLIER', 'CONTRIBUTION', 3000, 600, 2.0)
                """);

        // when
        List<Item> items = itemRepository.findAllByOrderByIdAsc();

        // then: 시드가 있어도 이 테스트가 넣은 것만 확인
        assertThat(items)
                .filteredOn(item -> item.getId() >= 9001)
                .extracting(Item::getId, Item::getName, Item::getType, Item::getPayType, Item::getPrice, Item::getDuration, Item::getMultiplier)
                .containsExactly(
                        tuple(9001L, "테스트 2배 클릭", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 3000L, 600, 2.0),
                        tuple(9002L, "테스트 학교 로고 변경권", ItemType.LOGO_UNIVERSITY, PayType.CREDIT, 3900L, null, null)
                );
    }
}