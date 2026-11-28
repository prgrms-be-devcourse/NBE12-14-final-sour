package com.back.nbe1214finalsour.domain.payment.dto;

import com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus;
import com.back.nbe1214finalsour.domain.payment.service.PaymentConfirmResult;
import com.back.nbe1214finalsour.global.time.ClockConfig;
import java.time.OffsetDateTime;

// 결제 승인 완료 응답 (200). 승인 시각은 팀 규칙대로 KST로 바꿔 응답한다
public record PaymentConfirmResponse(
        String orderId,
        PaymentStatus status,
        String method,
        OffsetDateTime approvedAt,
        long chargedCredit,
        long creditBalance) {

    // 완료(DONE) 결과로만 만든다. 확인 중(IN_PROGRESS)은 PaymentPendingResponse를 쓴다
    public PaymentConfirmResponse(PaymentConfirmResult result) {
        this(
                result.orderId(),
                result.status(),
                result.method(),
                result.approvedAt().atZoneSameInstant(ClockConfig.KST).toOffsetDateTime(),
                result.chargedCredit(),
                result.creditBalance()
        );
    }
}