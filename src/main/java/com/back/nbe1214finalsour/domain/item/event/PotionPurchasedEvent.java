package com.back.nbe1214finalsour.domain.item.event;

import java.time.OffsetDateTime;

public record PotionPurchasedEvent(Long userId, Long itemId, double multiplier, OffsetDateTime expiredAt) {
}
