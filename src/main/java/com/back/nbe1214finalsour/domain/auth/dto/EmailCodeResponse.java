package com.back.nbe1214finalsour.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code POST /api/v1/auth/email/code} 응답
 *
 * @param university       이메일 도메인으로 정한 학교
 * @param expiresInSeconds 코드 유효 시간(초)
 */
public record EmailCodeResponse(
        @Schema(description = "학교 정보")
        UniversitySummary university,
        @Schema(description = "코드 유효 시간(초)", example = "300")
        long expiresInSeconds) {
}
