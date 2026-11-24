package com.back.nbe1214finalsour.domain.auth.dto;

import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.global.security.Role;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 로그인 응답. refresh 토큰은 본문이 아니라 {@code refreshToken} 쿠키로 보낸다.
 *
 * @param expiresInSeconds access 토큰 유효 시간(초). 프론트는 이 값으로 만료 전에 재발급한다
 */
public record LoginResponse(
        @Schema(description = "access 토큰", example = "example-access-token")
        String accessToken,
        @Schema(description = "토큰 유효 시간(초)", example = "3600")
        long expiresInSeconds,
        @Schema(description = "로그인 회원 정보")
        LoginUser user) {

    public record LoginUser(
            @Schema(description = "회원 ID", example = "1")
            Long id,
            @Schema(description = "닉네임", example = "학생")
            String nickname,
            @Schema(description = "학교 ID", example = "12")
            Long universityId,
            @Schema(description = "회원 권한", example = "USER")
            Role role) {

        public static LoginUser from(User user) {
            return new LoginUser(user.getId(), user.getNickname(), user.getUniversityId(), user.getRole());
        }
    }
}
