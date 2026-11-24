package com.back.nbe1214finalsour.domain.support.controller.docs;

/** 문의 API에서 공통으로 발생하는 오류 설명. */
public final class SupportErrorDocs {
    public static final String INVALID_INPUT = "`COMMON_INVALID_INPUT` 요청 값 검증 또는 형식 오류";
    public static final String TOKEN_ERROR = "`COMMON_INVALID_TOKEN` 유효하지 않은 토큰 / "
            + "`COMMON_EXPIRED_TOKEN` 만료된 토큰 / `AUTH_SESSION_REPLACED` 다른 기기에서 로그인함";
    public static final String UNAUTHORIZED = "`COMMON_UNAUTHORIZED` 인증 필요 / " + TOKEN_ERROR;
    public static final String FORBIDDEN_ADMIN = "`COMMON_FORBIDDEN` ADMIN 권한 없음";
    public static final String NOT_FOUND = "`SUPPORT_NOT_FOUND` 존재하지 않는 문의";

    private SupportErrorDocs() {
    }
}
