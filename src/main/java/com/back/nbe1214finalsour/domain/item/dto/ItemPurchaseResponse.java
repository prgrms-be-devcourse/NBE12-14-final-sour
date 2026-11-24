package com.back.nbe1214finalsour.domain.item.dto;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import com.back.nbe1214finalsour.domain.item.service.ItemPurchaseResult;

import java.time.OffsetDateTime;

// 아이템 구매 응답 (201). myContribScore·myCreditBalance는 둘 다 구매 후 현재 값
public record ItemPurchaseResponse(
        Long userItemId,
        PurchasedItem item,
        long price,
        OffsetDateTime expiredAt,
        long myContribScore,
        long myCreditBalance
) {
    public static ItemPurchaseResponse from(ItemPurchaseResult result) {
        return new ItemPurchaseResponse(
                result.userItemId(),
                PurchasedItem.from(result.item()),
                result.price(),
                result.expiredAt(),
                result.myContribScore(),
                result.myCreditBalance()
        );
    }

    public record PurchasedItem(Long id, String name, PayType payType) {
        static PurchasedItem from(Item item) {
            return new PurchasedItem(item.getId(), item.getName(), item.getPayType());
        }
    }
}