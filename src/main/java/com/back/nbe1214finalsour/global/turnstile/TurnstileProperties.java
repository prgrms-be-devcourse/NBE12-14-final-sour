package com.back.nbe1214finalsour.global.turnstile;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Turnstile 설정 ({@code turnstile.*}, SCRUM-117). 환경 변수 {@code TURNSTILE_ENABLED}·{@code TURNSTILE_SECRET}·
 * {@code TURNSTILE_HOSTNAMES}로 넣는다. 켰을 때의 필수 값 검사는 {@link TurnstileConfig}가 한다.
 *
 * @param enabled   켜기. 기본값 false (꺼져 있으면 지금과 동작이 같다)
 * @param secret    위젯 secret. 저장소·로그에 남기지 않는다. {@link #toString()}에서도 가린다
 * @param hostnames siteverify가 돌려준 hostname으로 허용할 프론트 주소. 공백과 빈 칸은 무시한다.
 *                  prod에는 {@code localhost}·{@code 127.0.0.1}을 넣을 수 없다
 * @param timeout   siteverify 연결·응답 시간 초과. 기본값 3초. 넘으면 한 번 더 시도하고, 그래도 안 되면 연결 불가로 본다
 */
@ConfigurationProperties("turnstile")
public record TurnstileProperties(
        boolean enabled,
        @Nullable String secret,
        List<String> hostnames,
        Duration timeout) {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    public TurnstileProperties(
            boolean enabled, @Nullable String secret, @Nullable List<String> hostnames, @Nullable Duration timeout) {
        this.enabled = enabled;
        this.secret = secret;
        this.hostnames = hostnames == null ? List.of() : hostnames.stream()
                .map(String::trim)
                .filter(hostname -> !hostname.isEmpty())
                .distinct()
                .toList();
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    boolean hasSecret() {
        return secret != null && !secret.isBlank();
    }

    @Override
    public String toString() {
        return "TurnstileProperties[enabled=%s, secret=%s, hostnames=%s, timeout=%s]"
                .formatted(enabled, hasSecret() ? "****" : "(empty)", hostnames, timeout);
    }
}
