package com.back.nbe1214finalsour.domain.click.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 연합 학교 지원 응답 (추가 명세 §3-3).
 * assistScore는 대상 학교에 더한 점수, myContribScore는 지원 뒤 내 누적 기여도다.
 */
public record AssistResponse(
        @Schema(description = "반영한 클릭 수 (요청한 clickCount와 같다)", example = "15")
        Long acceptedClicks,
        @Schema(description = "대상 학교에 더한 점수", example = "4")
        Long assistScore,
        @Schema(description = "지원 뒤 대상 학교")
        AssistUniversityResponse targetUniversity,
        @Schema(description = "지원 뒤 내 누적 기여도", example = "1520")
        Long myContribScore
) {
    public record AssistUniversityResponse(
            @Schema(description = "학교 ID", example = "2")
            Long id,
            @Schema(description = "학교 이름", example = "고려대학교")
            String name,
            @Schema(description = "지원 후 학교 총 점수", example = "50_000")
            Long totalScore,
            @Schema(description = "지원 후 학교 순위", example = "1")
            Long rank
    ) {}
}
