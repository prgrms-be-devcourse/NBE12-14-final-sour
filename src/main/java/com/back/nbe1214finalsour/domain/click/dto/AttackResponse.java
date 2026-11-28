package com.back.nbe1214finalsour.domain.click.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

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
        Long myAttackScore,
        @Schema(description = "크리티컬이 몇 번 인덱스에서 터지는지", example = "[1, 6, 10]")
        List<Integer> criticalIndex,
        @Schema(description = "데미지 비율 인덱스", example = "[89, 90, 110, 130, 50, ... 20개]")
        List<Integer> damageRateIndex,
        @Schema(description = "직전 1초 동안 공격 중인 학교의 전체 공격 수 (연출용)", example = "42")
        Long schoolAttacksLastSecond,
        @Schema(description = "직전 1초 동안 공격 중인 학교의 전체 클릭 수 (연출용)", example = "42")
        Long schoolClicksLastSecond,
        @Schema(description = "직전 1초 동안 공격 중인 학교의 전체 지원 수 (연출용)", example = "42")
        Long schoolAssistsLastSecond,
        @Schema(description = "직전 1초의 시간", example = "1795852440")
        Long second
) {
    public static AttackResponse from(AttackApplyResponse attackApplyResponse, int clickCount,
                                      boolean isRival, Long targetUniversityId, String targetName,
                                      List<Integer> criticalIndex, List<Integer> damageRateIndex, Long second) {
        return new AttackResponse(
                (long) clickCount,
                attackApplyResponse.damage(),
                isRival ? 1.1 : 1.0,
                isRival,
                new AttackUniversityResponse(targetUniversityId, targetName, attackApplyResponse.univTotal(), attackApplyResponse.univRank()),
                attackApplyResponse.myAttackScore(),
                criticalIndex,
                damageRateIndex,
                attackApplyResponse.attackLastSecond(),
                attackApplyResponse.clickLastSecond(),
                attackApplyResponse.assistLastSecond(),
                second
        );
    }
    record AttackUniversityResponse(Long id, String name, Long totalScore, Long rank) {}
}
