package com.back.nbe1214finalsour.domain.university.dto;

import java.util.List;

public record UserRankingResponse(
        List<Item> items,
        int page,
        int size,
        long totalCount,
        boolean hasNext,
        Me me
) {
    public record Item(
            long rank,
            long userId,
            String nickname,
            String profileImageUrl,
            DepartmentInfo department,
            long contribScore
    ) {}

    public record DepartmentInfo(
            long id,
            String name
    ) {}

    public record Me(
            long rank,
            long contribScore
    ) {}
}
