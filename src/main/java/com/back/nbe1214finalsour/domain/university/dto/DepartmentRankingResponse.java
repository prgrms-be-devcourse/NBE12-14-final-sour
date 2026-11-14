package com.back.nbe1214finalsour.domain.university.dto;

import java.util.List;

public record DepartmentRankingResponse(
        List<Item> items,
        int page,
        int size,
        long totalCount,
        boolean hasNext,
        MyDepartment myDepartment
) {
    public record Item(
            long rank,
            long departmentId,
            String name,
            String logoUrl,
            long totalScore
    ) {}

    public record MyDepartment(
            long rank,
            long departmentId,
            long totalScore
    ) {}
}