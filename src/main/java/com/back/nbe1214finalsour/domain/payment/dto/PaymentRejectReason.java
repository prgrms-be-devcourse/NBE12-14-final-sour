package com.back.nbe1214finalsour.domain.payment.dto;

import org.jspecify.annotations.Nullable;

// 토스가 승인을 거절한 사유. 400 PAY_CONFIRM_FAILED 응답의 data
public record PaymentRejectReason(String tossCode, @Nullable String tossMessage) {
}