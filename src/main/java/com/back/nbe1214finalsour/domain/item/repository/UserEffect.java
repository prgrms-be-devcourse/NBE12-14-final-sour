package com.back.nbe1214finalsour.domain.item.repository;

import java.time.OffsetDateTime;

public record UserEffect(
        Long itemId,
        double multiplier,
        OffsetDateTime expiredAt
) {
}
