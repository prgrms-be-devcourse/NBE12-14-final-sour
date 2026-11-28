package com.back.nbe1214finalsour.domain.click.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

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
        Long myContribScore,
        @Schema(description = "크리티컬이 몇 번 인덱스에서 터지는지", example = "[1, 6, 10]")
        List<Integer> criticalIndex,
        @Schema(description = "데미지 비율 인덱스", example = "[89, 90, 110, 130, 50, ... 20개]")
        List<Integer> damageRateIndex,
        @Schema(description = "직전 1초 동안 지원 중인 학교의 전체 지원 수 (연출용)", example = "42")
        Long schoolAssistsLastSecond,
        @Schema(description = "직전 1초 동안 지원 중인 학교의 전체 클릭 수 (연출용)", example = "42")
        Long schoolClicksLastSecond,
        @Schema(description = "직전 1초 동안 지원 중인 학교의 전체 공격 수 (연출용)", example = "42")
        Long schoolAttacksLastSecond,
        @Schema(description = "직전 1초의 시간", example = "1795852440")
        Long second
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
