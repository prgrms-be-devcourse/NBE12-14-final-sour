package com.back.nbe1214finalsour.domain.user.controller.docs;

/** 회원 API에서 공통으로 발생하는 오류 설명. */
public final class UserErrorDocs {
    public static final String INVALID_INPUT = "`COMMON_INVALID_INPUT` 요청 값 검증 실패";
    public static final String UNAUTHORIZED = "`COMMON_UNAUTHORIZED` 인증 필요 또는 없는 회원 / "
            + "`COMMON_INVALID_TOKEN` 유효하지 않은 토큰 / `COMMON_EXPIRED_TOKEN` 만료된 토큰 / "
            + "`AUTH_SESSION_REPLACED` 다른 기기에서 로그인함";
    public static final String INVALID_PASSWORD = "`USER_INVALID_PASSWORD` 비밀번호 불일치";

    private UserErrorDocs() {
    }
}
