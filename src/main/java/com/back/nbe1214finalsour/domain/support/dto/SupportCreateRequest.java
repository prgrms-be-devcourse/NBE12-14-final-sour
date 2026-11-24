package com.back.nbe1214finalsour.domain.support.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * {@code POST /api/v1/supports} 요청 (추가 명세 §3-8).
 *
 * <p>{@code recipientEmail}은 검증 전에 앞뒤 공백만 지운다 (계획 D3). 본문 길이 상한 5,000자는 명세에 없고 계획 D2에서 정했다.
 *
 * @param attachFileUrl  회원만 보낼 수 있다. 빈 문자열은 첨부 없음으로 본다
 * @param turnstileToken 비회원이 같은 IP에서 몰아서 문의할 때만 필요한 봇 확인 토큰 (SCRUM-119). 회원 요청에서는 무시한다.
 *                       빈 문자열은 위젯을 불러오지 못했다는 뜻이라 {@code null}로 바꾸지 않는다
 */
public record SupportCreateRequest(
        @Schema(description = "답변 수신 이메일", example = "student@example.ac.kr")
        @NotBlank @Email @Size(max = 254) String recipientEmail,
        @Schema(description = "제목", example = "문의 제목")
        @NotBlank @Size(max = 100) String title,
        @Schema(description = "본문", example = "문의 내용입니다.")
        @NotBlank @Size(max = 5000) String content,
        @Schema(description = "첨부 파일 URL", example = "https://example.com/attachment.png")
        @Nullable String attachFileUrl,
        @Schema(description = "비회원 봇 확인 토큰", example = "example-turnstile-token")
        @Nullable @Size(max = 2048) String turnstileToken) {

    public SupportCreateRequest {
        recipientEmail = recipientEmail == null ? null : recipientEmail.strip();
        attachFileUrl = attachFileUrl == null || attachFileUrl.isBlank() ? null : attachFileUrl;
    }
}
