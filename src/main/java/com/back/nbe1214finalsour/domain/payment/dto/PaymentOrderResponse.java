package com.back.nbe1214finalsour.domain.payment.dto;

import com.back.nbe1214finalsour.domain.payment.entity.Payment;

// 프론트가 이 세 값을 그대로 토스 SDK requestPayment에 넘긴다
public record PaymentOrderResponse(String orderId, String orderName, long amount) {

    public PaymentOrderResponse(Payment payment){
        this(
                payment.getOrderId(),
                payment.getOrderName(),
                payment.getAmount()
        );
    }
}
