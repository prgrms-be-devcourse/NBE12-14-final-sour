package com.back.nbe1214finalsour.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/v1/auth/email/code} 요청 */
public record EmailCodeRequest(
        @Schema(description = "학교 이메일", example = "student@example.ac.kr")
        @NotBlank @Email @Size(max = 254) String email) {
}
