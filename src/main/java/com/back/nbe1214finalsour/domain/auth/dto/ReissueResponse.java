package com.back.nbe1214finalsour.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 재발급 응답. 새 refresh 토큰은 {@code refreshToken} 쿠키로 보낸다.
 *
 * @param expiresInSeconds access 토큰 유효 시간(초)
 */
public record ReissueResponse(
        @Schema(description = "access 토큰", example = "example-access-token")
        String accessToken,
        @Schema(description = "토큰 유효 시간(초)", example = "3600")
        long expiresInSeconds) {
}
