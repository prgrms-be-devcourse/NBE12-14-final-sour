package com.back.nbe1214finalsour.domain.auth.dto;

import com.back.nbe1214finalsour.domain.user.validation.Nickname;
import com.back.nbe1214finalsour.domain.user.validation.Password;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/auth/signup} 요청. 학교는 받지 않고 가입 토큰의 이메일 도메인으로 서버가 정한다.
 *
 * @param password 규칙은 {@link Password}
 */
public record SignupRequest(
        @Schema(description = "가입 토큰", example = "00000000-0000-0000-0000-000000000000")
        @NotBlank @Size(max = 64) String signupToken,
        @Schema(description = "닉네임", example = "학생")
        @NotNull @Nickname String nickname,
        @Schema(description = "비밀번호", example = "Passw0rd!")
        @Password String password,
        @Schema(description = "학과 ID", example = "301")
        @NotNull @Positive Long departmentId) {

    /** 로그에 비밀번호가 남지 않도록 가린다 */
    @Override
    public String toString() {
        return "SignupRequest[signupToken=***, nickname=" + nickname
                + ", password=***, departmentId=" + departmentId + "]";
    }
}
