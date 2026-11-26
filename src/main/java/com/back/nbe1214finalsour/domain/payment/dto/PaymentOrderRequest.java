package com.back.nbe1214finalsour.domain.payment.dto;

import jakarta.validation.constraints.NotNull;

// 충전 주문 요청. 회원은 본문이 아니라 토큰에서 꺼낸다
public record PaymentOrderRequest(@NotNull Long amount) {
}
