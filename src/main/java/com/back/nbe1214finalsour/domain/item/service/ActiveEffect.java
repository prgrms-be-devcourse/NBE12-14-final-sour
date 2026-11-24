package com.back.nbe1214finalsour.domain.item.service;

import java.time.OffsetDateTime;

public record ActiveEffect(
        Long itemId,
        String name,
        double multiplier,
        OffsetDateTime expiredAt,
        long remainingSeconds
) {
}
