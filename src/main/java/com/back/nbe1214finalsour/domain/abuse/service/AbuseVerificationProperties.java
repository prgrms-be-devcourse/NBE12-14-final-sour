package com.back.nbe1214finalsour.domain.abuse.service;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 클릭 어뷰징 의심 표시와 봇 확인 ({@code abuse.verification.*}, SCRUM-118, 계획 P2·P7).
 * 운영 중 환경 변수(예: {@code ABUSE_VERIFICATION_FLAGTTL})로 바꿀 수 있다.
 *
 * @param flagTtl       의심 표시 유지 시간. 확인을 통과하면 바로 지워지므로, 확인하지 않고 떠난 경우 언제 풀어 줄지다
 * @param maxAttempts   회원당 확인 API 허용 횟수. 넘으면 429 {@code COMMON_TOO_MANY_REQUESTS}
 * @param attemptWindow 확인 횟수를 세는 시간. 첫 시도부터 잰다 (고정 창)
 */
@Validated
@ConfigurationProperties("abuse.verification")
public record AbuseVerificationProperties(
        @NotNull Duration flagTtl,
        @Positive int maxAttempts,
        @NotNull Duration attemptWindow) {
}
