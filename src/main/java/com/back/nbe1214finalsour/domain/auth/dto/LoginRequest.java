package com.back.nbe1214finalsour.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 로그인 요청. 비밀번호 형식 규칙은 가입 때만 검사하고, 여기서는 길이만 제한한다.
 * 길이는 가입 규칙의 최대값과 같다 (그보다 긴 비밀번호로 가입한 회원은 없다).
 */
public record LoginRequest(
        @Schema(description = "이메일", example = "student@example.ac.kr")
        @NotBlank @Size(max = 254) String email,
        @Schema(description = "비밀번호", example = "Passw0rd!")
        @NotBlank @Size(max = 64) String password) {

    /** 비밀번호가 로그에 남지 않도록 가린다 */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
