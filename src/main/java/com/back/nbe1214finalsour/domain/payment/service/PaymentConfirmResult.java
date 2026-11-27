package com.back.nbe1214finalsour.domain.payment.service;

import com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;

// 결제 승인 결과. status는 그 순간 주문의 실제 상태다
// DONE: 승인·충전 완료 (200) / IN_PROGRESS: 아직 토스 결과가 없음 (tx1 직후면 토스를 불러야 하고, 토스 결과를 모르면 202)
public record PaymentConfirmResult(
        String orderId,
        PaymentStatus status,
        @Nullable String method,
        @Nullable OffsetDateTime approvedAt,
        @Nullable Long chargedCredit,
        @Nullable Long creditBalance) {

    // IN_PROGRESS. 아직 충전하지 않았다
    public PaymentConfirmResult(String orderId) {
        this(orderId, PaymentStatus.IN_PROGRESS, null, null, null, null);
    }
}