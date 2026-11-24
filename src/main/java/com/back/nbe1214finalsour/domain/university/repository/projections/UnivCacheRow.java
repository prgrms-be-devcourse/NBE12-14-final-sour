package com.back.nbe1214finalsour.domain.university.repository.projections;

import java.math.BigDecimal;

public record UnivCacheRow(
        Long id,
        String name,
        BigDecimal libraryLat,
        BigDecimal libraryLng,
        boolean hidden) {
}
