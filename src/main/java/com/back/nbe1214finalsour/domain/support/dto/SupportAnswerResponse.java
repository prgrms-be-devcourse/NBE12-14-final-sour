package com.back.nbe1214finalsour.domain.support.dto;

import com.back.nbe1214finalsour.domain.support.entity.SupportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** 관리자 문의 답변 결과 (추가 명세 §3-9) */
public record SupportAnswerResponse(
        @Schema(description = "문의 ID", example = "1")
        Long supportId,
        @Schema(description = "문의 상태", example = "ANSWERED")
        SupportStatus status) {
}
