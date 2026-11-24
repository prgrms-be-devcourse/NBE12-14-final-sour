package com.back.nbe1214finalsour.domain.support.dto;

import com.back.nbe1214finalsour.domain.support.entity.SupportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 내 문의 목록 한 줄 (추가 명세 §3-8). 본문·이메일은 읽지 않는다 */
public record SupportListItem(
        @Schema(description = "문의 ID", example = "1")
        Long supportId,
        @Schema(description = "제목", example = "문의 제목")
        String title,
        @Schema(description = "문의 상태", example = "RECEIVED")
        SupportStatus status,
        @Schema(description = "등록 시각", example = "2026-11-23T12:00:00+09:00")
        OffsetDateTime createdAt) {
}
