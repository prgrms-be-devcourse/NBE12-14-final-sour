package com.back.nbe1214finalsour.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/users/me/withdrawal} 요청 (추가 명세 §3-1). 비밀번호를 본문으로 받아야 해서 DELETE 대신 POST다.
 *
 * @param password 확인만 하므로 로그인과 같이 길이만 본다
 */
public record WithdrawalRequest(
        @Schema(description = "현재 비밀번호", example = "Passw0rd!")
        @NotBlank @Size(max = 64) String password) {

    /** 로그에 비밀번호가 남지 않도록 가린다 */
    @Override
    public String toString() {
        return "WithdrawalRequest[password=***]";
    }
}
