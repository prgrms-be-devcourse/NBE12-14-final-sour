package com.back.nbe1214finalsour.domain.support.dto;

import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import com.back.nbe1214finalsour.domain.support.entity.SupportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * 관리자 문의 상세 (SCRUM-173, 계획 .agents/01-PLAN/261119-1038-scrum173_admin_supports.md B1). 회원 상세와 같은 본문·답변에
 * 관리자 목록의 수신 이메일(복호화한 원문)과 회원 여부를 더한다. 답변 전이면 {@code answer}는 {@code null}이다.
 */
public record AdminSupportDetailResponse(
        @Schema(description = "문의 ID", example = "1")
        Long supportId,
        @Schema(description = "제목", example = "문의 제목")
        String title,
        @Schema(description = "본문", example = "문의 내용입니다.")
        String content,
        @Schema(description = "첨부 파일 URL", example = "https://example.com/attachment.png")
        @Nullable String attachFileUrl,
        @Schema(description = "답변 수신 이메일", example = "student@example.ac.kr")
        String recipientEmail,
        @Schema(description = "회원 문의 여부", example = "true")
        boolean isMember,
        @Schema(description = "문의 상태", example = "ANSWERED")
        SupportStatus status,
        @Schema(description = "문의 답변")
        SupportDetailResponse.@Nullable Answer answer,
        @Schema(description = "등록 시각", example = "2026-11-23T12:00:00+09:00")
        OffsetDateTime createdAt) {

    public static AdminSupportDetailResponse from(CustomerSupport support) {
        SupportDetailResponse.Answer answer = support.getStatus() == SupportStatus.ANSWERED
                ? new SupportDetailResponse.Answer(support.getAnswerTitle(), support.getAnswerContent())
                : null;
        return new AdminSupportDetailResponse(support.getId(), support.getTitle(), support.getContent(),
                support.getAttachFileUrl(), support.getRecipientEmail(), support.isMember(), support.getStatus(),
                answer, support.getCreatedAt());
    }
}
