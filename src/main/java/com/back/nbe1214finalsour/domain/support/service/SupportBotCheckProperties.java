package com.back.nbe1214finalsour.domain.support.service;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 비회원 문의 봇 확인 기준 ({@code support.bot-check.*}, SCRUM-119, 계획 P3). 창과 한도는
 * {@link SupportRateLimitProperties}의 비회원 IP 한도를 같이 쓴다.
 *
 * @param verifyFrom 같은 IP의 창 안에서 몇 번째 문의부터 확인을 요구하는지. 1이면 모든 비회원 문의에 요구한다
 */
@Validated
@ConfigurationProperties("support.bot-check")
public record SupportBotCheckProperties(@Min(1) int verifyFrom) {
}
