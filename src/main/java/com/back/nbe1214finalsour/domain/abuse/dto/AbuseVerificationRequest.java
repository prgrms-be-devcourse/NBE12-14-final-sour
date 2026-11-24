package com.back.nbe1214finalsour.domain.abuse.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * 클릭 봇 확인 요청 (SCRUM-118).
 *
 * @param turnstileToken 위젯이 준 토큰. 위젯을 못 불러왔으면 비워서 보낸다 (계획 P6: 서버가 Cloudflare에 닿지 못하면 통과).
 *                       Cloudflare 문서상 최대 2048자
 */
public record AbuseVerificationRequest(
        @Schema(description = "클릭 봇 확인용 Turnstile 토큰", example = "example-turnstile-token")
        @Nullable @Size(max = 2048, message = "turnstileToken은 2048자 이하여야 합니다.") String turnstileToken) {
}
