package com.back.nbe1214finalsour.domain.click.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ClickResponse(
        @Schema(description = "반영한 클릭 수 (요청한 clickCount와 같다)", example = "15")
        Long acceptedClicks,
        @Schema(description = "이번 제출로 얻은 점수", example = "18")
        Long earnedScore,
        @Schema(description = "적용한 배율 (아이템 배율 × 도서관 보너스)", example = "1.2")
        Double multiplier,
        @Schema(description = "제출 뒤 내 누적 기여도", example = "1520")
        Long myContribScore,
        @Schema(description = "내 학교")
        ClickUniversityResponse university,
        @Schema(description = "내 학과")
        ClickDepartmentResponse department,
        @Schema(description = "직전 1초 동안 내 학교 전체 클릭 수 (연출용)", example = "42")
        Long schoolClicksLastSecond,
        @Schema(description = "도서관 보너스를 받았는지", example = "true")
        Boolean libraryBonus
) {
    public static ClickResponse from(ClickApplyResponse clickApplyResponse, int clickCount, long score, Double multiplier, Long universityId, Long departmentId, boolean libraryBonus) {
        // TODO: 대학 로고 URL 수정
        return new ClickResponse(
                (long) clickCount,
                score,
                multiplier,
                clickApplyResponse.myContrib(),
                new ClickUniversityResponse(universityId, clickApplyResponse.univTotal(), clickApplyResponse.univRank(), "tempUrl"),
                new ClickDepartmentResponse(departmentId, clickApplyResponse.deptTotal()),
                clickApplyResponse.lastSecond(),
                libraryBonus
        );
    }

    record ClickUniversityResponse(Long id, Long totalScore, Long rank, String logoUrl) {}
    record ClickDepartmentResponse(Long id, Long totalScore) {}
}