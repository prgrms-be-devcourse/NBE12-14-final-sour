package com.back.nbe1214finalsour.domain.auth.service;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 인증 API의 IP 기준 한도 ({@code auth.ip-limit.*}, SCRUM-100). 학교 와이파이처럼 여러 학생이 한 IP를 쓰므로 넉넉히 잡고,
 * 운영 중 환경 변수(예: {@code AUTH_IPLIMIT_LOGINMAXFAILURES})로 바꿀 수 있다.
 *
 * @param loginMaxFailures    IP당 로그인 실패 허용 횟수. 넘으면 {@code loginWindow}가 끝날 때까지 그 IP의 로그인을 모두 거절
 * @param loginWindow         로그인 실패 횟수를 세는 시간. 첫 실패부터 잰다
 * @param emailCodeMaxRequests IP당 인증 코드 요청 허용 횟수 (학교 도메인이 맞는 요청만 센다)
 * @param emailCodeWindow     인증 코드 요청 횟수를 세는 시간. 첫 요청부터 잰다
 */
@Validated
@ConfigurationProperties("auth.ip-limit")
public record AuthIpLimitProperties(
        @Positive int loginMaxFailures,
        @NotNull Duration loginWindow,
        @Positive int emailCodeMaxRequests,
        @NotNull Duration emailCodeWindow) {
}
