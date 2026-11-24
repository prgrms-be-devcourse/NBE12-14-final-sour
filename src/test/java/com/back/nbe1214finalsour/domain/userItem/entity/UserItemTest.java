package com.back.nbe1214finalsour.domain.userItem.entity;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class UserItemTest {

    private static final Item UNIVERSITY_LOGO = new Item(
            3L, "학교 로고 변경권", "우리 학교 로고 변경",
            ItemType.LOGO_UNIVERSITY, PayType.CREDIT, 3900L, null, null);

    private static final String LOGO_URL = "https://cdn.devcproject.cloud/custom-logo/1/abc.png";

    @Test
    @DisplayName("로고 변경권 구매 이력은 가격 스냅샷과 로고 이미지를 담고, 영구라 만료 시각이 없다")
    void ofLogo() {
        UserItem userItem = UserItem.ofLogo(1L, UNIVERSITY_LOGO, LOGO_URL);

        assertThat(userItem.getUserId()).isEqualTo(1L);
        assertThat(userItem.getItemId()).isEqualTo(3L);
        assertThat(userItem.getPrice()).isEqualTo(3900L);
        assertThat(userItem.getLogoImageUrl()).isEqualTo(LOGO_URL);
        assertThat(userItem.getExpiredAt()).isNull();
    }
}
