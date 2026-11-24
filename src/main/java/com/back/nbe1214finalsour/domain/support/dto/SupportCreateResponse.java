package com.back.nbe1214finalsour.domain.support.dto;

import com.back.nbe1214finalsour.domain.support.entity.SupportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** {@code POST /api/v1/supports} 응답 */
public record SupportCreateResponse(
        @Schema(description = "문의 ID", example = "1")
        Long supportId,
        @Schema(description = "문의 상태", example = "RECEIVED")
        SupportStatus status) {
}
