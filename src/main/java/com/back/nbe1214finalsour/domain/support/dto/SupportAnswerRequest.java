package com.back.nbe1214finalsour.domain.support.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 관리자 문의 답변 (추가 명세 §3-9). 길이는 계획 D2 */
public record SupportAnswerRequest(
        @Schema(description = "답변 제목", example = "답변 제목")
        @NotBlank @Size(max = 100) String answerTitle,
        @Schema(description = "답변 내용", example = "답변 내용입니다.")
        @NotBlank @Size(max = 5000) String answerContent) {
}
