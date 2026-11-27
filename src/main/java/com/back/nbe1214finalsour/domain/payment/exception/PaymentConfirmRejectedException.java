package com.back.nbe1214finalsour.domain.payment.exception;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

// 토스가 승인을 거절했다. 응답 data에 토스 code·message를 담아 사용자가 거절 사유를 보게 한다
// BusinessException의 자식이라 GlobalExceptionHandler의 BusinessException 처리기와 전용 처리기 둘 다 맞지만,
// Spring은 상속 단계가 더 가까운 전용 처리기를 실행한다. 전용 처리기가 없어도 BusinessException 처리기가 400으로 응답한다
@Getter
public class PaymentConfirmRejectedException extends BusinessException {

    private final String tossCode;
    private final @Nullable String tossMessage;

    public PaymentConfirmRejectedException(String tossCode, @Nullable String tossMessage) {
        super(ErrorCode.PAY_CONFIRM_FAILED, "토스 승인 거절: " + tossCode);
        this.tossCode = tossCode;
        this.tossMessage = tossMessage;
    }
}