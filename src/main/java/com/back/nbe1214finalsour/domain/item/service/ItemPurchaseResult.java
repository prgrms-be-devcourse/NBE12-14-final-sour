package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.item.entity.Item;

import java.time.OffsetDateTime;

// 아이템 구매 결과
public record ItemPurchaseResult(
        Long userItemId,
        Item item,
        long price,
        OffsetDateTime expiredAt,
        long myContribScore,
        long myCreditBalance
) {
}