package com.back.nbe1214finalsour.domain.payment.dto;

import com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus;

// 결제 실패 기록 응답. 서비스가 주문을 읽지 않으므로 요청의 orderId와 ABORTED로 만든다
public record PaymentFailResponse(String orderId, PaymentStatus status) {
}
