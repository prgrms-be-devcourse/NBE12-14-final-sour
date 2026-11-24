package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.item.entity.Item;

import java.time.OffsetDateTime;

public record PurchaseHistory(
        Long userItemId,
        Item item,
        long price,
        OffsetDateTime createdAt,
        OffsetDateTime expiredAt
) {
}
