package com.back.nbe1214finalsour.domain.university.dto;

import java.util.List;

/**
 * 각 항목과 내 행의 {@code rankChange}: 어제 00:00 KST 복사본에서의 순위 − 지금 순위 (SCRUM-184).
 * 양수는 상승이고, 비교할 순위가 없으면 null이다 (새 멤버, 또는 오늘 복사본이 없음).
 */
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
            long totalScore,
            Long rankChange
    ) {}

    public record MyDepartment(
            long rank,
            long departmentId,
            long totalScore,
            Long rankChange
    ) {}
}