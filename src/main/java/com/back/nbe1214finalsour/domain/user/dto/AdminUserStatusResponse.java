package com.back.nbe1214finalsour.domain.user.dto;

import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** {@code PATCH /api/v1/admin/users/{userId}/status} 응답 (추가 명세 §3-9) */
public record AdminUserStatusResponse(
        @Schema(description = "회원 ID", example = "1")
        Long userId,
        @Schema(description = "변경된 회원 상태", example = "SUSPENDED")
        UserStatus status) {
}
