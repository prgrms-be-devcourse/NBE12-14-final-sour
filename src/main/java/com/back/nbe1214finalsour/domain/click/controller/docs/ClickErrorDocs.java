package com.back.nbe1214finalsour.domain.click.controller.docs;

/**
 * 클릭·공격·지원 API가 함께 내는 에러 설명. 세 API 모두 {@code ClickActionGuard}를 거친다.
 *
 * <p>에러 응답 본문은 {@code {"code": "<에러 코드>", "message": "<메시지>", "data": null}}이다.
 */
final class ClickErrorDocs {

    static final String INVALID_INPUT = """
            `COMMON_INVALID_INPUT` 요청 값 검증 실패 (submissionId 없음, clickCount가 1~20 밖 등)""";

    static final String UNAUTHORIZED = """
            `COMMON_UNAUTHORIZED` 토큰 없음 / `COMMON_INVALID_TOKEN` / `COMMON_EXPIRED_TOKEN` / \
            `AUTH_SESSION_REPLACED` 다른 기기에서 로그인함""";

    static final String FORBIDDEN = """
            `USER_SUSPENDED` 이용이 제한된 계정 / \
            `ABUSE_VERIFICATION_REQUIRED` 어뷰징 의심 표시가 있어 봇 확인(Turnstile)이 먼저 필요함""";

    static final String USER_NOT_FOUND = "`USER_NOT_FOUND` 회원 없음";

    static final String DUPLICATE_SUBMISSION = """
            `CLICK_DUPLICATE_SUBMISSION` 10초 안에 같은 submissionId로 다시 제출함""";

    static final String TOO_MANY_REQUESTS = """
            `CLICK_TOO_FREQUENT` 10초에 12회 넘게 제출 / `CLICK_COUNT_EXCEEDED` 1분에 900클릭 넘게 제출. \
            둘 다 어뷰징 의심 표시가 남아 다음 제출부터 403 `ABUSE_VERIFICATION_REQUIRED`가 된다""";

    private ClickErrorDocs() {
    }
}
