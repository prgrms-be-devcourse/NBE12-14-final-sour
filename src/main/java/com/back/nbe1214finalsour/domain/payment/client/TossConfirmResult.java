package com.back.nbe1214finalsour.domain.payment.client;

import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;

// 토스 승인 API 응답을 분류한 결과. type에 따라 채워지는 필드가 다르다
// APPROVED  : method, approvedAt
// REJECTED  : code, message (토스 거절 코드·메시지)
// KEY_ERROR : code, message (우리 시크릿 키 설정 오류)
// UNKNOWN   : message (결과를 모르는 이유. 토스 코드가 있으면 code에도)
public record TossConfirmResult(
        Type type,
        @Nullable String method,
        @Nullable OffsetDateTime approvedAt,
        @Nullable String code,
        @Nullable String message) {

    public enum Type {
        APPROVED,   // 200이고 status가 DONE. 돈이 결제됐다
        REJECTED,   // 4xx 거절 (한도초과, 정지 카드 등). 돈이 결제되지 않았다
        KEY_ERROR,  // 우리 시크릿 키 설정 오류. 돈이 결제되지 않았고, 사용자 잘못이 아니다
        UNKNOWN     // 결제됐는지 알 수 없다 (5xx, 시간 초과, 이미 처리 중 등). 스케줄러가 나중에 확정한다
    }
}