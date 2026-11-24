package com.back.nbe1214finalsour.domain.support.dto;

import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import com.back.nbe1214finalsour.domain.support.entity.SupportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * 문의 상세 (추가 명세 §3-8). 답변 전이면 {@code answer}는 {@code null}이다. 수신 이메일은 명세 응답에 없어 내보내지 않는다.
 */
public record SupportDetailResponse(
        @Schema(description = "문의 ID", example = "1")
        Long supportId,
        @Schema(description = "제목", example = "문의 제목")
        String title,
        @Schema(description = "본문", example = "문의 내용입니다.")
        String content,
        @Schema(description = "첨부 파일 URL", example = "https://example.com/attachment.png")
        @Nullable String attachFileUrl,
        @Schema(description = "문의 상태", example = "ANSWERED")
        SupportStatus status,
        @Schema(description = "문의 답변")
        @Nullable Answer answer,
        @Schema(description = "등록 시각", example = "2026-11-23T12:00:00+09:00")
        OffsetDateTime createdAt) {

    public record Answer(
            @Schema(description = "답변 제목", example = "답변 제목")
            String title,
            @Schema(description = "답변 본문", example = "답변 내용입니다.")
            String content) {
    }

    public static SupportDetailResponse from(CustomerSupport support) {
        Answer answer = support.getStatus() == SupportStatus.ANSWERED
                ? new Answer(support.getAnswerTitle(), support.getAnswerContent())
                : null;
        return new SupportDetailResponse(support.getId(), support.getTitle(), support.getContent(),
                support.getAttachFileUrl(), support.getStatus(), answer, support.getCreatedAt());
    }
}
