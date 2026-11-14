package com.back.nbe1214finalsour.domain.university.dto;

import java.math.BigDecimal;

public record UniversityMapResponse(
        long rank,
        long universityId,
        String name,
        String logoUrl,
        long totalScore,
        BigDecimal latitude,
        BigDecimal longitude
) {
}