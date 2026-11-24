package com.back.nbe1214finalsour.domain.user.dto;

import com.back.nbe1214finalsour.domain.user.validation.Password;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/v1/users/me/password} 요청 (추가 명세 §3-1).
 *
 * @param currentPassword 확인만 하므로 로그인과 같이 길이만 본다
 * @param newPassword     규칙은 {@link Password}
 */
public record ChangePasswordRequest(
        @Schema(description = "현재 비밀번호", example = "Passw0rd!")
        @NotBlank @Size(max = 64) String currentPassword,
        @Schema(description = "새 비밀번호", example = "Newpass1!")
        @Password String newPassword) {

    /** 로그에 비밀번호가 남지 않도록 가린다 */
    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=***, newPassword=***]";
    }
}
