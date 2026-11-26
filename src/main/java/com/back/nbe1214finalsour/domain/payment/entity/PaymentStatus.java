package com.back.nbe1214finalsour.domain.payment.entity;

public enum PaymentStatus {
    /** 주문 생성됨. 토스 승인을 보낸 적이 없어 돈이 빠졌을 가능성이 없다 */
    READY,
    /** 토스 승인을 호출했거나 호출 중이라 결과를 모른다. 돈이 빠졌을 수 있다 */
    IN_PROGRESS,
    /** 승인 완료, 크레딧 충전됨 */
    DONE,
    /** 결제창 실패·취소 또는 토스 승인 거절. 돈이 빠지지 않았다 */
    ABORTED,
    /** 시간 안에 끝나지 않아 스케줄러가 만료 처리함 */
    EXPIRED,
    /** 승인 후 취소(환불)됨. 환불 기능은 이번 범위 밖 */
    CANCELED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case READY -> next == IN_PROGRESS || next == ABORTED || next == EXPIRED;
            case IN_PROGRESS -> next == DONE || next == ABORTED || next == EXPIRED;
            case DONE -> next == CANCELED;
            case ABORTED, EXPIRED, CANCELED -> false; // 종료 상태
        };
    }
}
