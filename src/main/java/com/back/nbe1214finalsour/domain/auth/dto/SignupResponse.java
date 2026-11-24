package com.back.nbe1214finalsour.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code POST /api/v1/auth/signup} 응답 (201). 클라이언트는 이어서 로그인 API를 호출한다 */
public record SignupResponse(
        @Schema(description = "회원 ID", example = "1")
        Long userId,
        @Schema(description = "닉네임", example = "학생")
        String nickname,
        @Schema(description = "학교 정보")
        UniversitySummary university,
        @Schema(description = "학과 정보")
        DepartmentSummary department) {
}
