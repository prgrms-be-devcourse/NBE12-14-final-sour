package com.back.nbe1214finalsour.domain.payment.controller.docs;

/**
 * 결제 API가 내는 에러 설명.
 *
 * <p>에러 응답 본문은 {@code {"code": "<에러 코드>", "message": "<메시지>", "data": null}}이다.
 * 입력 검증 실패(COMMON_INVALID_INPUT)는 data에 필드 목록이, 토스 승인 거절(PAY_CONFIRM_FAILED)은 data에 거절 사유가 들어간다.
 */
final class PaymentErrorDocs {

    static final String UNAUTHORIZED = """
            `COMMON_UNAUTHORIZED` 토큰 없음 / `COMMON_INVALID_TOKEN` / `COMMON_EXPIRED_TOKEN` / \
            `AUTH_SESSION_REPLACED` 다른 기기에서 로그인함""";

    static final String ORDER_BAD_REQUEST = """
            `COMMON_INVALID_INPUT` amount가 없거나 본문이 올바른 JSON이 아님 / \
            `PAY_INVALID_AMOUNT` 허용 금액(2,500·5,000·10,000)이 아님""";

    static final String FAIL_BAD_REQUEST = """
            `COMMON_INVALID_INPUT` orderId·code가 없거나 공백, 길이 초과(orderId 64·code 100·message 255자), \
            본문이 올바른 JSON이 아님""";

    static final String FAIL_NOT_FOUND = """
            `PAY_ORDER_NOT_FOUND` 주문이 없음 (다른 회원의 주문도 같은 응답)""";

    static final String FAIL_CONFLICT = """
            `PAY_ALREADY_PROCESSED` 이미 승인 진행 중이거나 완료·만료·취소된 주문. 결제 내역을 확인하도록 안내한다""";

    static final String CONFIRM_BAD_REQUEST = """
            `COMMON_INVALID_INPUT` paymentKey·orderId·amount가 없거나 공백, 길이 초과(paymentKey 200·orderId 64자), \
            본문이 올바른 JSON이 아님 / \
            `PAY_AMOUNT_MISMATCH` 요청 금액이 주문 금액과 다름 / \
            `PAY_CONFIRM_FAILED` 토스가 승인을 거절함. data에 토스 거절 사유 {tossCode, tossMessage}가 들어간다""";

    static final String CONFIRM_NOT_FOUND = """
            `PAY_ORDER_NOT_FOUND` 주문이 없음 (다른 회원의 주문도 같은 응답)""";

    static final String CONFIRM_CONFLICT = """
            `PAY_ALREADY_PROCESSED` 이미 승인 진행 중이거나 실패·만료·취소된 주문, 또는 다른 결제 키로 이미 승인된 주문""";

    static final String CONFIRM_SERVER_ERROR = """
            `COMMON_INTERNAL_ERROR` 서버 설정 오류 등. 결제는 일어나지 않았다""";

    private PaymentErrorDocs() {
    }
}
