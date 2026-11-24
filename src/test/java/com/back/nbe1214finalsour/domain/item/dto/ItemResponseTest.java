package com.back.nbe1214finalsour.domain.item.dto;


import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

public class ItemResponseTest {

    @Test
    @DisplayName("물약은 duration을 durationSeconds로 변환")
    void fromMultiplier(){
        Item item = new Item(1L, "2배 클릭", "10분 동안 클릭 점수 2배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 3000L, 600, 2.0);

        ItemResponse response = new ItemResponse(item);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("2배 클릭");
        assertThat(response.content()).isEqualTo("10분 동안 클릭 점수 2배");
        assertThat(response.type()).isEqualTo(ItemType.MULTIPLIER);
        assertThat(response.payType()).isEqualTo(PayType.CONTRIBUTION);
        assertThat(response.price()).isEqualTo(3000L);
        assertThat(response.durationSeconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("로고 변경권은 영구라 durationSeconds가 null")
    void fromLogo() {
        Item item = new Item(2L, "학교 로고 변경권", "우리 학교 로고 변경", ItemType.LOGO_UNIVERSITY, PayType.CREDIT, 3900L, null, null);

        ItemResponse response = new ItemResponse(item);

        assertThat(response.type()).isEqualTo(ItemType.LOGO_UNIVERSITY);
        assertThat(response.durationSeconds()).isNull();
    }
}
