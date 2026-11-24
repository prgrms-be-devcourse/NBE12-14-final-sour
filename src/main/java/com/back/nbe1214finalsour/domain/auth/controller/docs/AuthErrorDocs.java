package com.back.nbe1214finalsour.domain.auth.controller.docs;

/** 인증 API에서 공통으로 발생하는 오류 설명. */
final class AuthErrorDocs {
    static final String INVALID_INPUT = "`COMMON_INVALID_INPUT` 요청 값 검증 실패";
    static final String EMAIL_REGISTERED = "`AUTH_EMAIL_ALREADY_REGISTERED` 이미 가입된 이메일";
    static final String IP_LIMIT = "`AUTH_TOO_MANY_REQUESTS` IP 기준 요청 한도 초과";
    static final String UNAUTHORIZED = "`COMMON_UNAUTHORIZED` 인증 필요 / `COMMON_INVALID_TOKEN` 유효하지 않은 토큰 / "
            + "`COMMON_EXPIRED_TOKEN` 만료된 토큰 / `AUTH_SESSION_REPLACED` 다른 기기에서 로그인함";

    private AuthErrorDocs() {
    }
}
