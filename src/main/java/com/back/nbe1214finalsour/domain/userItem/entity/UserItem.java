package com.back.nbe1214finalsour.domain.userItem.entity;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;


@Entity
@Table(name = "user_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    // 구매한 가격
    @Column(nullable = false)
    private long price;

    // 로고 변경권일 시 로고 경로
    @Column(name = "logo_image_url", length = 500)
    private String logoImageUrl;

    // 관리자가 로고 삭제한 시간
    @Column(name = "logo_removed_at")
    private OffsetDateTime logoRemovedAt;

    // 효과 만료 시간
    @Column(name = "expired_at")
    private OffsetDateTime expiredAt;

    // 기여도 아이템 구매 시 이력 생성
    public static UserItem ofPotion(Long userId, Item potion, OffsetDateTime purchasedAt) {
        UserItem userItem = new UserItem();
        userItem.userId = userId;
        userItem.itemId = potion.getId();
        userItem.price = potion.getPrice();
        userItem.expiredAt = purchasedAt.plusSeconds(potion.getDuration());
        return userItem;
    }
}
