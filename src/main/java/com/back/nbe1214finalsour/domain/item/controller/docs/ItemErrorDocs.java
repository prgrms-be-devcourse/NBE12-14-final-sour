package com.back.nbe1214finalsour.domain.item.controller.docs;

/**
 * 아이템 API가 내는 에러 설명.
 *
 * <p>에러 응답 본문은 {@code {"code": "<에러 코드>", "message": "<메시지>", "data": null}}이다.
 */
final class ItemErrorDocs {

    static final String UNAUTHORIZED = """
            `COMMON_UNAUTHORIZED` 토큰 없음 / `COMMON_INVALID_TOKEN` / `COMMON_EXPIRED_TOKEN` / \
            `AUTH_SESSION_REPLACED` 다른 기기에서 로그인함""";

    static final String HISTORY_INVALID_INPUT = """
            `COMMON_INVALID_INPUT` page가 0보다 작거나 size가 1~100 밖""";

    static final String PURCHASE_BAD_REQUEST = """
            `COMMON_INVALID_INPUT` Idempotency-Key 헤더가 없거나 UUID 형식이 아님 / \
            `ITEM_NOT_ENOUGH_SCORE` 물약 구매에 필요한 기여도 부족 / \
            `ITEM_NOT_ENOUGH_CREDIT` 로고 변경권 구매에 필요한 크레딧 부족 / \
            `ITEM_LOGO_IMAGE_REQUIRED` 로고 아이템인데 logoImage 없음 / \
            `ITEM_LOGO_IMAGE_TOO_LARGE` 5MB 초과 / \
            `ITEM_LOGO_IMAGE_INVALID_TYPE` 파일 내용이 PNG·JPG·WEBP가 아님""";

    static final String ITEM_NOT_FOUND = "`ITEM_NOT_FOUND` 없는 아이템";

    static final String DUPLICATE_PURCHASE = """
            `ITEM_DUPLICATE_PURCHASE` 같은 Idempotency-Key로 다시 요청함. \
            물약은 5분 안의 재요청, 로고 변경권은 이미 성공한 키면 언제든""";

    private ItemErrorDocs() {
    }
}