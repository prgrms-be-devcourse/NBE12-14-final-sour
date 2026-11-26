package com.back.nbe1214finalsour.domain.clicker.controller.docs;

/**
 * 클리커 API가 내는 에러 설명.
 *
 * <p>에러 응답 본문은 {@code {"code": "<에러 코드>", "message": "<메시지>", "data": null}}이다.
 */
final class ClickerErrorDocs {

    static final String UNAUTHORIZED = """
            `COMMON_UNAUTHORIZED` 토큰 없음 / `COMMON_INVALID_TOKEN` / `COMMON_EXPIRED_TOKEN` / \
            `AUTH_SESSION_REPLACED` 다른 기기에서 로그인함""";

    static final String UPGRADE_BAD_REQUEST = """
            `CLICKER_MAX_LEVEL` 이미 최고 레벨 / \
            `CLICKER_NOT_ENOUGH_SCORE` 다음 레벨 비용보다 기여도가 적음""";

    static final String UPGRADE_CONFLICT = """
            `CLICKER_UPGRADE_CONFLICT` 같은 회원의 다른 업그레이드 요청이 먼저 처리됨. 레벨표와 내 정보를 다시 불러온 뒤 재시도""";

    private ClickerErrorDocs() {
    }
}