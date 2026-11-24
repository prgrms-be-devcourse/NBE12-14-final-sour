package com.back.nbe1214finalsour.domain.click.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AttackResponse(
        @Schema(description = "반영한 클릭 수 (요청한 clickCount와 같다)", example = "15")
        Long acceptedClicks,
        @Schema(description = "대상 학교에서 깎은 점수", example = "4")
        Long damage,
        @Schema(description = "라이벌 배율. 라이벌 학교면 1.1, 아니면 1.0", example = "1.0")
        Double multiplier,
        @Schema(description = "대상이 라이벌 학교인지", example = "false")
        Boolean isRival,
        @Schema(description = "공격 뒤 대상 학교")
        AttackUniversityResponse targetUniversity,
        @Schema(description = "제출 뒤 내 누적 공격 점수", example = "120")
        Long myAttackScore
) {
    public static AttackResponse from(AttackApplyResponse attackApplyResponse, int clickCount,
                                      boolean isRival, Long targetUniversityId, String targetName) {
        return new AttackResponse(
                (long) clickCount,
                attackApplyResponse.damage(),
                isRival ? 1.1 : 1.0,
                isRival,
                new AttackUniversityResponse(targetUniversityId, targetName, attackApplyResponse.univTotal(), attackApplyResponse.univRank()),
                attackApplyResponse.myAttackScore()
        );
    }
    record AttackUniversityResponse(Long id, String name, Long totalScore, Long rank) {}
}
