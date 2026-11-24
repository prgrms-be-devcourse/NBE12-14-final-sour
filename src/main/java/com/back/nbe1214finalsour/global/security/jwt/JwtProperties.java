package com.back.nbe1214finalsour.global.security.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 설정 ({@code jwt.*}).
 *
 * @param secret          HS256 서명 키. UTF-8로 32바이트 이상이어야 한다. 환경 변수 {@code JWT_SECRET}으로 넣고 저장소에 커밋하지 않는다
 * @param accessTokenTtl  access 토큰 유효 시간 (API 명세서: 60분)
 * @param refreshTokenTtl refresh 토큰 유효 시간 (API 명세서: 30일)
 */
@Validated
@ConfigurationProperties("jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl) {
}
