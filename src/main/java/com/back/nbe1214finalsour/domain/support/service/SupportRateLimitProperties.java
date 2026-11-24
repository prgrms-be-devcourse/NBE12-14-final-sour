package com.back.nbe1214finalsour.domain.support.service;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 문의 등록 한도 ({@code support.rate-limit.*}, SCRUM-110, 계획 Q7·D1). 회원은 회원별로, 비회원은 IP별로 따로 센다.
 *
 * @param maxRequests 창 하나에서 허용하는 등록 수. 넘으면 429 {@code COMMON_TOO_MANY_REQUESTS}
 * @param window      횟수를 세는 시간. 첫 등록부터 잰다 (고정 창)
 */
@Validated
@ConfigurationProperties("support.rate-limit")
public record SupportRateLimitProperties(
        @Positive int maxRequests,
        @NotNull Duration window) {
}
