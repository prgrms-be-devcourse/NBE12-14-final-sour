package com.back.nbe1214finalsour.domain.support.dto;

import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import com.back.nbe1214finalsour.domain.support.entity.SupportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 관리자 문의 목록 한 줄 (추가 명세 §3-9). {@code recipientEmail}은 복호화한 원문이다 */
public record AdminSupportListItem(
        @Schema(description = "문의 ID", example = "1")
        Long supportId,
        @Schema(description = "제목", example = "문의 제목")
        String title,
        @Schema(description = "답변 수신 이메일", example = "student@example.ac.kr")
        String recipientEmail,
        @Schema(description = "회원 문의 여부", example = "true")
        boolean isMember,
        @Schema(description = "문의 상태", example = "RECEIVED")
        SupportStatus status,
        @Schema(description = "등록 시각", example = "2026-11-23T12:00:00+09:00")
        OffsetDateTime createdAt) {

    public static AdminSupportListItem from(CustomerSupport support) {
        return new AdminSupportListItem(support.getId(), support.getTitle(), support.getRecipientEmail(),
                support.isMember(), support.getStatus(), support.getCreatedAt());
    }
}
