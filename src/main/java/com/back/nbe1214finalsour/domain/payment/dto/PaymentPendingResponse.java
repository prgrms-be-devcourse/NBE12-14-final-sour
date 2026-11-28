package com.back.nbe1214finalsour.domain.payment.dto;

import com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus;

// 토스 결과를 몰라 확인 중일 때의 응답 (202). 스케줄러가 나중에 확정한다
public record PaymentPendingResponse(String orderId, PaymentStatus status) {

    public PaymentPendingResponse(String orderId) {
        this(orderId, PaymentStatus.IN_PROGRESS);
    }
}