package com.back.nbe1214finalsour.domain.payment.entity;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import java.util.Arrays;
import java.util.Locale;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChargeAmount {
    KRW_2500(2_500),
    KRW_5000(5_000),
    KRW_10000(10_000);

    private final long amount;

    public static ChargeAmount from(long amount) {
        return Arrays.stream(values())
                .filter(chargeAmount -> chargeAmount.amount == amount)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PAY_INVALID_AMOUNT));
    }

    public String orderName() {
        // 서버 기본 로케일과 무관하게 천 단위 구분자를 쉼표로 고정한다
        return String.format(Locale.ROOT, "크레딧 %,d", amount);
    }
}
