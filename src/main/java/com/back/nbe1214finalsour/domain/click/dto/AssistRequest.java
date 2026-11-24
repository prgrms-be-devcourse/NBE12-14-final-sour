package com.back.nbe1214finalsour.domain.click.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 연합 학교 지원 요청 (추가 명세 §3-3). 검증은 공격 요청과 같다 */
public record AssistRequest(
        @NotBlank(message = "고유한 제출 ID가 필요합니다.")
        @Schema(description = "제출마다 새로 만드는 고유 ID. 10초 안에 같은 값으로 다시 보내면 409", example = "01JDQ6Z3X8M2N4P5R7S9T1V3W5")
        String submissionId,
        @NotNull(message = "클릭 횟수가 필요합니다.")
        @Min(value = 1, message = "최소 1 이상의 클릭 횟수가 필요합니다.")
        @Max(value = 20, message = "부정 행위를 감지했습니다.")
        @Schema(description = "이번에 제출하는 클릭 수 (1~20)", example = "15")
        Integer clickCount,
        @NotNull(message = "지원할 학교의 ID를 입력해주세요.")
        @Schema(description = "지원할 학교 ID. 같은 연합의 다른 학교만 된다", example = "2")
        Long targetUniversityId
) {
}
