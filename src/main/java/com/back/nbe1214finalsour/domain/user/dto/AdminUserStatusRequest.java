package com.back.nbe1214finalsour.domain.user.dto;

import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * {@code PATCH /api/v1/admin/users/{userId}/status} 요청 (추가 명세 §3-9).
 *
 * @param status {@code ACTIVE}·{@code SUSPENDED}·{@code BANNED}. {@code WITHDRAWN}은 서비스가 400으로 거절하고,
 *               그 밖의 문자열은 본문을 읽지 못해 400이다
 */
public record AdminUserStatusRequest(
        @Schema(description = "변경할 회원 상태", example = "SUSPENDED")
        @NotNull UserStatus status) {
}
