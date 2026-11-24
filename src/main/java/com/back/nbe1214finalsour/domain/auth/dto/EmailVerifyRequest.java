package com.back.nbe1214finalsour.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code POST /api/v1/auth/email/verify} 요청. 형식이 틀린 코드는 실패 횟수에 넣지 않고 400 {@code COMMON_INVALID_INPUT}으로 막는다 */
public record EmailVerifyRequest(
        @Schema(description = "학교 이메일", example = "student@example.ac.kr")
        @NotBlank @Email @Size(max = 254) String email,
        @Schema(description = "인증 코드", example = "123456")
        @NotBlank @Pattern(regexp = "\\d{6}", message = "인증 코드는 숫자 6자리입니다.") String code) {
}
