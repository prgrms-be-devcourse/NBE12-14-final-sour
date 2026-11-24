package com.back.nbe1214finalsour.domain.university.dto;

import com.back.nbe1214finalsour.domain.university.entity.University;

public record UniversitySearchResponse(
        Long id,
        String name,
        String logoUrl
) {
    public UniversitySearchResponse(University university) {
        this(
                university.getId(),
                university.getName(),
                university.getLogoUrl()
        );
    }
}
