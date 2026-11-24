package com.back.nbe1214finalsour.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code GET /api/v1/users/nickname/availability} 응답 */
public record NicknameAvailabilityResponse(
        @Schema(description = "닉네임 사용 가능 여부", example = "true")
        boolean available) {
}
