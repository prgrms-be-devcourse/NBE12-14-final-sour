package com.back.nbe1214finalsour.domain.university.dto;

import java.util.List;

public record UniversityRankingResponse(
        List<Item> items,
        int page,
        int size,
        long totalCount,
        boolean hasNext,
        MyUniversity myUniversity
) {
    public record Item(
            long rank,
            long universityId,
            String name,
            String logoUrl,
            long totalScore
    ) {}

    public record MyUniversity(
            long rank,
            long universityId,
            long totalScore
    ) {}
}