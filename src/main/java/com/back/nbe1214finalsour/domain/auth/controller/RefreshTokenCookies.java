package com.back.nbe1214finalsour.domain.auth.controller;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * {@code refreshToken} 쿠키 (API 명세서 §1: HttpOnly, Secure, SameSite=Strict).
 *
 * <p>Path는 인증 API로 좁혀 다른 API 요청에는 쿠키가 실리지 않게 한다. URL 접두어 결정(회원 MVP 계획 Q1)에 따라
 * 명세서의 {@code /api/auth} 대신 {@code /api/v1/auth}다.
 */
public final class RefreshTokenCookies {

    static final String NAME = "refreshToken";
    static final String PATH = "/api/v1/auth";

    private RefreshTokenCookies() {
    }

    static ResponseCookie issue(String refreshToken, Duration maxAge) {
        return cookie(refreshToken, maxAge);
    }

    /** 로그아웃·비밀번호 변경·탈퇴: 같은 이름·Path에 {@code Max-Age=0}을 보내 브라우저가 지우게 한다 */
    public static ResponseCookie expire() {
        return cookie("", Duration.ZERO);
    }

    private static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
