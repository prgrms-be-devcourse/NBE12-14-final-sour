package com.back.nbe1214finalsour.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 결제 승인 요청. successUrl 쿼리의 세 값을 그대로 보낸다
// 길이: paymentKey는 토스 최대 200자(payment_key 컬럼과 같음), orderId는 order_id 64자
// amount가 Long인 이유: 값이 빠진 요청을 @NotNull로 막으려면 null을 담을 수 있어야 한다
public record PaymentConfirmRequest(
        @NotBlank @Size(max = 200) String paymentKey,
        @NotBlank @Size(max = 64) String orderId,
        @NotNull Long amount) {
}