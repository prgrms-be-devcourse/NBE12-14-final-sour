package com.back.nbe1214finalsour.domain.click.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record UniversityActivityResponse(
        @Schema(description = "집계한 초 (epoch seconds, 서버 현재 초 − 1)", example = "1795789466")
        Long second,
        @Schema(description = "그 초에 이 학교 학생들이 한 클릭 수 (없으면 0)", example = "12")
        Long clicks,
        @Schema(description = "그 초에 이 학교가 받은 공격 수 (없으면 0)", example = "3")
        Long attacks,
        @Schema(description = "그 초에 이 학교가 받은 지원 수 (없으면 0)", example = "1")
        Long assists
) {
}
